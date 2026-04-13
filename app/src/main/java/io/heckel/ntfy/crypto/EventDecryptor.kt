package io.heckel.ntfy.crypto

import com.vitorpamplona.quartz.nip04Dm.crypto.Nip04
import com.vitorpamplona.quartz.nip44Encryption.Nip44v2
import com.vitorpamplona.quartz.utils.Hex
import io.heckel.ntfy.util.Log

/**
 * Abstracts NIP-44/NIP-04 decryption so the parser doesn't care
 * whether keys are local or in an external signer (Amber).
 */
interface EventDecryptor {
    suspend fun nip44Decrypt(ciphertext: String, senderPubKeyHex: String): String?
    suspend fun nip04Decrypt(ciphertext: String, senderPubKeyHex: String): String?
    fun isAvailable(): Boolean
}

/**
 * Decrypts using a locally stored private key via KeyManager.
 */
class LocalKeyDecryptor(private val keyManager: KeyManager) : EventDecryptor {
    private val nip44 = Nip44v2()

    override suspend fun nip44Decrypt(ciphertext: String, senderPubKeyHex: String): String? {
        val privKey = try { keyManager.getPrivKeyBytes() } catch (e: Exception) { return null }
        val pubKeyBytes = try { Hex.decode(senderPubKeyHex) } catch (e: Exception) { return null }
        return runCatching { nip44.decrypt(ciphertext, privKey, pubKeyBytes) }.getOrNull()
    }

    override suspend fun nip04Decrypt(ciphertext: String, senderPubKeyHex: String): String? {
        val privKey = try { keyManager.getPrivKeyBytes() } catch (e: Exception) { return null }
        val pubKeyBytes = try { Hex.decode(senderPubKeyHex) } catch (e: Exception) { return null }
        return runCatching { Nip04.decrypt(ciphertext, privKey, pubKeyBytes) }.getOrNull()
    }

    override fun isAvailable(): Boolean = keyManager.hasKey()
}
