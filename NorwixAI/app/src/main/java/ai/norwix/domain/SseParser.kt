package ai.norwix.domain

import java.io.BufferedReader

/** SSE data fields are joined with a newline; comments and heartbeat frames are ignored. */
object SseParser {
    suspend fun consume(reader: BufferedReader, cancelled: () -> Boolean, onData: suspend (String) -> Unit) {
        val lines = mutableListOf<String>()
        suspend fun dispatch() {
            if (lines.isNotEmpty()) onData(lines.joinToString("\n"))
            lines.clear()
        }
        while (!cancelled()) {
            val line = reader.readLine() ?: break
            require(line.length <= 2_000_000) { "Streaming event is too large." }
            if (line.isEmpty()) dispatch()
            else if (line.startsWith("data:")) {
                lines += line.substring(5).removePrefix(" ")
                require(lines.sumOf { it.length } <= 2_000_000) { "Streaming event is too large." }
            }
        }
        if (!cancelled()) dispatch()
    }
}
