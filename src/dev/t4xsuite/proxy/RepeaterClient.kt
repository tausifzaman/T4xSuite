package dev.t4xsuite.proxy

import java.io.ByteArrayOutputStream
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** User-triggered direct HTTP(S), independent of browser/proxy/interception state. */
object RepeaterClient {
    const val MAX_RESPONSE = 2 * 1024 * 1024
    private const val CONNECT_TIMEOUT = 8_000
    private const val READ_TIMEOUT = 30_000

    fun newCall(raw: String): Call = Call(raw)
    fun send(raw: String): String = newCall(raw).execute()

    /** One socket per explicit send, with cancellation during connect/TLS/read/write. */
    class Call internal constructor(private val raw: String) {
        private val started = AtomicBoolean(false)
        private val cancelled = AtomicBoolean(false)
        private val activeSocket = AtomicReference<Socket?>()
        val isCancelled: Boolean get() = cancelled.get()

        fun cancel() {
            cancelled.set(true)
            runCatching { activeSocket.get()?.close() }
        }

        fun execute(): String {
            check(started.compareAndSet(false, true)) { "Create a new call for each send." }
            checkCancelled()
            require(raw.all { it.code <= 255 }) { "Encode non-Latin request text before sending." }
            val request = PlainHttpRequest.parse(raw.toByteArray(Charsets.ISO_8859_1), null)
            require(request != null && request.method != "CONNECT") { "Invalid HTTP(S) request" }
            val uri = URI(request.target)
            val host = uri.host.removeSurrounding("[", "]")
            val port = if (uri.port > 0) uri.port else if (request.scheme == "https") 443 else 80
            val path = uri.rawPath.orEmpty().ifEmpty { "/" } + uri.rawQuery?.let { "?$it" }.orEmpty()
            val wire = "${request.method} $path HTTP/1.1" + withClose(raw).substring(raw.indexOf("\r\n"))
            return Socket(Proxy.NO_PROXY).use { socket ->
                activeSocket.set(socket)
                try {
                    checkCancelled()
                    socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT)
                    socket.soTimeout = READ_TIMEOUT
                    checkCancelled()
                    if (request.scheme == "https") {
                        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                        (factory.createSocket(socket, host, port, true) as SSLSocket).use { tls ->
                            tls.soTimeout = READ_TIMEOUT
                            tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                            tls.startHandshake()
                            checkCancelled()
                            exchange(tls, wire, request.method)
                        }
                    } else {
                        exchange(socket, wire, request.method)
                    }
                } finally {
                    activeSocket.compareAndSet(socket, null)
                }
            }
        }

