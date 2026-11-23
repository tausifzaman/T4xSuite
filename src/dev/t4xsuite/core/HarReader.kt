package dev.t4xsuite.core

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.InputStreamReader
import java.net.URI
import java.util.Locale

private const val MAX_BYTES = 8L * 1024 * 1024
private const val MAX_ENTRIES = 2_500
private const val MAX_HEADERS = 96
private const val MAX_TOTAL_HEADERS = 24_000
private val HTTP_TOKEN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
private val DNS_OR_IP = Regex("[0-9A-Za-z.:%_\\[\\]-]+")
private val NUMERIC = Regex("[0-9]{1,16}")
private val MIME = Regex("[a-z0-9!#$&^_.+-]{1,40}/[a-z0-9!#$&^_.+-]{1,60}")

enum class ImportProblem(val userMessage: String) {
    INVALID("This isn't a supported HTTP HAR capture. The existing session was kept."),
    TOO_LARGE("The capture exceeds the 8 MiB import limit."),
    TOO_MANY("The capture exceeds the exchange or header limit."),
}

class HarException(val problem: ImportProblem) : IOException(problem.userMessage)
internal data class RawHeader(val name: String, val value: String)

object HarReader {
    /** Consumes and closes the stream. Model state only contains sanitized metadata. */
    fun read(source: InputStream): Capture {
        JsonReader(InputStreamReader(BoundedInput(source), Charsets.UTF_8.newDecoder())).use { reader ->
            reader.strictness = Strictness.STRICT
            reader.nestingLimit = 40
            return Parser(reader).capture()
        }
    }
}

private class Parser(private val json: JsonReader) {
    private var headerCount = 0

    fun capture(): Capture {
        var entries: List<Exchange>? = null
        json.obj(setOf("log")) { rootKey ->
            if (rootKey == "log") {
                json.obj(setOf("entries")) { logKey ->
                    if (logKey == "entries") entries = exchanges() else json.skipValue()
                }
            } else json.skipValue()
        }
        if (json.peek() != JsonToken.END_DOCUMENT) invalid()
        return Capture(entries ?: invalid())
    }

    private fun exchanges(): List<Exchange> = buildList {
        json.beginArray()
        while (json.hasNext()) {
            if (size == MAX_ENTRIES) throw HarException(ImportProblem.TOO_MANY)
            checkCancelled()
            add(exchange())
        }
        json.endArray()
    }

