package dev.t4xsuite.proxy

import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class ExchangeState { CONNECTED, CAPTURED, PAUSED, FORWARDED, DROPPED, FAILED }

data class ProxyExchange(
    val id: Long,
    @Volatile var method: String,
    @Volatile var target: String,
    val isHttps: Boolean,
    val time: String,
    @Volatile var state: ExchangeState,
    @Volatile var status: Int = 0,
    @Volatile var request: String = "",
    @Volatile var responseHeaders: String = "",
    @Volatile var responseBody: ByteArray = ByteArray(0),
    @Volatile var responseMime: String = "text/plain",
    @Volatile var elapsedMs: Long = 0,
)

class InterceptedRequest(
    val exchange: ProxyExchange,
    initial: String,
    private val answer: CompletableFuture<Decision> = CompletableFuture(),
) {
    private val original = initial
    private val completed = AtomicBoolean(false)

    val isPending: Boolean get() = !completed.get()

    fun continueOriginal(): Boolean = continueWith(original)

    fun continueWith(edited: String): Boolean {
        if (completed.get() || !PlainHttpRequest.validEdited(edited, original)) return false
        if (!completed.compareAndSet(false, true)) return false
        return answer.complete(Decision.Forward(edited))
    }

    fun drop(): Boolean {
        if (!completed.compareAndSet(false, true)) return false
        exchange.state = ExchangeState.DROPPED
        return answer.complete(Decision.Drop)
    }

    fun await(): Decision = try {
        answer.get(120, TimeUnit.SECONDS)
    } catch (_: Exception) {
        completed.set(true)
        exchange.state = ExchangeState.FAILED
        Decision.Timeout
    }

    fun cancel() {
        if (!completed.compareAndSet(false, true)) return
        exchange.state = ExchangeState.FAILED
        answer.complete(Decision.Drop)
    }
}

sealed interface Decision {
    data class Forward(val request: String) : Decision
    data object Drop : Decision
    data object Timeout : Decision
}

class InterceptedResponse(
    val exchange: ProxyExchange,
    initial: String,
    private val answer: CompletableFuture<ResponseDecision> = CompletableFuture(),
) {
    private val original = initial
    private val completed = AtomicBoolean(false)

    val isPending: Boolean get() = !completed.get()

    fun continueOriginal(): Boolean = continueWith(original)

    fun continueWith(edited: String): Boolean {
        if (!ResponseEditor.validEdited(edited, original)) return false
        if (!completed.compareAndSet(false, true)) return false
        return answer.complete(ResponseDecision.Forward(edited))
    }

    fun drop(): Boolean {
        if (!completed.compareAndSet(false, true)) return false
        exchange.state = ExchangeState.DROPPED
        return answer.complete(ResponseDecision.Drop)
    }

    fun await(): ResponseDecision = try {
        answer.get(120, TimeUnit.SECONDS)
    } catch (_: Exception) {
        completed.set(true)
        exchange.state = ExchangeState.FAILED
        ResponseDecision.Timeout
    }

    fun cancel() {
        if (!completed.compareAndSet(false, true)) return
        exchange.state = ExchangeState.FAILED
        answer.complete(ResponseDecision.Drop)
    }
}

sealed interface ResponseDecision {
    data class Forward(val response: String) : ResponseDecision
    data object Drop : ResponseDecision
    data object Timeout : ResponseDecision
}

private object ResponseEditor {
    fun validEdited(candidate: String, original: String): Boolean {
        val bytes = candidate.toByteArray(Charsets.ISO_8859_1)
        if (bytes.size > PlainHttpRequest.MAX_HEAD + PlainHttpRequest.MAX_BODY) return false
        val split = candidate.indexOf("\r\n\r\n")
        if (split !in 1..PlainHttpRequest.MAX_HEAD) return false
        val lines = candidate.substring(0, split).split("\r\n")
        if (lines.size > 101 || !Regex("HTTP/1\\.1 [1-5][0-9]{2}(?: [\\x20-\\x7E]*)?").matches(lines.firstOrNull().orEmpty())) return false
        val headers = lines.drop(1).map { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) return false
            val name = line.substring(0, colon)
            val value = line.substring(colon + 1).trim()
            if (!Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+").matches(name) || value.any { it.code < 0x20 && it != '\t' || it.code == 0x7f }) return false
            name.lowercase(Locale.ROOT) to value
        }
        if (headers.groupBy { it.first }.any { it.value.size > 1 && it.key in setOf("content-length", "transfer-encoding") }) return false
        if (headers.any { it.first == "transfer-encoding" }) return false
        val declared = headers.singleOrNull { it.first == "content-length" }?.second?.toLongOrNull()
        if (headers.any { it.first == "content-length" } && declared == null) return false
        val length = declared ?: 0L
        if (length !in 0..PlainHttpRequest.MAX_BODY || bytes.size != split + 4 + length.toInt()) return false
        return original.substringBefore("\r\n").startsWith("HTTP/")
    }
}

