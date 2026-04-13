package io.heckel.ntfy.msg

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip04Dm.crypto.Nip04
import com.vitorpamplona.quartz.nip44Encryption.Nip44v2
import io.heckel.ntfy.crypto.KeyManager
import io.heckel.ntfy.db.Action
import io.heckel.ntfy.db.Icon
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.deriveNotificationId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val TAG = "NstrfyParser"
private const val PRIORITY_URGENT = 5
private const val PRIORITY_HIGH = 4
private const val PRIORITY_DEFAULT = 3
private const val PRIORITY_LOW = 2
private const val PRIORITY_MIN = 1

/**
 * Parses a nostr kind 30078 event into a (Subscription, Notification) pair.
 *
 * Protocol:
 * - content is NIP-44 encrypted JSON (primary) or NIP-04 encrypted JSON (fallback)
 * - for public subscriptions (inbox=false), plain JSON is also accepted
 * - topic routing: decrypted payload.topic matched against subscription.topic
 * - empty/null subscription.topic = catch-all
 */
class NostrNotificationParser(private val keyManager: KeyManager) {

    private val nip44 = Nip44v2()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class ParsedNotification(
        val subscription: Subscription,
        val notification: Notification
    )

    /**
     * Attempt to parse an event against the given subscriptions.
     * Returns null if the event cannot be decrypted, parsed, or routed.
     */
    fun parse(event: Event, subscriptions: List<Subscription>): ParsedNotification? {
        val senderPubkeyHex = event.pubKey
        val (payload, encryptionMethod) = decryptOrParse(event) ?: return null

        // Route to subscription by topic
        val subscription = findSubscription(payload.topic, subscriptions) ?: run {
            Log.d(TAG, "No subscription for topic='${payload.topic}', discarding")
            return null
        }

        val sequenceId = event.tags.find { it.size >= 2 && it[0] == "d" }?.get(1) ?: event.id
        val priority = parsePriority(payload.priority)
        val tagsStr = payload.tags?.joinToString(",") ?: ""
        val senderNpub = try {
            com.vitorpamplona.quartz.nip19Bech32.bech32.Bech32.encodeBytes(
                "npub", com.vitorpamplona.quartz.utils.Hex.decode(senderPubkeyHex),
                com.vitorpamplona.quartz.nip19Bech32.bech32.Bech32.Encoding.Bech32
            )
        } catch (e: Exception) { senderPubkeyHex }
        val notifId = deriveNotificationId(NostrConstants.NOSTR_BASE_URL, subscription.topic, sequenceId)
        val icon = payload.icon?.takeIf { it.isNotBlank() }?.let { Icon(it) }
        val actions = payload.actions?.mapNotNull { it.toAction() }

        val notification = Notification(
            id = event.id,
            subscriptionId = subscription.id,
            timestamp = event.createdAt,
            sequenceId = sequenceId,
            title = payload.title ?: "",
            message = payload.message,
            contentType = senderNpub, // Store sender npub for display
            encoding = encryptionMethod,
            notificationId = notifId,
            priority = priority,
            tags = tagsStr,
            click = payload.click ?: "",
            icon = icon,
            actions = actions?.ifEmpty { null },
            attachment = null,
            deleted = false
        )

        Log.d(TAG, "Parsed notification for topic='${subscription.topic}' " +
                "from sender=${senderPubkeyHex.take(8)}... priority=$priority")
        return ParsedNotification(subscription, notification)
    }

    // ---

    private fun decryptOrParse(event: Event): Pair<NostrPayload, String>? {
        // Try decryption if we have a key
        if (keyManager.hasKey()) {
            val privKey = try { keyManager.getPrivKeyBytes() } catch (e: Exception) { null }
            val senderPubKeyBytes = try {
                com.vitorpamplona.quartz.utils.Hex.decode(event.pubKey)
            } catch (e: Exception) { null }

            if (privKey != null && senderPubKeyBytes != null) {
                // 1. Try NIP-44
                val nip44Result = runCatching { nip44.decrypt(event.content, privKey, senderPubKeyBytes) }
                if (nip44Result.isSuccess) {
                    val plaintext = nip44Result.getOrNull()
                    if (plaintext != null) {
                        val payload = parsePayload(plaintext, "NIP-44")
                        if (payload != null) return Pair(payload, "nip44")
                    }
                }

                // 2. Fallback: NIP-04
                val nip04Result = runCatching { Nip04.decrypt(event.content, privKey, senderPubKeyBytes) }
                if (nip04Result.isSuccess) {
                    val plaintext = nip04Result.getOrNull()
                    if (plaintext != null) {
                        val payload = parsePayload(plaintext, "NIP-04")
                        if (payload != null) return Pair(payload, "nip04")
                    }
                }
            }
        }

        // 3. Last resort: treat content as plain JSON (public/unencrypted events)
        val payload = parsePayload(event.content, "plain")
        return if (payload != null) Pair(payload, "plain") else null
    }

    private fun parsePayload(plaintext: String, method: String): NostrPayload? {
        return runCatching {
            json.decodeFromString<NostrPayload>(plaintext).also {
                Log.d(TAG, "Decoded payload via $method: topic=${it.topic} title=${it.title}")
            }
        }.getOrElse {
            Log.d(TAG, "Failed to parse payload via $method: ${it.message}")
            null
        }
    }

    private fun findSubscription(topic: String?, subscriptions: List<Subscription>): Subscription? {
        if (!topic.isNullOrBlank()) {
            val exact = subscriptions.firstOrNull { it.topic == topic }
            if (exact != null) return exact
        }
        // Catch-all: subscription with empty topic
        return subscriptions.firstOrNull { it.topic.isBlank() }
    }

    private fun parsePriority(priority: String?): Int = when (priority?.lowercase()) {
        "urgent", "max" -> PRIORITY_URGENT
        "high" -> PRIORITY_HIGH
        "low" -> PRIORITY_LOW
        "min" -> PRIORITY_MIN
        else -> PRIORITY_DEFAULT
    }
}

// ---  kotlinx.serialization data classes for the NIP-DRAFT payload ---

@Serializable
private data class NostrPayload(
    @SerialName("version") val version: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("message") val message: String = "",
    @SerialName("priority") val priority: String? = null,
    @SerialName("timestamp") val timestamp: Long? = null,
    @SerialName("topic") val topic: String? = null,
    @SerialName("tags") val tags: List<String>? = null,
    @SerialName("click") val click: String? = null,
    @SerialName("icon") val icon: String? = null,
    @SerialName("actions") val actions: List<NostrAction>? = null,
)

@Serializable
private data class NostrAction(
    @SerialName("label") val label: String,
    @SerialName("url") val url: String? = null,
    @SerialName("method") val method: String? = null, // "view", "http"
) {
    fun toAction(): Action? {
        val action = when (method?.lowercase()) {
            "http" -> "http"
            else -> "view"
        }
        return Action(
            id = label.hashCode().toString(),
            action = action,
            label = label,
            clear = null,
            url = url,
            method = if (action == "http") "POST" else null,
            headers = null,
            body = null,
            intent = null,
            extras = null,
            value = null,
            progress = null,
            error = null
        )
    }
}