    private fun exchange(): Exchange {
        var request: Side? = null
        var response: Side? = null
        var duration: Long? = null
        json.obj(setOf("request", "response", "time")) { key ->
            when (key) {
                "request" -> request = side(true)
                "response" -> response = side(false)
                "time" -> duration = if (json.peek() == JsonToken.NUMBER) {
                    json.nextDouble().takeIf { it >= 0 && it <= 86_400_000 }?.toLong()
                } else { json.skipValue(); null }
                else -> json.skipValue()
            }
        }
        val req = request ?: invalid()
        val res = response ?: invalid()
        val method = req.method?.takeIf { it.length in 1..24 && HTTP_TOKEN.matches(it) }
            ?.uppercase(Locale.ROOT) ?: invalid()
        val uri = try { URI(req.url ?: invalid()) } catch (_: java.net.URISyntaxException) { invalid() }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)?.takeIf { it == "http" || it == "https" } ?: invalid()
        val hostname = uri.host?.takeIf { it.length <= 255 && DNS_OR_IP.matches(it) } ?: invalid()
        val host = if (uri.port >= 0) "$hostname:${uri.port}" else hostname
        val path = (uri.rawPath?.ifEmpty { "/" } ?: "/").take(768).filterNot {
            it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()
        }
        val status = res.status ?: invalid()
        val mime = res.headers.firstOrNull { it.name == "content-type" }?.value?.let(::mimeType)
            ?: res.mime?.let(::mimeType)
        return Exchange(method, scheme, host, path, status, duration, req.headers.map(::sanitize),
            res.headers.map(::sanitize), review(scheme, method, status, mime, res.headers))
    }

    private class Side {
        var method: String? = null
        var url: String? = null
        var status: Int? = null
        var mime: String? = null
        var headers: List<RawHeader> = emptyList()
    }

    private fun side(request: Boolean) = Side().apply {
        json.obj(if (request) setOf("method", "url", "headers") else setOf("status", "headers", "content")) { key ->
            when {
                key == "headers" -> headers = headers()
                request && key == "method" -> method = json.string()
                request && key == "url" -> url = json.string().takeIf { it.length <= 16_384 } ?: invalid()
                !request && key == "status" -> {
                    if (json.peek() != JsonToken.NUMBER) invalid()
                    status = json.nextInt().takeIf { it == 0 || it in 100..599 } ?: invalid()
                }
                !request && key == "content" && json.peek() == JsonToken.BEGIN_OBJECT -> {
                    json.obj(setOf("mimeType")) { contentKey ->
                        if (contentKey == "mimeType" && json.peek() == JsonToken.STRING) mime = json.string()
                        else json.skipValue()
                    }
                }
                else -> json.skipValue()
            }
        }
    }

    private fun headers(): List<RawHeader> = buildList {
        json.beginArray()
        while (json.hasNext()) {
            if (size == MAX_HEADERS || ++headerCount > MAX_TOTAL_HEADERS) throw HarException(ImportProblem.TOO_MANY)
            var name: String? = null
            var value: String? = null
            json.obj(setOf("name", "value")) { key ->
                when (key) {
                    "name" -> name = json.string()
                    "value" -> value = json.string()
                    else -> json.skipValue()
                }
            }
            val token = name?.takeIf { it.length in 1..96 && HTTP_TOKEN.matches(it) }?.lowercase(Locale.ROOT)
            add(RawHeader(token ?: "(invalid header name)", value ?: invalid()))
        }
        json.endArray()
    }
}

private fun sanitize(header: RawHeader): Header {
    val summary = when (header.name) {
        "content-type" -> mimeType(header.value)
        "content-length" -> header.value.trim().takeIf(NUMERIC::matches)
        "strict-transport-security" -> if (hasPositiveAge(header.value)) "Positive max-age observed" else "No positive max-age observed"
        "content-security-policy", "content-security-policy-report-only" -> if (header.value.isBlank()) "Empty" else "Present · policy text hidden"
        "x-frame-options" -> frameValue(header.value)
        "x-content-type-options" -> if (header.value.trim().equals("nosniff", true)) "nosniff" else null
        else -> null
    }
    return Header(header.name, summary)
}

private fun mimeType(value: String): String? = value.substringBefore(';').trim().lowercase(Locale.ROOT).takeIf(MIME::matches)

private inline fun JsonReader.obj(unique: Set<String>, field: (String) -> Unit) {
    val seen = HashSet<String>()
    beginObject()
    while (hasNext()) {
        val key = nextName()
        if (key in unique && !seen.add(key)) invalid()
        field(key)
    }
    endObject()
}

private fun JsonReader.string(): String {
    if (peek() != JsonToken.STRING) invalid()
    return nextString()
}

private fun invalid(): Nothing = throw HarException(ImportProblem.INVALID)

private fun checkCancelled() {
    if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
}

private class BoundedInput(source: InputStream) : FilterInputStream(source) {
    private var remaining = MAX_BYTES

    override fun read(): Int {
        checkCancelled()
        val next = `in`.read()
        if (next != -1) consume(1)
        return next
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        checkCancelled()
        val count = `in`.read(b, off, minOf(len, (remaining + 1).toInt()))
        if (count > 0) consume(count)
        return count
    }

    private fun consume(count: Int) {
        remaining -= count
        if (remaining < 0) throw HarException(ImportProblem.TOO_LARGE)
    }
}
