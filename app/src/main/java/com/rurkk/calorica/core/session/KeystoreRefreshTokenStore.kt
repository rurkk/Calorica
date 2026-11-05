package com.rurkk.calorica.core.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only encrypted refresh bytes are persisted, outside all Android backup locations. */
@Singleton
class KeystoreRefreshTokenStore @Inject constructor(@ApplicationContext private val context: Context) : RefreshTokenStore {
  private val alias = "${context.packageName}.refresh.v1"
  private val aad = alias.toByteArray(Charsets.UTF_8)
  private val file get() = AtomicFile(File(context.noBackupFilesDir, "refresh-session.v1"))
  private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
  private fun key(create: Boolean): SecretKey? {
    val existing = keyStore().getKey(alias, null) as? SecretKey
    if (existing != null || !create) return existing
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
      init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
          .setRandomizedEncryptionRequired(true).build())
    }.generateKey()
  }
  override suspend fun read(): String? = withContext(Dispatchers.IO) {
    if (!file.baseFile.exists()) return@withContext null
    val bytes = file.openRead().use { stream ->
      val value = stream.readBytes()
      require(value.size in 30..8192) { "Invalid encrypted session" }
      value
    }
    require(bytes[0].toInt() == 1 && bytes[1].toInt() == 12) { "Invalid encrypted session" }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, requireNotNull(key(false)), GCMParameterSpec(128, bytes.copyOfRange(2, 14)))
    cipher.updateAAD(aad)
    cipher.doFinal(bytes.copyOfRange(14, bytes.size)).toString(Charsets.UTF_8).also { require(it.isNotBlank()) }
  }
  override suspend fun write(token: String) = withContext(Dispatchers.IO) {
    require(token.isNotBlank() && token.length <= 4096)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, requireNotNull(key(true)))
    cipher.updateAAD(aad)
    val bytes = byteArrayOf(1, 12) + cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
    val atomic = file
    val stream = atomic.startWrite()
    try { stream.write(bytes); atomic.finishWrite(stream) }
    catch (error: Exception) { atomic.failWrite(stream); throw error }
  }
  override suspend fun clear() = withContext(Dispatchers.IO) {
    try { keyStore().deleteEntry(alias) } finally { file.delete() }
  }
}
