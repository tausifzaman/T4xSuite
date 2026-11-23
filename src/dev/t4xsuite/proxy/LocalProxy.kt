package dev.t4xsuite.proxy

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** Explicit browser-only local HTTP proxy; HTTPS decryption is disabled unless configured with a generated CA. */
class LocalProxy(private val listener: Listener) {
    interface Listener {
        fun changed()
        fun waiting(request: InterceptedRequest)
        fun waiting(response: InterceptedResponse) = Unit
    }

    private val serial = AtomicLong()
    private val runningFlag = AtomicReference(false)
    private val historyLock = Any()
    private val history = ArrayDeque<ProxyExchange>()
    private val pending = ArrayBlockingQueue<InterceptedRequest>(16)
    private val pendingResponses = ArrayBlockingQueue<InterceptedResponse>(16)
    private val clients = java.util.Collections.synchronizedSet(mutableSetOf<Socket>())
    private val sockets = java.util.Collections.synchronizedSet(mutableSetOf<Socket>())
    @Volatile private var io = Executors.newFixedThreadPool(12, daemon("t4xsuite-io"))
    @Volatile private var acceptor = Executors.newSingleThreadExecutor(daemon("t4xsuite-listener"))
    @Volatile private var server: ServerSocket? = null
    @Volatile var interceptHttp: Boolean = false
        private set
    @Volatile var interceptResponses: Boolean = false
        private set
    @Volatile var tlsInspection: Boolean = false
        private set
    @Volatile private var caIdentity: CertificateAuthorityCore.Identity? = null
    @Volatile var port: Int = 0
        private set
    val running: Boolean get() = runningFlag.get()

