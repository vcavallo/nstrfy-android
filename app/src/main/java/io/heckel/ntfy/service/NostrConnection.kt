package io.heckel.ntfy.service

import android.app.AlarmManager
import android.os.Build
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient as QuartzNostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.IRelayClientListener
import com.vitorpamplona.quartz.nip01Core.relay.client.single.IRelayClient
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EventMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.normalizeRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import io.heckel.ntfy.crypto.KeyManager
import io.heckel.ntfy.db.ConnectionState
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.msg.NostrNotificationParser
import io.heckel.ntfy.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.Calendar
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Unique identifier for a nostr relay connection. The SubscriberService creates a new
 * connection whenever any of these fields change (relay set, key, or topic mappings).
 */
data class NostrConnectionId(
    val relayUrls: List<String>,
    val topicsToSubscriptionIds: Map<String, Long>, // topic (or "") -> subscriptionId
    val inboxSubscriptionIds: Set<Long>,             // subscriptionIds with inboxMode=true
    val relaysHash: Int,
    val pubkeyHash: Int
)

/**
 * Maintains a persistent connection to a set of nostr relays and listens for
 * kind 30078 notification events. Replaces WsConnection / JsonConnection.
 *
 * A single instance handles ALL subscriptions: it builds one relay filter per
 * subscription mode (inbox vs. public) and routes parsed events to the correct
 * subscription by matching the decrypted payload's `topic` field.
 *
 * Reconnection is handled by the Quartz client internally; this class also
 * exposes scheduleReconnect() for external alarm-based retries.
 */
