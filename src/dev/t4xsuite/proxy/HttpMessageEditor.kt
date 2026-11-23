package dev.t4xsuite.proxy

import java.net.URI

/** UI-only canonicalization. The network parser remains strict and rejects ambiguous framing. */
object HttpMessageEditor {
    fun request(text: String, original: String? = null): String {
        var wire = canonical(text)
        if (original != null) {
            val before = PlainHttpRequest.parse(original.toByteArray(Charsets.ISO_8859_1), null)
                ?: throw IllegalArgumentException("The original request is unavailable.")
            val first = wire.substringBefore("\r\n").split(' ')
            if (first.size == 3 && first[1].startsWith('/') && !first[1].startsWith("//")) {
                wire = "${first[0]} ${before.scheme}://${before.authority}${first[1]} ${first[2]}" +
                    wire.substring(wire.indexOf("\r\n"))
            }
            require(PlainHttpRequest.validEdited(wire, original)) {
                "Use METHOD URL HTTP/1.1, one matching Host header, and keep the original host and scheme. Chunked requests are not editable."
            }
        } else {
            val parsed = PlainHttpRequest.parse(wire.toByteArray(Charsets.ISO_8859_1), null)
            require(parsed != null && parsed.method != "CONNECT") {
                "Use METHOD http(s)://host/path HTTP/1.1 and one matching Host header. Chunked requests are not supported."
            }
            require(URI(parsed.target).host != null)
        }
        return wire
    }

    fun response(text: String): String = canonical(text)

    private fun canonical(text: String): String {
        require(text.length <= PlainHttpRequest.MAX_HEAD + PlainHttpRequest.MAX_BODY) { "Message exceeds the edit limit." }
        require(text.all { it.code <= 255 } && '\u0000' !in text) {
            "This is a byte-oriented editor. Encode non-Latin text (for example, URL encoding) before sending."
        }
        val separator = Regex("\r?\n\r?\n").find(text)
            ?: throw IllegalArgumentException("Leave a blank line between headers and body.")
        val head = text.substring(0, separator.range.first).replace("\r\n", "\n")
        require('\r' !in head) { "Use LF or CRLF header line endings." }
        val body = text.substring(separator.range.last + 1)
        require(body.length <= PlainHttpRequest.MAX_BODY) { "Body exceeds 1 MiB." }
        val lines = head.split('\n').toMutableList()
        val framing = lines.drop(1).map { it.substringBefore(':').lowercase(java.util.Locale.ROOT) }
        require(framing.count { it == "content-length" } <= 1 && "transfer-encoding" !in framing) {
            "Remove duplicate Content-Length or Transfer-Encoding headers."
        }
        val lengthIndex = lines.indexOfFirst { it.substringBefore(':').equals("Content-Length", true) }
        if (lengthIndex >= 0) {
            require(lines[lengthIndex].substringAfter(':', "").trim().matches(Regex("[0-9]+"))) {
                "Content-Length must be a non-negative integer."
            }
            lines[lengthIndex] = "Content-Length: ${body.toByteArray(Charsets.ISO_8859_1).size}"
        } else if (body.isNotEmpty()) {
            lines.add("Content-Length: ${body.toByteArray(Charsets.ISO_8859_1).size}")
        }
        val result = lines.joinToString("\r\n") + "\r\n\r\n" + body
        require(result.indexOf("\r\n\r\n") <= PlainHttpRequest.MAX_HEAD) { "Headers exceed 32 KiB." }
        return result
    }
}
