package ai.norwix.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface Vault {
    fun read(name: String): ByteArray?
    fun write(name: String, bytes: ByteArray)
    fun delete(name: String)
    fun names(): List<String>
    fun erase()
}

/** Every record is independently authenticated and atomically replaced. No plaintext files. */
class EncryptedVault(context: Context) : Vault {
    private val root = File(context.noBackupFilesDir, "norwix-vault")
    private val alias = "${context.packageName}.vault.v1"
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    private fun key(): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build())
            generateKey()
        }
    }

    private fun file(name: String): File {
        require(name.matches(Regex("[a-zA-Z0-9_.-]{1,100}")) && name != "." && name != "..")
        check(root.exists() || root.mkdirs()) { "Cannot access protected storage." }
        return File(root, name)
    }

    override fun read(name: String): ByteArray? {
        val atomic = AtomicFile(file(name))
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return null
        val packet = atomic.openRead().use { it.readBytes() }
        require(packet.size >= 29 && packet[0] == 1.toByte()) { "Unsupported encrypted record." }
        // Never create a replacement key for existing data if the original key has gone missing.
        val existingKey = keyStore.getKey(alias, null) as? SecretKey
            ?: error("The device encryption key is unavailable. Stored data was not changed.")
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, existingKey, GCMParameterSpec(128, packet.copyOfRange(1, 13)))
            updateAAD(name.toByteArray(Charsets.UTF_8))
            doFinal(packet, 13, packet.size - 13)
        }
    }

    override fun write(name: String, bytes: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key())
            updateAAD(name.toByteArray(Charsets.UTF_8))
        }
        check(cipher.iv.size == 12)
        val packet = byteArrayOf(1) + cipher.iv + cipher.doFinal(bytes)
        val atomic = AtomicFile(file(name))
        val stream = atomic.startWrite()
        try {
            stream.write(packet)
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

    override fun delete(name: String) = AtomicFile(file(name)).delete()
    override fun names(): List<String> = root.listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty()
    override fun erase() {
        check(!root.exists() || root.deleteRecursively()) { "Some encrypted files could not be removed." }
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }
}
