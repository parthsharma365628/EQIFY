package com.liteplane.eqify

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Reads a user-picked document as strict UTF-8 with a hard size cap, enforced
 * while streaming (providers may omit or misreport size). Call off the main thread.
 */
object BoundedTextReader {
    data class Document(val displayName: String?, val text: String)

    fun read(context: Context, uri: Uri, maxBytes: Int, limitLabel: String): Document {
        val resolver = context.contentResolver
        val displayName = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        val bytes = ByteArrayOutputStream()
        resolver.openInputStream(uri)?.use { input ->
            val chunk = ByteArray(8192)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                if (count > 0) bytes.write(chunk, 0, count)
                require(bytes.size() <= maxBytes) { "File exceeds $limitLabel limit." }
            }
        } ?: error("Could not open selected file.")
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        return Document(displayName, text)
    }
}