class NostrConnection(
    private val connectionId: NostrConnectionId,
    private val repository: Repository,
    private val keyManager: KeyManager,
    private val connectionDetailsListener: (String, ConnectionState, Throwable?, Long) -> Unit,
    private val notificationListener: (Subscription, Notification) -> Unit,
    private val alarmManager: AlarmManager
) : Connection {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val parser = NostrNotificationParser(keyManager)
    private var quartzClient: QuartzNostrClient? = null
    private var errorCount = 0
    private var closed = false

    private val globalId = GLOBAL_ID.incrementAndGet()

    init {
        Log.d(TAG, "(gid=$globalId): New NostrConnection, ${connectionId.relayUrls.size} relays, " +
                "${connectionId.topicsToSubscriptionIds.size} topic subscriptions")
    }

    @Synchronized
    override fun start() {
        if (closed) {
            Log.d(TAG, "(gid=$globalId): Not starting, connection is closed")
            return
        }
        if (!keyManager.hasKey()) {
            Log.w(TAG, "(gid=$globalId): No nostr key configured, skipping connection")
            return
        }

        Log.d(TAG, "(gid=$globalId): Starting nostr relay connection")
        connectionDetailsListener(NOSTR_SENTINEL, ConnectionState.CONNECTING, null, 0L)

        val okHttpClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .build()
        val wsBuilder = BasicOkHttpWebSocket.Builder { okHttpClient }
        val client = QuartzNostrClient(wsBuilder, scope)
        quartzClient = client

        client.subscribe(object : IRelayClientListener {
            override fun onConnected(relay: IRelayClient, pingMillis: Int, compressed: Boolean) {
                Log.i(TAG, "(gid=$globalId): Connected to ${relay.url.url} (ping=${pingMillis}ms)")
                errorCount = 0
                connectionDetailsListener(NOSTR_SENTINEL, ConnectionState.CONNECTED, null, 0L)
            }

            override fun onIncomingMessage(relay: IRelayClient, msgStr: String, msg: Message) {
                if (msg is EventMessage && msg.event.kind == KIND_NSTRFY) {
                    handleEvent(msg.event)
                }
            }

            override fun onDisconnected(relay: IRelayClient) {
                Log.w(TAG, "(gid=$globalId): Disconnected from ${relay.url.url}")
                if (!closed) {
                    connectionDetailsListener(NOSTR_SENTINEL, ConnectionState.CONNECTING, null, 0L)
                }
            }

            override fun onCannotConnect(relay: IRelayClient, errorMessage: String) {
                Log.e(TAG, "(gid=$globalId): Cannot connect to ${relay.url.url}: $errorMessage")
                if (!closed) {
                    onConnectionError(Exception("Cannot connect: $errorMessage"))
                }
            }
        })

        client.connect()
        scope.launch { openSubscriptions(client) }
    }

    @Synchronized
    override fun close() {
        closed = true
        Log.d(TAG, "(gid=$globalId): Closing connection")
        quartzClient?.disconnect()
        quartzClient = null
        scope.cancel()
    }

    @Synchronized
    fun scheduleReconnect(seconds: Int) {
        if (closed) return
        Log.d(TAG, "(gid=$globalId): Scheduling reconnect in ${seconds}s")
        val reconnectTime = Calendar.getInstance()
        reconnectTime.add(Calendar.SECOND, seconds)
        val startOnBackgroundThread = { scope.launch { start() } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, reconnectTime.timeInMillis,
                    RECONNECT_TAG, { startOnBackgroundThread() }, null)
            }
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, reconnectTime.timeInMillis,
                RECONNECT_TAG, { startOnBackgroundThread() }, null)
        }
    }

    // ---

    private suspend fun openSubscriptions(client: QuartzNostrClient) {
        val userPubkey = try { keyManager.getPubKeyHex() } catch (e: Exception) {
            Log.e(TAG, "(gid=$globalId): Cannot get pubkey, skipping subscription: ${e.message}")
            return
        }

        // Build author set: union of all per-subscription allowlists
        // (queried synchronously here; the connection is on IO thread)
        val allAllowedSenders = try {
            repository.getAllAllowedSenders()
        } catch (e: Exception) {
            Log.w(TAG, "(gid=$globalId): Failed to load allowlists: ${e.message}")
            emptyList()
        }

        val hasInboxSubs = connectionId.inboxSubscriptionIds.isNotEmpty()
        val hasPublicSubs = connectionId.topicsToSubscriptionIds.any {
            it.value !in connectionId.inboxSubscriptionIds
        }

        // Inbox filter: events tagged to our pubkey (#p = userPubkey)
        if (hasInboxSubs) {
            val inboxFilter = Filter(
                kinds = listOf(KIND_NSTRFY),
                tags = mapOf("p" to listOf(userPubkey))
            )
            val filterMap = connectionId.relayUrls.associate { url ->
                val normalized = url.normalizeRelayUrl()
                (normalized ?: NormalizedRelayUrl(url)) to listOf(inboxFilter)
            }
            Log.d(TAG, "(gid=$globalId): Opening inbox subscription (#p=$userPubkey)")
            client.openReqSubscription(subId = SUB_INBOX, filters = filterMap)
        }

        // Public filter: events from allowlisted authors (no #p routing required)
        if (hasPublicSubs && allAllowedSenders.isNotEmpty()) {
            val publicFilter = Filter(
                kinds = listOf(KIND_NSTRFY),
                authors = allAllowedSenders
            )
            val filterMap = connectionId.relayUrls.associate { url ->
                val normalized = url.normalizeRelayUrl()
                (normalized ?: NormalizedRelayUrl(url)) to listOf(publicFilter)
            }
            Log.d(TAG, "(gid=$globalId): Opening public subscription (${allAllowedSenders.size} allowed authors)")
            client.openReqSubscription(subId = SUB_PUBLIC, filters = filterMap)
        }
    }

    private fun handleEvent(event: Event) {
        scope.launch {
            val senderPubkey = event.pubKey
            Log.d(TAG, "(gid=$globalId): Received kind 30078 event id=${event.id.take(8)} from $senderPubkey")

            // Find matching subscription(s) via topic routing
            val subscriptions = repository.getSubscriptions()
            val parsed = parser.parse(event, subscriptions) ?: run {
                Log.d(TAG, "(gid=$globalId): Event could not be parsed or routed, discarding")
                return@launch
            }

            // Check sender allowlist for the matched subscription
            if (parsed.subscription.whitelistEnabled) {
                val allowedSenders = repository.getAllowedSenders(parsed.subscription.id)
                if (senderPubkey !in allowedSenders) {
                    Log.d(TAG, "(gid=$globalId): Sender $senderPubkey not in allowlist for " +
                            "subscription '${parsed.subscription.topic}', discarding")
                    return@launch
                }
            }

            notificationListener(parsed.subscription, parsed.notification)
        }
    }

    private fun onConnectionError(t: Throwable) {
        errorCount++
        val retrySeconds = RETRY_SECONDS.getOrNull(errorCount - 1) ?: RETRY_SECONDS.last()
        val nextRetryTime = System.currentTimeMillis() + retrySeconds * 1000L
        val firstErrorTime = if (errorCount == 1) System.currentTimeMillis() else 0L
        connectionDetailsListener(NOSTR_SENTINEL, ConnectionState.CONNECTING, t, nextRetryTime)
        Log.w(TAG, "(gid=$globalId): Connection error #$errorCount, retrying in ${retrySeconds}s: ${t.message}")
        scheduleReconnect(retrySeconds)
    }

    companion object {
        private const val TAG = "NstrfyNostrConn"
        private const val RECONNECT_TAG = "NostrReconnect"
        private const val KIND_NSTRFY = 30078
        private const val SUB_INBOX = "nstrfy-inbox"
        private const val SUB_PUBLIC = "nstrfy-public"
        const val NOSTR_SENTINEL = "nostr://"
        private val RETRY_SECONDS = listOf(5, 10, 15, 20, 30, 45, 60, 120)
        private val GLOBAL_ID = AtomicLong(0)
    }
}