    fun start() {
        if (running) return
        if (io.isShutdown) io = Executors.newFixedThreadPool(12, daemon("t4xsuite-io"))
        if (acceptor.isShutdown) acceptor = Executors.newSingleThreadExecutor(daemon("t4xsuite-listener"))
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress("127.0.0.1", 0), 32)
        server = socket
        port = socket.localPort
        runningFlag.set(true)
        synchronized(historyLock) { history.clear() }
        acceptor.submit {
            while (running) {
                try {
                    val client = socket.accept()
                    if (!running || clients.size >= 24) { client.close(); continue }
                    clients.add(client)
                    sockets.add(client)
                    io.submit { try { handle(client) } finally { clients.remove(client); sockets.remove(client); runCatching { client.close() } } }
                } catch (_: Exception) {
                    if (running) listener.changed()
                }
            }
        }
        listener.changed()
    }

    fun stop() {
        if (!runningFlag.getAndSet(false)) return
        server?.let { runCatching { it.close() } }
        server = null
        port = 0
        pending.toList().forEach { it.cancel() }
        pending.clear()
        pendingResponses.toList().forEach { it.cancel() }
        pendingResponses.clear()
        sockets.toList().forEach { runCatching { it.close() } }
        clients.toList().forEach { runCatching { it.close() } }
        clients.clear()
        sockets.clear()
        acceptor.shutdownNow()
        io.shutdownNow()
        interceptHttp = false
        interceptResponses = false
        tlsInspection = false
        caIdentity = null
        listener.changed()
    }

    fun setInterceptResponses(enabled: Boolean) {
        interceptResponses = enabled
        if (!enabled) pendingResponses.toList().forEach { response ->
            pendingResponses.remove(response)
            response.continueOriginal()
        }
        listener.changed()
    }

    fun setTlsInspection(enabled: Boolean, ca: CertificateAuthorityCore.Identity? = caIdentity) {
        if (enabled && ca == null) throw IllegalStateException("A generated CA is required")
        caIdentity = ca
        tlsInspection = enabled
        listener.changed()
    }


    fun setIntercept(enabled: Boolean) {
        interceptHttp = enabled
        if (!enabled) pending.toList().forEach { request ->
            pending.remove(request)
            request.continueOriginal()
        }
        listener.changed()
    }

    fun history(): List<ProxyExchange> = synchronized(historyLock) { history.toList() }
    fun pending(): List<InterceptedRequest> = pending.toList().filter { it.isPending }
    fun pendingResponses(): List<InterceptedResponse> = pendingResponses.toList().filter { it.isPending }

    fun clearHistory() {
        synchronized(historyLock) { history.clear() }
        listener.changed()
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 15_000
            val input = client.getInputStream()
            val first = readHeader(input) ?: return
            if (first.size > PlainHttpRequest.MAX_HEAD) { respond(client, 431, "Request headers too large"); return }
            val line = first.toString(StandardCharsets.ISO_8859_1).substringBefore("\r\n")
            if (line.startsWith("CONNECT ")) {
                tunnel(client, first, line)
                return
            }
            val request = readRequest(input, "http", null, prefetched = first) ?: run { respond(client, 400, "Malformed or unsupported HTTP request"); return }
            plain(client, request)
        } catch (_: Exception) {
            runCatching { respond(client, 502, "Proxy connection failed") }
        }
    }

    private fun plain(client: Socket, original: PlainHttpRequest, https: Boolean = false, connected: Socket? = null) {
        val row = ProxyExchange(serial.incrementAndGet(), original.method, original.target, https, now(), ExchangeState.CAPTURED,
            request = original.raw)
        add(row)
        var wire = original.raw
        if (interceptHttp) {
            val paused = InterceptedRequest(row, wire)
            row.state = ExchangeState.PAUSED
            if (!pending.offer(paused)) { row.state = ExchangeState.FAILED; respond(client, 503, "Interception queue full"); listener.changed(); return }
            listener.waiting(paused)
            listener.changed()
            val decision = paused.await()
            pending.remove(paused)
            wire = when (decision) {
                is Decision.Forward -> decision.request
                Decision.Drop -> { respond(client, 451, "Request dropped in T4X-SUITE"); listener.changed(); return }
                Decision.Timeout -> { respond(client, 504, "Request interception timed out"); listener.changed(); return }
            }
        }
        val edited = PlainHttpRequest.parse(wire.toByteArray(StandardCharsets.ISO_8859_1), original.scheme) ?: run {
            row.state = ExchangeState.FAILED; respond(client, 400, "Invalid edited request"); listener.changed(); return
        }
        row.request = edited.raw
        row.method = edited.method
        row.target = edited.target
        row.state = ExchangeState.CAPTURED
        listener.changed()
        val started = System.nanoTime()
        val upstream = connected ?: Socket()
        if (connected == null) sockets.add(upstream)
        try {
            val url = java.net.URI(edited.target)
            val host = url.host ?: throw java.io.IOException("Invalid host")
            if (connected == null) upstream.connect(InetSocketAddress(host, if (url.port > 0) url.port else if (url.scheme.equals("https", true)) 443 else 80), 8_000)
            upstream.soTimeout = 15_000
            upstream.use { remote ->
                val out = remote.getOutputStream()
                val normalized = normalize(edited).toByteArray(StandardCharsets.ISO_8859_1)
                out.write(normalized); out.flush()
                val response = readHeader(remote.getInputStream()) ?: throw EOFException()
                if (response.size > PlainHttpRequest.MAX_HEAD) throw java.io.IOException("Response headers too large")
                row.responseHeaders = response.toString(StandardCharsets.ISO_8859_1).take(PlainHttpRequest.MAX_HEAD)
                row.status = responseStatus(response)
                if (interceptResponses) relayInterceptedResponse(remote, client, response, edited.method, row)
                else relayResponse(remote, client, response, edited.method).also { captured ->
                    if (captured != null) row.responseBody = captured
                    row.responseMime = responseMime(response)
                }
            }
            if (row.state != ExchangeState.DROPPED && row.state != ExchangeState.FAILED) row.state = ExchangeState.FORWARDED
        } catch (_: Exception) {
            row.state = ExchangeState.FAILED
            runCatching { respond(client, 502, "Upstream request failed") }
        } finally {
            runCatching { upstream.close() }
            if (connected == null) sockets.remove(upstream)
            row.elapsedMs = (System.nanoTime() - started) / 1_000_000
            listener.changed()
        }
    }

    private fun tunnel(client: Socket, header: ByteArray, line: String) {
        val connectParts = line.split(' ')
        val authority = connectParts.getOrNull(1)
        val row = ProxyExchange(serial.incrementAndGet(), "CONNECT", authority?.take(255) ?: "(invalid)", true, now(), ExchangeState.CONNECTED)
        add(row)
        val parsed = authority?.let { runCatching { java.net.URI("http://$it") }.getOrNull() }
        val host = parsed?.host
        if (connectParts.size != 3 || connectParts[0] != "CONNECT" || connectParts[2] != "HTTP/1.1" ||
            host == null || parsed.port !in 1..65535 || parsed.rawUserInfo != null ||
            parsed.rawPath.isNotEmpty() || parsed.rawQuery != null || parsed.rawFragment != null ||
            !Regex("(?:[A-Za-z0-9.-]+|\\[[0-9A-Fa-f:.]+]):[0-9]{1,5}").matches(authority.orEmpty()) ||
            header.size > PlainHttpRequest.MAX_HEAD) {
            row.state = ExchangeState.FAILED; respond(client, 400, "Invalid CONNECT target"); listener.changed(); return
        }
        val upstream = Socket()
        sockets.add(upstream)
        try {
            if (tlsInspection) {
                inspectTls(client, upstream, host, parsed.port, row)
                row.status = 200
                if (row.state != ExchangeState.FAILED) row.state = ExchangeState.FORWARDED
                listener.changed()
                return
            }
            upstream.connect(InetSocketAddress(host, parsed.port), 8_000)
            upstream.soTimeout = 0; client.soTimeout = 0
            client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            client.getOutputStream().flush()
            row.status = 200
            val relay = Executors.newFixedThreadPool(2, daemon("t4xsuite-tunnel"))
            val a = relay.submit { copy(client, upstream) }
            val b = relay.submit { copy(upstream, client) }
            runCatching { a.get(5, java.util.concurrent.TimeUnit.MINUTES) }
            runCatching { b.get(5, java.util.concurrent.TimeUnit.MINUTES) }
            relay.shutdownNow()
            row.state = ExchangeState.FORWARDED
        } catch (_: Exception) {
            row.state = ExchangeState.FAILED
            runCatching { respond(client, 502, "HTTPS tunnel unavailable") }
        } finally {
            runCatching { upstream.close() }
            sockets.remove(upstream)
            row.elapsedMs = 0
            listener.changed()
        }
    }

    private fun inspectTls(client: Socket, upstreamRaw: Socket, host: String, port: Int, row: ProxyExchange) {
        val ca = caIdentity ?: throw java.io.IOException("HTTPS inspection CA unavailable")
        var upstreamTls: SSLSocket? = null
        var browserTls: SSLSocket? = null
        try {
            upstreamRaw.connect(InetSocketAddress(host, port), 8_000)
            upstreamTls = (SSLContext.getDefault().socketFactory.createSocket(upstreamRaw, host, port, true) as SSLSocket).apply {
                soTimeout = 15_000
                sslParameters = sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                    if (!host.contains(':')) serverNames = listOf(SNIHostName(host))
                }
                startHandshake()
            }
            val leaf = CertificateAuthorityCore.issueServer(ca, host)
            val keyStore = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry("t4xsuite-host", leaf.pair.private, null, arrayOf(leaf.certificate, ca.certificate))
            }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, null) }
            val serverContext = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
            client.getOutputStream().apply { write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()); flush() }
            browserTls = (serverContext.socketFactory.createSocket(client, host, client.port, false) as SSLSocket).apply {
                useClientMode = false
                soTimeout = 15_000
                startHandshake()
            }
            val request = readRequest(browserTls!!.getInputStream(), "https", host, port)
                ?: throw java.io.IOException("Unsupported TLS request")
            plain(browserTls!!, request, https = true, connected = upstreamTls)
            upstreamTls = null // plain() closes the connected upstream socket.
        } finally {
            runCatching { browserTls?.close() }
            runCatching { upstreamTls?.close() }
            runCatching { upstreamRaw.close() }
            sockets.remove(upstreamRaw)
        }
    }

    private fun readRequest(input: InputStream, scheme: String, tlsHost: String?, tlsPort: Int = 443, prefetched: ByteArray? = null): PlainHttpRequest? {
        val head = prefetched ?: readHeader(input) ?: return null
        if (head.size > PlainHttpRequest.MAX_HEAD) throw java.io.IOException("Request headers too large")
        val rawHead = head.toString(StandardCharsets.ISO_8859_1)
        val names = rawHead.substringAfter("\r\n").substringBefore("\r\n\r\n").lineSequence()
            .filter { ':' in it }.map { it.substringBefore(':').trim().lowercase(Locale.ROOT) }.toList()
        if (names.groupingBy { it }.eachCount().any { (n, c) -> c > 1 && n in setOf("host", "content-length", "transfer-encoding") })
            throw java.io.IOException("Duplicate request framing")
        val headers = headerValue(head)
        if (headers.keys.any { it in setOf("transfer-encoding", "expect", "upgrade", "te", "trailer") })
            throw java.io.IOException("Unsupported request framing")
        val lengthText = headers["content-length"]
        val length = lengthText?.toLongOrNull() ?: if (lengthText == null) 0L else -1L
        if (length !in 0..PlainHttpRequest.MAX_BODY.toLong()) throw java.io.IOException("Request body exceeds edit limit")
        val body = readExact(input, length.toInt()) ?: throw EOFException("Incomplete request")
        val requestRaw = (head + body).toString(StandardCharsets.ISO_8859_1)
        val startLine = requestRaw.substringBefore("\r\n").split(' ')
        if (startLine.size != 3) return null
        val target = if (startLine[1].startsWith("/")) {
            val host = tlsHost ?: headers["host"] ?: return null
            val authority = if (tlsHost != null && tlsPort != 443) "$host:$tlsPort" else host
            "$scheme://$authority${startLine[1]}"
        } else startLine[1]
        val absolute = requestRaw.replaceFirst(startLine[1], target)
        return PlainHttpRequest.parse(absolute.toByteArray(StandardCharsets.ISO_8859_1), scheme)
    }

    private fun normalize(request: PlainHttpRequest): String {
        val split = request.raw.indexOf("\r\n\r\n")
        val lines = request.raw.substring(0, split).split("\r\n")
        val path = java.net.URI(request.target).rawPath.ifEmpty { "/" } +
            java.net.URI(request.target).rawQuery?.let { "?$it" }.orEmpty()
        val start = "${request.method} $path HTTP/1.1"
        val kept = lines.drop(1).filterNot { it.substringBefore(':').equals("proxy-connection", true) || it.substringBefore(':').equals("connection", true) }
        return (listOf(start) + kept + "Connection: close" + "" + request.raw.substring(split + 4)).joinToString("\r\n")
    }

    private fun readHeader(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        var matched = 0
        while (out.size() <= PlainHttpRequest.MAX_HEAD) {
            val value = input.read()
            if (value < 0) return if (out.size() == 0) null else null
            out.write(value)
            matched = when {
                matched == 0 && value == 13 -> 1
                matched == 1 && value == 10 -> 2
                matched == 2 && value == 13 -> 3
                matched == 3 && value == 10 -> return out.toByteArray()
                value == 13 -> 1
                else -> 0
            }
        }
        return out.toByteArray()
    }

    private fun headerValue(bytes: ByteArray): Map<String, String> {
        val lines = bytes.toString(StandardCharsets.ISO_8859_1).split("\r\n").drop(1)
        return lines.filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase(Locale.ROOT) to it.substringAfter(':').trim() }
    }

    private fun readExact(input: InputStream, size: Int): ByteArray? {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(bytes, offset, size - offset)
            if (read < 0) return null
            offset += read
        }
        return bytes
    }

    private fun responseStatus(bytes: ByteArray): Int = bytes.toString(StandardCharsets.ISO_8859_1)
        .substringBefore("\r\n").split(' ').getOrNull(1)?.toIntOrNull() ?: 0

    private fun relayResponse(remote: Socket, client: Socket, header: ByteArray, method: String): ByteArray? {
        val capture = CaptureBuffer()
        val lines = header.toString(StandardCharsets.ISO_8859_1).split("\r\n")
        val fields = lines.drop(1).dropLastWhile { it.isEmpty() }.map { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) throw java.io.IOException("Malformed upstream response header")
            line.substring(0, colon).lowercase(Locale.ROOT) to line.substring(colon + 1).trim()
        }
        if (fields.groupBy { it.first }.any { (name, values) ->
                values.size > 1 && name in setOf("content-length", "transfer-encoding")
            }) throw java.io.IOException("Ambiguous upstream response framing")
        val lengths = fields.filter { it.first == "content-length" }.map { it.second.toLongOrNull() }
        if (lengths.any { it == null || it < 0 }) throw java.io.IOException("Invalid upstream Content-Length")
        val transfer = fields.singleOrNull { it.first == "transfer-encoding" }?.second
        if (transfer != null && !transfer.equals("chunked", true)) throw java.io.IOException("Unsupported upstream framing")
        if (transfer != null && lengths.isNotEmpty()) throw java.io.IOException("Conflicting upstream response framing")

        val out = client.getOutputStream()
        out.write(header)
        val status = responseStatus(header)
        if (method.equals("HEAD", true) || status in 100..199 || status == 204 || status == 304) {
            out.flush(); return ByteArray(0)
        }
        when {
            transfer != null -> copyChunked(remote.getInputStream(), out, capture)
            lengths.singleOrNull() != null -> copyExact(remote.getInputStream(), out, lengths.single()!!, capture)
            else -> copyUntilClose(remote.getInputStream(), out, capture)
        }
        out.flush()
        return capture.bytes()
    }

    private fun relayInterceptedResponse(remote: Socket, client: Socket, header: ByteArray, method: String, row: ProxyExchange) {
        val status = responseStatus(header)
        val lines = header.toString(StandardCharsets.ISO_8859_1).split("\r\n").filter { it.isNotEmpty() }
        val fields = lines.drop(1).map { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) throw java.io.IOException("Malformed upstream response header")
            line.substring(0, colon).lowercase(Locale.ROOT) to line.substring(colon + 1).trim()
        }
        if (fields.groupBy { it.first }.any { (key, value) -> value.size > 1 && key in setOf("content-length", "transfer-encoding") })
            throw java.io.IOException("Ambiguous upstream response framing")
        if (fields.any { it.first == "transfer-encoding" }) {
            relayResponse(remote, client, header, method)
            return
        }
        val declaredLength = fields.singleOrNull { it.first == "content-length" }?.second?.toLongOrNull()
        if (!method.equals("HEAD", true) && status !in 100..199 && status != 204 && status != 304 &&
            (declaredLength == null || declaredLength !in 0..PlainHttpRequest.MAX_BODY.toLong())) {
            relayResponse(remote, client, header, method)
            return
        }
        val body = when {
            method.equals("HEAD", true) || status in 100..199 || status == 204 || status == 304 -> ByteArray(0)
            else -> {
                readExact(remote.getInputStream(), declaredLength!!.toInt()) ?: throw EOFException("Truncated response")
            }
        }
        row.responseBody = body
        // Preserve end-to-end cookies/auth headers; redact only the history summary, not browser traffic.
        val safeFields = fields.filterNot { it.first in setOf("connection", "content-length", "transfer-encoding") }
        val canonical = (listOf(lines.first()) + safeFields.map { (name, value) -> "$name: $value" } +
            "Content-Length: ${body.size}" + "Connection: close" + "" + body.toString(StandardCharsets.ISO_8859_1)).joinToString("\r\n")
        row.responseHeaders = canonical.substringBefore("\r\n\r\n") + "\r\n\r\n"
        row.responseMime = responseMime(header)
        val paused = InterceptedResponse(row, canonical)
        if (!pendingResponses.offer(paused)) throw java.io.IOException("Response interception queue full")
        row.state = ExchangeState.PAUSED
        listener.waiting(paused)
        listener.changed()
        val decision = paused.await()
        pendingResponses.remove(paused)
        when (decision) {
            is ResponseDecision.Forward -> client.getOutputStream().apply {
                write(decision.response.toByteArray(StandardCharsets.ISO_8859_1)); flush()
                row.responseHeaders = decision.response.substringBefore("\r\n\r\n") + "\r\n\r\n"
                row.responseBody = decision.response.substringAfter("\r\n\r\n").toByteArray(StandardCharsets.ISO_8859_1)
                row.status = responseStatus(decision.response.substringBefore("\r\n\r\n").toByteArray(StandardCharsets.ISO_8859_1))
            }
            ResponseDecision.Drop -> respond(client, 451, "Response dropped in T4X-SUITE")
            ResponseDecision.Timeout -> respond(client, 504, "Response interception timed out")
        }
    }

    private fun copyExact(input: InputStream, output: java.io.OutputStream, length: Long, capture: CaptureBuffer? = null) {
        if (length > 64L * 1024 * 1024) throw java.io.IOException("Response body too large")
        val buffer = ByteArray(8192)
        var remaining = length
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) throw EOFException("Truncated response body")
            output.write(buffer, 0, count)
            capture?.add(buffer, count)
            remaining -= count
        }
    }

    private fun copyUntilClose(input: InputStream, output: java.io.OutputStream, capture: CaptureBuffer? = null) {
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            total += count
            if (total > 64L * 1024 * 1024) throw java.io.IOException("Response body too large")
            output.write(buffer, 0, count)
            capture?.add(buffer, count)
        }
    }

    private fun copyChunked(input: InputStream, output: java.io.OutputStream, capture: CaptureBuffer? = null) {
        var total = 0L
        while (true) {
            val line = readCrlfLine(input) ?: throw EOFException("Truncated chunk header")
            val sizeText = line.toString(StandardCharsets.ISO_8859_1).trim().substringBefore(';')
            val size = sizeText.toLongOrNull(16) ?: throw java.io.IOException("Invalid chunk size")
            if (size < 0 || total + size > 64L * 1024 * 1024) throw java.io.IOException("Response body too large")
            output.write(line); output.write("\r\n".toByteArray())
            if (size == 0L) {
                while (true) {
                    val trailer = readCrlfLine(input) ?: throw EOFException("Truncated chunk trailer")
                    output.write(trailer); output.write("\r\n".toByteArray())
                    if (trailer.isEmpty()) return
                }
            }
            copyExact(input, output, size, capture)
            val cr = input.read(); val lf = input.read()
            if (cr != '\r'.code || lf != '\n'.code) throw java.io.IOException("Malformed chunk terminator")
            output.write("\r\n".toByteArray())
            total += size
        }
    }

    private fun readCrlfLine(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        while (out.size() <= PlainHttpRequest.MAX_HEAD) {
            val value = input.read()
            if (value < 0) return null
            if (value == '\r'.code) {
                if (input.read() != '\n'.code) throw java.io.IOException("Malformed line ending")
                return out.toByteArray()
            }
            if (value == '\n'.code) throw java.io.IOException("Malformed line ending")
            out.write(value)
        }
        throw java.io.IOException("Response line too large")
    }

    private class CaptureBuffer {
        private val out = ByteArrayOutputStream()
        private var overflow = false
        fun add(bytes: ByteArray, count: Int) {
            if (overflow) return
            if (out.size() + count > PlainHttpRequest.MAX_BODY) { overflow = true; return }
            out.write(bytes, 0, count)
        }
        fun bytes(): ByteArray? = if (overflow) null else out.toByteArray()
    }

    private fun responseMime(header: ByteArray): String = header.toString(StandardCharsets.ISO_8859_1).lineSequence()
        .firstOrNull { it.substringBefore(':').trim().equals("content-type", true) }
        ?.substringAfter(':')?.trim()?.substringBefore(';')?.takeIf { it.isNotBlank() } ?: "text/plain"

    private fun respond(client: Socket, code: Int, text: String) {
        val body = text.toByteArray()
        client.getOutputStream().apply {
            write("HTTP/1.1 $code $text\r\nConnection: close\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray())
            write(body); flush()
        }
    }

    private fun add(row: ProxyExchange) {
        synchronized(historyLock) {
            if (history.size == 500) history.removeLast()
            history.addFirst(row)
        }
        listener.changed()
    }

    private fun now(): String = java.text.SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(java.util.Date())

    private fun copy(from: Socket, to: Socket) {
        try {
            val buffer = ByteArray(8192)
            while (running && !from.isClosed && !to.isClosed) {
                val count = from.getInputStream().read(buffer)
                if (count < 0) break
                to.getOutputStream().apply { write(buffer, 0, count); flush() }
            }
        } catch (_: Exception) { }
        runCatching { to.shutdownOutput() }
    }

    private fun daemon(name: String): ThreadFactory = ThreadFactory { runnable -> Thread(runnable, name).apply { isDaemon = true } }
}
