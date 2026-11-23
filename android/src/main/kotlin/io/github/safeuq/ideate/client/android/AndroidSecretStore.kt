package io.github.safeuq.ideate.client.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import io.github.safeuq.ideate.client.ssh.SecretStore
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * SSH secrets (keys, passwords) encrypted with AES-GCM under a key that lives in the Android
 * Keystore and never leaves it; the encrypted values sit in the app's private preferences as base64
 * of `iv + ciphertext`.
 */
class AndroidSecretStore(context: Context) : SecretStore {
  private val preferences = context.getSharedPreferences("ssh-secrets", Context.MODE_PRIVATE)

  override fun read(name: String): String? {
    val stored = preferences.getString(name, null) ?: return null
    val bytes = Base64.getDecoder().decode(stored)
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
    return cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE).toString(Charsets.UTF_8)
  }

  override fun write(name: String, value: String) {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key())
    val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
    check(cipher.iv.size == IV_SIZE) { "Unexpected GCM IV size ${cipher.iv.size}" }
    preferences.edit { putString(name, Base64.getEncoder().encodeToString(encrypted)) }
  }

  override fun delete(name: String) = preferences.edit { remove(name) }

  private fun key(): SecretKey {
    val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    (keyStore.getKey(ALIAS, null) as? SecretKey)?.let {
      return it
    }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
    generator.init(
      KeyGenParameterSpec.Builder(
          ALIAS,
          KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build(),
    )
    return generator.generateKey()
  }

  private companion object {
    const val KEYSTORE = "AndroidKeyStore"
    const val ALIAS = "ideate-ssh-secrets"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val IV_SIZE = 12
  }
}
