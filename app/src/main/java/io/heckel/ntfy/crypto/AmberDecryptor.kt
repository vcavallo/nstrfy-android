package io.heckel.ntfy.crypto

import android.content.ContentResolver
import com.vitorpamplona.quartz.nip55AndroidSigner.api.SignerResult
import com.vitorpamplona.quartz.nip55AndroidSigner.api.background.queries.Nip44DecryptQuery
import com.vitorpamplona.quartz.nip55AndroidSigner.api.background.queries.Nip04DecryptQuery
import io.heckel.ntfy.util.Log

private const val TAG = "NstrfyAmberDecryptor"

/**
 * Decrypts via Amber's ContentProvider (NIP-55 background channel).
 * No user interaction needed after initial "Always allow" permission grant.
 */
class AmberDecryptor(
    pubKeyHex: String,
    packageName: String,
    contentResolver: ContentResolver
) : EventDecryptor {

    private val nip44Query = Nip44DecryptQuery(pubKeyHex, packageName, contentResolver)
    private val nip04Query = Nip04DecryptQuery(pubKeyHex, packageName, contentResolver)

    override suspend fun nip44Decrypt(ciphertext: String, senderPubKeyHex: String): String? {
        return try {
            val result = nip44Query.query(ciphertext, senderPubKeyHex)
            when (result) {
                is SignerResult.RequestAddressed.Successful -> result.result.plaintext
                else -> {
                    Log.d(TAG, "NIP-44 decrypt via Amber: $result")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "NIP-44 decrypt via Amber failed: ${e.message}")
            null
        }
    }

    override suspend fun nip04Decrypt(ciphertext: String, senderPubKeyHex: String): String? {
        return try {
            val result = nip04Query.query(ciphertext, senderPubKeyHex)
            when (result) {
                is SignerResult.RequestAddressed.Successful -> result.result.plaintext
                else -> {
                    Log.d(TAG, "NIP-04 decrypt via Amber: $result")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "NIP-04 decrypt via Amber failed: ${e.message}")
            null
        }
    }

    override fun isAvailable(): Boolean = true
}
