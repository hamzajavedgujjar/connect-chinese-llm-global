package ai.norwix

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.norwix.data.EncryptedVault
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VaultInstrumentedTest {
    @Test fun keystoreEncryptionRoundTripsAndRejectsTampering() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = EncryptedVault(context)
        val name = "test-vault-record.bin"
        try {
            vault.write(name, "sensitive test text".toByteArray())
            assertEquals("sensitive test text", vault.read(name)!!.decodeToString())
            val file = File(context.noBackupFilesDir, "norwix-vault/$name")
            val encrypted = file.readBytes()
            assertFalse(encrypted.decodeToString().contains("sensitive test text"))
            encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
            file.writeBytes(encrypted)
            assertThrows(Exception::class.java) { vault.read(name) }
        } finally { vault.delete(name) }
    }
}
