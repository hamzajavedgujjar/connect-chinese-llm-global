package ai.norwix.network

import ai.norwix.domain.OAuthPrimitives
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.io.InputStream

/** RFC 8252 loopback receiver. Bind only IPv4 loopback; never wildcard/LAN. */
class LoopbackCallback : AutoCloseable {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1000 }
    val redirectUri = "http://127.0.0.1:${server.localPort}/auth/callback"

    suspend fun await(expectedState: String): Map<String, String> = withContext(Dispatchers.IO) {
        val deadline = System.nanoTime() + 300_000_000_000L
        while (System.nanoTime() < deadline) {
            currentCoroutineContext().ensureActive()
            val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
            socket.use { connection ->
                connection.soTimeout = 1500
                val query = runCatching {
                    val input = connection.getInputStream()
                    val requestLine = readRequestLine(input, deadline)
                    val parts = requestLine.split(' ')
                    require(parts.size == 3 && parts[0] == "GET")
                    val target = URI(parts[1])
                    require(!target.isAbsolute && target.path == "/auth/callback")
                    var headerBytes = 0
                    while (true) {
                        val header = readRequestLine(input, deadline)
                        if (header.isEmpty()) break
                        headerBytes += header.length
                        require(headerBytes <= 16_384)
                    }
                    OAuthPrimitives.query(target.rawQuery.orEmpty())
                }.getOrNull()
                val matched = query != null && OAuthPrimitives.same(query["state"].orEmpty(), expectedState)
                val body = if (matched) "Norwix AI received your sign-in result. Close this tab to return to the app."
                    else "This callback could not be verified. Return to Norwix AI and start sign-in again."
                runCatching {
                    val status = if (matched) "200 OK" else "400 Bad Request"
                    val headers = "HTTP/1.1 $status\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                        "Cache-Control: no-store\r\nContent-Security-Policy: default-src 'none'\r\n" +
                        "Connection: close\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n"
                    connection.getOutputStream().write((headers + body).toByteArray())
                }
                if (matched) return@withContext checkNotNull(query)
            }
        }
        throw UserFacingException("Sign-in timed out after five minutes. Start a new sign-in attempt.")
    }

    private fun readRequestLine(input: InputStream, deadline: Long): String {
        val text = StringBuilder()
        while (text.length <= 8192 && System.nanoTime() < deadline) {
            val byte = input.read()
            if (byte < 0 || byte == 10) return text.toString()
            if (byte != 13) text.append(byte.toChar())
        }
        throw IllegalArgumentException("Oversized or timed-out callback request.")
    }

    override fun close() = server.close()
}
