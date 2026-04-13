package io.heckel.ntfy.crypto

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.vitorpamplona.quartz.nip01Core.crypto.Nip01Crypto
import com.vitorpamplona.quartz.nip19Bech32.bech32.Bech32
import com.vitorpamplona.quartz.utils.Hex

/**
 * Manages the user's nostr keypair.
 *
 * The private key is stored as hex in EncryptedSharedPreferences (backed by Android Keystore).
 * The public key is always derived — never stored separately.
 */
class KeyManager(context: Context) {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun hasKey(): Boolean = prefs.contains(KEY_PRIVKEY_HEX)

    /** Store a private key given as either nsec (bech32) or raw hex. */
    fun storeKey(nsecOrHex: String) {
        val hexKey = when {
            nsecOrHex.startsWith("nsec1") -> decodeNsec(nsecOrHex)
            Hex.isHex64(nsecOrHex) -> nsecOrHex.lowercase()
            else -> throw IllegalArgumentException("Invalid key: must be nsec1... or 64-char hex")
        }
        prefs.edit().putString(KEY_PRIVKEY_HEX, hexKey).apply()
    }

    /** Generate a fresh random keypair and store it. */
    fun generateKey() {
        val privKeyBytes = Nip01Crypto.privKeyCreate()
        prefs.edit().putString(KEY_PRIVKEY_HEX, Hex.encode(privKeyBytes)).apply()
    }

    /** Raw 32-byte private key. Throws if no key stored. */
    fun getPrivKeyBytes(): ByteArray {
        val hex = prefs.getString(KEY_PRIVKEY_HEX, null)
            ?: throw IllegalStateException("No private key stored")
        return Hex.decode(hex)
    }

    /** Hex-encoded private key. Throws if no key stored. */
    fun getPrivKeyHex(): String {
        return prefs.getString(KEY_PRIVKEY_HEX, null)
            ?: throw IllegalStateException("No private key stored")
    }

    /** Raw 32-byte public key derived from the stored private key. */
    fun getPubKeyBytes(): ByteArray = Nip01Crypto.pubKeyCreate(getPrivKeyBytes())

    /** Hex-encoded public key (the nostr author identifier used in relay filters). */
    fun getPubKeyHex(): String = Hex.encode(getPubKeyBytes())

    /** bech32-encoded public key (npub1...) for display/sharing. */
    fun getPubKeyNpub(): String =
        Bech32.encodeBytes("npub", getPubKeyBytes(), Bech32.Encoding.Bech32)

    /** bech32-encoded private key (nsec1...) — use sparingly, only for key export. */
    fun exportNsec(): String =
        Bech32.encodeBytes("nsec", getPrivKeyBytes(), Bech32.Encoding.Bech32)

    fun deleteKey() {
        prefs.edit().remove(KEY_PRIVKEY_HEX).apply()
    }

    // ---

    private fun decodeNsec(nsec: String): String {
        val result = Bech32.decodeBytes(nsec)
        val prefix = result.first
        val bytes = result.second
        require(prefix == "nsec") { "Expected nsec prefix, got: $prefix" }
        require(bytes.size == 32) { "Expected 32-byte key, got: ${bytes.size}" }
        return Hex.encode(bytes)
    }

    companion object {
        private const val PREFS_FILE = "nstrfy_keys"
        private const val KEY_PRIVKEY_HEX = "privkey_hex"
    }
}
