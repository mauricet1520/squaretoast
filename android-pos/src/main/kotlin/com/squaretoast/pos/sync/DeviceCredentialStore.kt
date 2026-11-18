package com.squaretoast.pos.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Holds the device's cloud token, AES-GCM encrypted under a non-exportable Android Keystore key, in no-backup
 * storage (also excluded by the backup rules). The token is never logged.
 */
class DeviceCredentialStore(context: Context) {

    private val file = File(context.noBackupFilesDir, "device.cred")

    @Volatile
    private var cached: String? = null

    fun token(): String? = cached ?: read()?.also { cached = it }

    fun save(token: String) {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        file.writeBytes(byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + ct)
        cached = token
    }

    fun clear() {
        file.delete()
        cached = null
    }

    private fun read(): String? {
        if (!file.exists()) return null
        return runCatching {
            val bytes = file.readBytes()
            val ivLen = bytes[0].toInt()
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 1, ivLen))
            String(cipher.doFinal(bytes, 1 + ivLen, bytes.size - 1 - ivLen), Charsets.UTF_8)
        }.getOrNull() // an unreadable file (key lost after a restore) means: enroll again
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    private companion object {
        const val ALIAS = "squaretoast.device-token.v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
