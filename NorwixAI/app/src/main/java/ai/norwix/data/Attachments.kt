package ai.norwix.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import ai.norwix.domain.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class Attachments(private val resolver: ContentResolver, private val vault: Vault) {
    companion object {
        const val MAX_FILE_BYTES = 10 * 1024 * 1024
        const val MAX_REQUEST_BYTES = 20 * 1024 * 1024
    }

    suspend fun import(uri: Uri): Attachment = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "Choose a file using the Android document picker." }
        var name = "Attachment"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) name = cursor.getString(index).orEmpty().take(180).ifBlank { "Attachment" }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    require(cursor.getLong(sizeIndex) <= MAX_FILE_BYTES) { "Each attachment must be 10 MB or smaller." }
                }
            }
        }
        val bytes = requireNotNull(resolver.openInputStream(uri)) { "The selected file is unavailable." }.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_FILE_BYTES) { "Each attachment must be 10 MB or smaller." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(bytes.isNotEmpty()) { "This file is empty." }
        val attachment = Attachment(name = name.replace(Regex("[\\r\\n\\u0000]"), " "),
            mime = resolver.getType(uri) ?: "application/octet-stream", size = bytes.size.toLong())
        vault.write("attachment-${attachment.id}.bin", bytes)
        attachment
    }

    fun bytes(attachment: Attachment): ByteArray = requireNotNull(vault.read("attachment-${attachment.id}.bin")) {
        "An attachment is no longer available. Remove it and select it again."
    }
}
