package dev.t4xsuite.core

import java.util.Locale

data class Capture(val exchanges: List<Exchange>) {
    val findings: List<Finding> = Rule.entries.mapNotNull { rule ->
        exchanges.count { rule in it.rules }.takeIf { it > 0 }?.let { Finding(rule, it) }
    }
}

data class Exchange(
    val method: String,
    val scheme: String,
    val host: String,
    val path: String,
    val status: Int,
    val elapsedMs: Long?,
    val requestHeaders: List<Header>,
    val responseHeaders: List<Header>,
    val rules: List<Rule>,
) {
    val searchable = "$method $scheme $host $path ${status.takeIf { it > 0 } ?: "unknown"}"
        .lowercase(Locale.ROOT)
}

data class Header(val name: String, val summary: String?)
data class Finding(val rule: Rule, val count: Int)

enum class Rule(val title: String, val severity: String, val action: String) {
    HSTS("HSTS not observed", "Low", "For HTTPS-only hosts, verify a positive max-age on Strict-Transport-Security. Review subdomain readiness before adding includeSubDomains."),
    CSP("Enforced CSP not observed", "Low", "Review the HTML document's Content-Security-Policy. A report-only header does not enforce a policy."),
    FRAME("Framing policy not observed", "Low", "Confirm which sites should be allowed to frame this page. Consider CSP frame-ancestors, or a suitable X-Frame-Options policy for legacy clients."),
    COOKIE_SECURE("Cookie without Secure", "Low", "For cookies intended only for HTTPS, set the Secure attribute."),
    COOKIE_HTTP_ONLY("Cookie without HttpOnly", "Info", "For cookies that do not need script access, set HttpOnly."),
    COOKIE_SAME_SITE("Cookie without recognized SameSite", "Info", "Choose an explicit SameSite mode that fits the application. Modern browser defaults and exceptions vary."),
}

private val HSTS_AGE = Regex("(?i)^max-age\\s*=\\s*(\\d+)$")
private const val CUTOFF = 64

internal fun review(scheme: String, method: String, status: Int, mime: String?, headers: List<RawHeader>): List<Rule> {
    if (status == 0) return emptyList()
    val result = linkedSetOf<Rule>()
    val html = mime == "text/html" || mime == "application/xhtml+xml"
    if (html && status in 200..299 && status != 204 && status != 205 && method != "HEAD") {
        val csp = headers.filter { it.name == "content-security-policy" }.map { it.value }
        if (csp.none { it.isNotBlank() }) result += Rule.CSP
        val frameAncestors = csp.any { policy ->
            policy.splitToSequence(';').any { directive ->
                val text = directive.trim()
                val separator = text.indexOfFirst(Char::isWhitespace)
                separator == "frame-ancestors".length && text.startsWith("frame-ancestors", ignoreCase = true)
            }
        }
        val xfo = headers.any { it.name == "x-frame-options" && frameValue(it.value) != null }
        if (!frameAncestors && !xfo) result += Rule.FRAME
        if (scheme == "https" && headers.none { it.name == "strict-transport-security" && hasPositiveAge(it.value) }) {
            result += Rule.HSTS
        }
    }
    headers.filter { it.name == "set-cookie" }.forEach { header ->
        val parts = header.value.splitToSequence(';').iterator()
        if (!parts.hasNext() || parts.next().substringBefore('=', "").isBlank()) return@forEach
        var secure = false
        var httpOnly = false
        var sameSite = false
        for (attribute in parts) {
            val text = attribute.trim()
            when (text.substringBefore('=').trim().lowercase(Locale.ROOT)) {
                "secure" -> secure = true
                "httponly" -> httpOnly = true
                "samesite" -> {
                    when (text.substringAfter('=', "").trim().lowercase(Locale.ROOT)) {
                        "strict", "lax", "none" -> sameSite = true
                    }
                }
            }
        }
        if (scheme == "https" && !secure) result += Rule.COOKIE_SECURE
        if (!httpOnly) result += Rule.COOKIE_HTTP_ONLY
        if (!sameSite) result += Rule.COOKIE_SAME_SITE
    }
    return result.sortedBy { it.ordinal }
}

internal fun hasPositiveAge(value: String): Boolean = value.splitToSequence(';').any { directive ->
    val match = directive.trim().takeIf { it.length <= CUTOFF }?.let(HSTS_AGE::matchEntire)
    match?.groupValues?.get(1)?.any { it != '0' } == true
}

internal fun frameValue(value: String): String? = value.trim().uppercase(Locale.ROOT)
    .takeIf { it == "DENY" || it == "SAMEORIGIN" }

fun markdown(capture: Capture): String = buildString {
    appendLine("# T4X-SUITE · Passive HTTP review")
    appendLine()
    appendLine("Exchanges reviewed: ${capture.exchanges.size}. Finding types: ${capture.findings.size}.")
    appendLine()
    appendLine("These observations are based only on the imported capture. They are not confirmed vulnerabilities, and a clean report does not establish security.")
    appendLine()
    if (capture.findings.isEmpty()) appendLine("No review candidates matched the built-in rules.")
    capture.findings.forEach { (rule, count) ->
        appendLine("## ${rule.title}")
        appendLine()
        appendLine("Advisory severity: ${rule.severity}. Observed on $count exchange(s). ${rule.action}")
        appendLine()
    }
    appendLine("Report includes counts and fixed remediation text only. Endpoints, headers, bodies and credentials are not included.")
}