data class PlainHttpRequest(val method: String, val authority: String, val target: String, val raw: String, val scheme: String = "http") {
    companion object {
        const val MAX_HEAD = 32 * 1024
        const val MAX_BODY = 1024 * 1024
        private val TOKEN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        private val AUTHORITY = Regex("(?:[A-Za-z0-9.-]+|\\[[0-9A-Fa-f:.]+])(?::[0-9]{1,5})?")

        fun parse(bytes: ByteArray, requiredScheme: String? = "http"): PlainHttpRequest? {
            val raw = bytes.toString(Charsets.ISO_8859_1)
            if (raw.length > MAX_HEAD + MAX_BODY || raw.any { it == '\u0000' }) return null
            val split = raw.indexOf("\r\n\r\n")
            if (split !in 1..MAX_HEAD) return null
            val head = raw.substring(0, split)
            val lines = head.split("\r\n")
            if (lines.size > 101 || lines.any { '\n' in it || '\r' in it }) return null
            val line = lines.first().split(' ')
            if (line.size != 3 || !TOKEN.matches(line[0]) || line[2] != "HTTP/1.1") return null
            val url = try { java.net.URI(line[1]) } catch (_: Exception) { return null }
            if ((requiredScheme != null && !url.scheme.equals(requiredScheme, true)) ||
                (requiredScheme == null && url.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) ||
                url.rawUserInfo != null || url.host.isNullOrBlank() || url.rawFragment != null ||
                (url.port != -1 && url.port !in 1..65535)) return null
            val headers = lines.drop(1).map { text ->
                val index = text.indexOf(':')
                if (index !in 1..256 || text.startsWith(' ') || text.startsWith('\t')) return null
                val name = text.substring(0, index)
                val value = text.substring(index + 1).trim()
                if (!TOKEN.matches(name) || value.any { (it.code < 0x20 && it != '\t') || it.code == 0x7f }) return null
                name.lowercase(Locale.ROOT) to value
            }
            if (headers.groupBy { it.first }.any { it.value.size > 1 }) return null
            if (headers.any { it.first in setOf("transfer-encoding", "expect", "upgrade", "proxy-authorization") }) return null
            val lengths = headers.filter { it.first == "content-length" }.map { it.second }
            if (lengths.size > 1) return null
            val length = lengths.firstOrNull()?.toLongOrNull() ?: if (lengths.isEmpty()) 0 else return null
            if (length !in 0..MAX_BODY.toLong() || bytes.size != split + 4 + length.toInt()) return null
            val authority = url.rawAuthority ?: return null
            if (!AUTHORITY.matches(authority) || authority.contains('@')) return null
            val hostHeaders = headers.filter { it.first == "host" }
            if (hostHeaders.size != 1 || !hostHeaders.single().second.equals(authority, true)) return null
            return PlainHttpRequest(line[0].uppercase(Locale.ROOT), authority, line[1], raw, url.scheme.lowercase(Locale.ROOT))
        }

        fun validEdited(candidate: String, original: String): Boolean {
            if (candidate.toByteArray(Charsets.ISO_8859_1).size > MAX_HEAD + MAX_BODY) return false
            val candidateBytes = candidate.toByteArray(Charsets.ISO_8859_1)
            val before = parse(original.toByteArray(Charsets.ISO_8859_1), null) ?: return false
            val parsed = parse(candidateBytes, before.scheme) ?: return false
            if (parsed.method == "CONNECT") return false
            if (!parsed.authority.equals(before.authority, true)) return false
            return true
        }
    }
}
