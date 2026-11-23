package dev.t4xsuite.proxy

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Minimal DER X.509 issuer for an explicit, browser-only testing CA. */
object CertificateAuthorityCore {
    data class Identity(val pair: KeyPair, val certificate: X509Certificate)

    fun newCa(): Identity {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        return Identity(pair, issue(pair, pair.public, "T4X-SUITE Local Proxy CA", null, true))
    }

    fun issueServer(ca: Identity, host: String): Identity {
        require(host.isNotBlank() && host.length <= 253 && host.none { it <= ' ' || it in "/\\@" })
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        return Identity(pair, issue(pair, ca.pair.public, host, ca, false))
    }

    fun parseCertificate(encoded: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(encoded.inputStream()) as X509Certificate

    private fun issue(pair: KeyPair, issuerPublic: java.security.PublicKey, commonName: String, issuer: Identity?, ca: Boolean): X509Certificate {
        val now = System.currentTimeMillis()
        val issuerName = name(if (issuer == null) commonName else "T4X-SUITE Local Proxy CA")
        val subjectName = name(commonName)
        val algorithm = seq(oid(1, 2, 840, 113549, 1, 1, 11), nullValue())
        val lifetime = if (ca) 10L * 365 * 24 * 60 * 60 * 1000 else 30L * 24 * 60 * 60 * 1000
        val validity = seq(time(Date(now - 60_000)), time(Date(now + lifetime)))
        val extensions = mutableListOf<ByteArray>()
        if (ca) {
            extensions += extension("2.5.29.19", true, seq(bool(true)))
            extensions += extension("2.5.29.15", true, bitString(byteArrayOf(0x06), 1))
            extensions += extension("2.5.29.14", false, octet(sha1(pair.public.encoded)))
        } else {
            extensions += extension("2.5.29.19", true, seq())
            extensions += extension("2.5.29.15", true, bitString(byteArrayOf(0xA0.toByte()), 5))
            extensions += extension("2.5.29.37", false, seq(oid(1, 3, 6, 1, 5, 5, 7, 3, 1)))
            extensions += extension("2.5.29.17", false, seq(generalName(commonName)))
            extensions += extension("2.5.29.35", false, seq(context(0x80, sha1(issuerPublic.encoded))))
        }
        val tbs = seq(
            context(0xA0, integer(byteArrayOf(2))),
            integer(byteArrayOf((1..126).random().toByte(), (1..126).random().toByte(), (1..126).random().toByte(), (1..126).random().toByte())),
            algorithm, issuerName, validity, subjectName, pair.public.encoded,
            context(0xA3, seq(*extensions.toTypedArray())),
        )
        val signer: PrivateKey = issuer?.pair?.private ?: pair.private
        val signature = Signature.getInstance("SHA256withRSA").run { initSign(signer); update(tbs); sign() }
        val encoded = seq(tbs, algorithm, bitString(signature, 0))
        return parseCertificate(encoded).also { it.checkValidity(); it.verify(issuerPublic) }
    }

    private fun name(value: String) = seq(set(seq(oid(2, 5, 4, 3), tagged(0x0C, value.toByteArray(Charsets.UTF_8)))))
    private fun time(date: Date): ByteArray {
        val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return tagged(0x17, fmt.format(date).toByteArray(Charsets.US_ASCII))
    }

    private fun extension(id: String, critical: Boolean, value: ByteArray) =
        seq(oid(id), if (critical) bool(true) else byteArrayOf(), octet(value)).let { if (critical) it else seq(oid(id), octet(value)) }

    private fun generalName(host: String): ByteArray = if (host.contains(':') || host.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))) {
        context(0x87, InetAddress.getByName(host).address)
    } else context(0x82, host.toByteArray(Charsets.US_ASCII))

    private fun oid(vararg parts: Int): ByteArray = oidBody(parts.toList())
    private fun oid(text: String): ByteArray = oidBody(text.split('.').map { it.toInt() })
    private fun oidBody(parts: List<Int>): ByteArray {
        require(parts.size >= 2)
        val values = listOf(parts[0] * 40 + parts[1]) + parts.drop(2)
        val out = ByteArrayOutputStream()
        values.forEach { n ->
            require(n >= 0)
            var value = n.toLong()
            val encoded = mutableListOf((value and 0x7f).toInt())
            value = value shr 7
            while (value > 0) { encoded.add(0, ((value and 0x7f) or 0x80).toInt()); value = value shr 7 }
            encoded.forEach(out::write)
        }
        return tagged(0x06, out.toByteArray())
    }

    private fun seq(vararg values: ByteArray) = tagged(0x30, values.fold(ByteArrayOutputStream()) { out, bytes -> out.apply { write(bytes) } }.toByteArray())
    private fun set(value: ByteArray) = tagged(0x31, value)
    private fun integer(value: ByteArray) = tagged(0x02, BigInteger(1, value).toByteArray())
    private fun bool(value: Boolean) = tagged(0x01, byteArrayOf(if (value) 0xFF.toByte() else 0))
    private fun nullValue() = tagged(0x05, byteArrayOf())
    private fun octet(value: ByteArray) = tagged(0x04, value)
    private fun bitString(value: ByteArray, unused: Int) = tagged(0x03, byteArrayOf(unused.toByte()) + value)
    private fun context(tag: Int, value: ByteArray) = tagged(tag, value)
    private fun tagged(tag: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        when {
            value.size < 128 -> out.write(value.size)
            value.size < 256 -> { out.write(0x81); out.write(value.size) }
            else -> { out.write(0x82); out.write(value.size shr 8); out.write(value.size and 0xff) }
        }
        out.write(value)
        return out.toByteArray()
    }

    private fun sha1(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-1").digest(bytes)
}
