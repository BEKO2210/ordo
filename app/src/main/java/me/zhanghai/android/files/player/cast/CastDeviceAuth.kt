/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player.cast

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Who is on the other end. A Cast device's TLS certificate is self-signed and lives at most four days (Chromium's
 * openscreen cast_auth_util.cc, kMaxSelfSignedCertLifetimeInDays), so it cannot be pinned. What stays is the device
 * certificate: in the device auth exchange the device signs `sender_nonce || DER(TLS certificate)` with it. Ordo pins
 * that certificate's key per device on first use and refuses the device when a later connection shows another one.
 */
object CastDeviceAuth {
    const val NAMESPACE = "urn:x-cast:com.google.cast.tp.deviceauth"
    private const val RSASSA_PKCS1V15 = 1L
    private const val SHA256 = 1L

    /** DeviceAuthMessage { challenge: AuthChallenge { signature_algorithm, sender_nonce, hash_algorithm } }. */
    fun challenge(nonce: ByteArray): ByteArray {
        val challenge = Proto.Writer().apply {
            varint(1, RSASSA_PKCS1V15)
            bytes(2, nonce)
            varint(3, SHA256)
        }.toByteArray()
        return Proto.Writer().apply { bytes(1, challenge) }.toByteArray()
    }

    /**
     * Checks the device's AuthResponse against the TLS certificate of this very connection and returns the pin of the
     * device certificate (SHA-256 of its public key, hex). Throws [CertificateException] when anything does not match.
     */
    fun verify(deviceAuthMessage: ByteArray, peerCertificateDer: ByteArray): String {
        val message = Proto.read(deviceAuthMessage)
        if (message[3] != null) throw CertificateException("Device refused authentication")
        val response = Proto.read(
            message[2]?.firstOrNull() as? ByteArray ?: throw CertificateException("No auth response")
        )
        val signature = response[1]?.firstOrNull() as? ByteArray ?: throw CertificateException("No signature")
        val deviceCertificate = response[2]?.firstOrNull() as? ByteArray
            ?: throw CertificateException("No device certificate")
        if ((response[4]?.firstOrNull() as? Long ?: RSASSA_PKCS1V15) != RSASSA_PKCS1V15) {
            throw CertificateException("Unsupported signature algorithm")
        }
        val algorithm = if ((response[6]?.firstOrNull() as? Long ?: 0L) == SHA256) "SHA256withRSA" else "SHA1withRSA"
        val nonce = response[5]?.firstOrNull() as? ByteArray ?: ByteArray(0)
        val certificate = parseCertificate(deviceCertificate)
        val valid = Signature.getInstance(algorithm).run {
            initVerify(certificate.publicKey)
            update(nonce)
            update(peerCertificateDer)
            verify(signature)
        }
        if (!valid) throw CertificateException("Device signature does not match this connection")
        return pin(certificate)
    }

    fun pin(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
            .joinToString("") { "%02x".format(it) }

    fun parseCertificate(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

    /**
     * Accepts only a TLS certificate that is valid right now and, like Chromium requires, lives no longer than four
     * days; who signed it is proven afterwards by [verify]. Not a trust-all manager: everything else throws.
     */
    class TlsTrustManager(private val now: () -> Long = System::currentTimeMillis) : X509TrustManager {
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val certificate = chain?.firstOrNull() ?: throw CertificateException("No certificate")
            certificate.checkValidity(java.util.Date(now()))
            if (certificate.notAfter.time - now() > MAX_TLS_CERTIFICATE_LIFETIME_MS) {
                throw CertificateException("TLS certificate lives longer than a Cast device certificate may")
            }
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("Ordo does not accept client connections here")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private const val MAX_TLS_CERTIFICATE_LIFETIME_MS = 4L * 24 * 60 * 60 * 1000
}