        private fun checkCancelled() {
            if (cancelled.get()) throw InterruptedIOException("Request cancelled.")
        }
    }

    private fun withClose(raw: String): String {
        val split = raw.indexOf("\r\n\r\n")
        val lines = raw.substring(0, split).split("\r\n").filterNot {
            it.substringBefore(':').equals("connection", true) || it.substringBefore(':').equals("proxy-connection", true)
        }
        return (lines + "Connection: close").joinToString("\r\n") + "\r\n\r\n" + raw.substring(split + 4)
    }

    private fun exchange(socket: Socket, raw: String, method: String): String {
        socket.getOutputStream().apply { write(raw.toByteArray(Charsets.ISO_8859_1)); flush() }
        return ResponseReader(BufferedInputStream(socket.getInputStream())).read(method)
    }

    /** Keep raw status/headers/body, but finish on HTTP framing rather than waiting for EOF. */
    private class ResponseReader(private val input: InputStream) {
        private val out = ByteArrayOutputStream()
        private val buffer = ByteArray(8192)

        fun read(method: String): String {
            try {
                var interim = 0
                while (true) {
                    val head = header()
                    val lines = head.removeSuffix("\r\n\r\n").split("\r\n")
                    require(Regex("HTTP/1\\.[01] [1-5][0-9]{2}(?: [\\x20-\\x7E]*)?").matches(lines.first())) { "Invalid response status line." }
                    val status = lines.first().substring(9, 12).toInt()
                    val fields = lines.drop(1).map { line ->
                        val colon = line.indexOf(':')
                        require(colon > 0 && Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+").matches(line.substring(0, colon)) &&
                            line.substring(colon + 1).none { (it.code < 0x20 && it != '\t') || it.code == 0x7f }) { "Invalid response header." }
                        line.substring(0, colon).lowercase(Locale.ROOT) to line.substring(colon + 1).trim()
                    }
                    if (status in 100..199 && status != 101) {
                        require(++interim <= 8) { "Too many informational responses." }
                        continue
                    }
                    if (method == "HEAD" || status in setOf(101, 204, 304)) return result()
                    val transfer = fields.filter { it.first == "transfer-encoding" }.flatMap { it.second.split(',') }
                        .map { it.trim().lowercase(Locale.ROOT) }
                    val lengths = fields.filter { it.first == "content-length" }.map { it.second }
                    require(lengths.size <= 1 && !(transfer.isNotEmpty() && lengths.isNotEmpty())) { "Ambiguous response framing." }
                    when {
                        transfer.lastOrNull() == "chunked" -> chunks()
                        transfer.isNotEmpty() -> untilClose()
                        lengths.isNotEmpty() -> {
                            require(lengths.single().matches(Regex("[0-9]+"))) { "Invalid response Content-Length." }
                            val length = lengths.single().toLongOrNull()
                            require(length != null && length >= 0) { "Invalid response Content-Length." }
                            exactly(length)
                        }
                        else -> untilClose()
                    }
                    return result()
                }
            } catch (_: CaptureLimit) {
                return result() + "\n[Response capture truncated at 2 MiB]"
            }
        }

        private fun result(): String = out.toString(Charsets.ISO_8859_1.name())

        private fun byte(): Int {
            val next = input.read()
            if (next < 0) throw EOFException("Response ended before its declared length.")
            if (out.size() >= MAX_RESPONSE) throw CaptureLimit()
            out.write(next)
            return next
        }

        private fun line(): String {
            val text = ByteArrayOutputStream()
            while (text.size() < PlainHttpRequest.MAX_HEAD) {
                val next = byte()
                if (next == 13) {
                    require(byte() == 10) { "Response requires CRLF line endings." }
                    return text.toString(Charsets.ISO_8859_1.name())
                }
                require(next != 10) { "Response requires CRLF line endings." }
                text.write(next)
            }
            throw IllegalArgumentException("Response line exceeds 32 KiB.")
        }

        private fun header(): String {
            val start = out.size()
            val text = StringBuilder()
            while (true) {
                val next = line()
                require(out.size() - start <= PlainHttpRequest.MAX_HEAD) { "Response headers exceed 32 KiB." }
                text.append(next).append("\r\n")
                if (next.isEmpty()) return text.toString()
            }
        }

        private fun exactly(length: Long) {
            var left = length
            while (left > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), left, (MAX_RESPONSE - out.size() + 1).toLong()).toInt())
                if (count < 0) throw EOFException("Response ended before its declared length.")
                val keep = minOf(count, MAX_RESPONSE - out.size())
                out.write(buffer, 0, keep)
                if (keep < count) throw CaptureLimit()
                left -= count
            }
        }

        private fun untilClose() {
            while (true) {
                val count = input.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE - out.size() + 1))
                if (count < 0) return
                val keep = minOf(count, MAX_RESPONSE - out.size())
                out.write(buffer, 0, keep)
                if (keep < count) throw CaptureLimit()
            }
        }

        private fun chunks() {
            while (true) {
                val value = line().substringBefore(';').trim()
                require(value.matches(Regex("[0-9A-Fa-f]+"))) { "Invalid chunk size." }
                val size = value.toLongOrNull(16)
                require(size != null && size >= 0) { "Invalid chunk size." }
                if (size == 0L) {
                    val start = out.size()
                    while (true) {
                        val trailer = line()
                        require(out.size() - start <= PlainHttpRequest.MAX_HEAD) { "Response trailers exceed 32 KiB." }
                        if (trailer.isEmpty()) return
                        require(trailer.indexOf(':') > 0) { "Invalid response trailer." }
                    }
                }
                exactly(size)
                require(byte() == 13 && byte() == 10) { "Invalid chunk terminator." }
            }
        }
    }

    private class CaptureLimit : java.io.IOException()
}
