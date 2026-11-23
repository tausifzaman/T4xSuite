package dev.t4xsuite.proxy

import android.content.Context
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec


/** Encrypts the CA signing key at rest with a non-exportable Android Keystore AES key. */
class AndroidCaVault(context: Context) {
    private val app = context.applicationContext
    private val file get() = java.io.File(app.filesDir, "t4xsuite-ca.enc")
    private val alias = "t4xsuite.ca.storage.v1"

    @Synchronized fun exists(): Boolean = file.isFile && runCatching { load() }.isSuccess

    @Synchronized fun create(): CertificateAuthorityCore.Identity {
        val ca = CertificateAuthorityCore.newCa()
        val data = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(ca.pair.private.encoded.size); out.write(ca.pair.private.encoded)
                out.writeInt(ca.pair.public.encoded.size); out.write(ca.pair.public.encoded)
                out.writeInt(ca.certificate.encoded.size); out.write(ca.certificate.encoded)
            }
        }.toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val encrypted = cipher.doFinal(data)
        val temp = java.io.File(app.filesDir, "t4xsuite-ca.enc.tmp")
        DataOutputStream(temp.outputStream()).use { out ->
            out.writeInt(cipher.iv.size); out.write(cipher.iv)
            out.writeInt(encrypted.size); out.write(encrypted)
            out.flush()
        }
        if (!temp.renameTo(file)) { temp.delete(); throw java.io.IOException("Unable to securely save CA") }
        return ca
    }

    @Synchronized fun load(): CertificateAuthorityCore.Identity {
        val all = DataInputStream(file.inputStream()).use { input ->
            val ivSize = input.readInt().takeIf { it in 12..16 } ?: error("Invalid CA store")
            val iv = ByteArray(ivSize).also(input::readFully)
            val dataSize = input.readInt().takeIf { it in 1..16_384 } ?: error("Invalid CA store")
            val encrypted = ByteArray(dataSize).also(input::readFully)
            require(input.read() == -1)
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, iv))
                doFinal(encrypted)
            }
        }
        val (privateBytes, publicBytes, certBytes) = DataInputStream(all.inputStream()).use { input ->
            fun bytes() = ByteArray(input.readInt().also { require(it in 1..8192) }).also(input::readFully)
            Triple(bytes(), bytes(), bytes())
        }
        val factory = KeyFactory.getInstance("RSA")
        val pair = KeyPair(factory.generatePublic(X509EncodedKeySpec(publicBytes)), factory.generatePrivate(PKCS8EncodedKeySpec(privateBytes)))
        val cert = CertificateAuthorityCore.parseCertificate(certBytes)
        cert.verify(pair.public)
        require(cert.basicConstraints >= 0)
        return CertificateAuthorityCore.Identity(pair, cert)
    }

    /** Exports only the public CA certificate to the user-visible Downloads collection. */
    @Synchronized fun exportCertificate(): Uri {
        val certificate = load().certificate.encoded
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "T4X-SUITE-CA.crt")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/x-x509-ca-cert")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/T4X-SUITE")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw java.io.IOException("Unable to create certificate file")
        try {
            app.contentResolver.openOutputStream(uri, "w")?.use { it.write(certificate) }
                ?: throw java.io.IOException("Unable to write certificate file")
            val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            app.contentResolver.update(uri, ready, null, null)
            return uri
        } catch (error: Exception) {
            app.contentResolver.delete(uri, null, null)
            throw error
        }
    }

    @Synchronized fun delete() {
        file.delete()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    /** Checks whether the given CA certificate is installed in Android's user CA store. */
    fun isCaInstalledInUserStore(cert: X509Certificate): Boolean = runCatching {
        val target = cert.encoded
        val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val aliases = store.aliases()
        while (aliases.hasMoreElements()) {
            val entry = aliases.nextElement()
            if (!entry.startsWith("user:")) continue
            val installed = store.getCertificate(entry) as? X509Certificate ?: continue
            if (installed.encoded.contentEquals(target)) return@runCatching true
        }
        false
    }.getOrDefault(false)

    private fun encryptionKey(): SecretKey {

        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
            generateKey()
        }
    }
}
