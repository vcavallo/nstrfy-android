package io.heckel.ntfy.crypto

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.vitorpamplona.quartz.nip01Core.crypto.Nip01Crypto
import com.vitorpamplona.quartz.nip19Bech32.bech32.Bech32
import com.vitorpamplona.quartz.utils.Hex

/**
 * Manages the user's nostr identity.
 *
 * Supports two modes:
 * - INTERNAL: private key stored in EncryptedSharedPreferences (Android Keystore backed)
 * - AMBER: only public key + Amber package name stored; decryption delegated to Amber via NIP-55
 */
class KeyManager(context: Context) {

    enum class LoginMode { INTERNAL, AMBER, NONE }

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

    // --- Login mode ---

    fun getLoginMode(): LoginMode = when (prefs.getString(KEY_LOGIN_MODE, null)) {
        "internal" -> LoginMode.INTERNAL
        "amber" -> LoginMode.AMBER
        else -> if (prefs.contains(KEY_PRIVKEY_HEX)) LoginMode.INTERNAL else LoginMode.NONE
    }

    fun hasIdentity(): Boolean = getLoginMode() != LoginMode.NONE

    /** True if a local private key is stored (internal mode). */
    fun hasKey(): Boolean = prefs.contains(KEY_PRIVKEY_HEX)

    // --- Internal key methods ---

    /** Store a private key given as either nsec (bech32) or raw hex. Sets mode to INTERNAL. */
    fun storeKey(nsecOrHex: String) {
        val hexKey = when {
            nsecOrHex.startsWith("nsec1") -> decodeNsec(nsecOrHex)
            Hex.isHex64(nsecOrHex) -> nsecOrHex.lowercase()
            else -> throw IllegalArgumentException("Invalid key: must be nsec1... or 64-char hex")
        }
        prefs.edit()
            .putString(KEY_PRIVKEY_HEX, hexKey)
            .putString(KEY_LOGIN_MODE, "internal")
            .remove(KEY_AMBER_PUBKEY)
            .remove(KEY_AMBER_PACKAGE)
            .apply()
    }

    /** Generate a fresh random keypair and store it. Sets mode to INTERNAL. */
    fun generateKey() {
        val privKeyBytes = Nip01Crypto.privKeyCreate()
        prefs.edit()
            .putString(KEY_PRIVKEY_HEX, Hex.encode(privKeyBytes))
            .putString(KEY_LOGIN_MODE, "internal")
            .remove(KEY_AMBER_PUBKEY)
            .remove(KEY_AMBER_PACKAGE)
            .apply()
    }

    /** Raw 32-byte private key. Throws if no internal key stored. */
    fun getPrivKeyBytes(): ByteArray {
        val hex = prefs.getString(KEY_PRIVKEY_HEX, null)
            ?: throw IllegalStateException("No private key stored")
        return Hex.decode(hex)
    }

    /** Hex-encoded private key. Throws if no internal key stored. */
    fun getPrivKeyHex(): String {
        return prefs.getString(KEY_PRIVKEY_HEX, null)
            ?: throw IllegalStateException("No private key stored")
    }

    /** bech32-encoded private key (nsec1...) — use sparingly, only for key export. */
    fun exportNsec(): String =
        Bech32.encodeBytes("nsec", getPrivKeyBytes(), Bech32.Encoding.Bech32)

    // --- Amber methods ---

    /** Store Amber login state. Clears any internal private key. */
    fun storeAmberLogin(pubkeyRaw: String, packageName: String) {
        // Amber may return npub bech32 or hex — normalize to hex
        val hexKey = if (pubkeyRaw.startsWith("npub1")) {
            val decoded = Bech32.decodeBytes(pubkeyRaw)
            require(decoded.first == "npub") { "Expected npub prefix" }
            Hex.encode(decoded.second)
        } else {
            pubkeyRaw.lowercase()
        }
        prefs.edit()
            .putString(KEY_AMBER_PUBKEY, hexKey)
            .putString(KEY_AMBER_PACKAGE, packageName)
            .putString(KEY_LOGIN_MODE, "amber")
            .remove(KEY_PRIVKEY_HEX)
            .apply()
    }

    fun getAmberPubKeyHex(): String = prefs.getString(KEY_AMBER_PUBKEY, null)
        ?: throw IllegalStateException("No Amber pubkey stored")

    fun getAmberPackageName(): String = prefs.getString(KEY_AMBER_PACKAGE, null)
        ?: throw IllegalStateException("No Amber package name stored")

    // --- Public key (works in both modes) ---

    /** Hex-encoded public key. Works in INTERNAL and AMBER modes. */
    fun getPubKeyHex(): String = when (getLoginMode()) {
        LoginMode.INTERNAL -> Hex.encode(Nip01Crypto.pubKeyCreate(getPrivKeyBytes()))
        LoginMode.AMBER -> getAmberPubKeyHex()
        LoginMode.NONE -> throw IllegalStateException("No identity configured")
    }

    /** Raw 32-byte public key. Works in INTERNAL and AMBER modes. */
    fun getPubKeyBytes(): ByteArray = Hex.decode(getPubKeyHex())

    /** bech32-encoded public key (npub1...) for display/sharing. */
    fun getPubKeyNpub(): String =
        Bech32.encodeBytes("npub", getPubKeyBytes(), Bech32.Encoding.Bech32)

    // --- Logout ---

    /** Clear all identity state (both internal and Amber). */
    fun logout() {
        prefs.edit()
            .remove(KEY_PRIVKEY_HEX)
            .remove(KEY_AMBER_PUBKEY)
            .remove(KEY_AMBER_PACKAGE)
            .remove(KEY_LOGIN_MODE)
            .apply()
    }

    fun deleteKey() = logout()

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
        private const val KEY_LOGIN_MODE = "login_mode"
        private const val KEY_AMBER_PUBKEY = "amber_pubkey_hex"
        private const val KEY_AMBER_PACKAGE = "amber_package_name"
    }
}
