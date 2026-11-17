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
import java.security.interfaces.RSAPublicKey
import java.util.Date
import javax.net.ssl.X509TrustManager

/**
 * Proves that the other end is a real Cast device, the way Chromium's openscreen sender does it
 * (cast/sender/channel/cast_auth_util.cc and cast/common/certificate/boringssl_trust_store.cc, BSD-3-Clause; read
 * there and written anew here):
 *
 * - The TLS certificate is self-signed and lives at most four days (measured on a Chromecast with Google TV: 48 h),
 *   so TLS alone proves nothing; [TlsTrustManager] only checks its time and lifetime.
 * - After the handshake Ordo sends an AuthChallenge with a fresh 16-byte nonce. The device answers with its device
 *   certificate, intermediates and a signature over `nonce || DER(TLS certificate of this connection)`.
 * - [verify] checks the nonce, builds the chain from the device certificate through the intermediates to one of
 *   Google's Cast roots ([CastRoots]) with the same rules as openscreen (validity at the current time for everything
 *   but the root, CA bit and path length, key usage, RSA ≥ 2048 bit, no unknown critical extension) and checks the
 *   signature with the device certificate's key.
 *
 * Expired certificates are refused, without exceptions, as openscreen does. The intermediate of Chromecast 2nd gen
 * and Chromecast Audio that expired on 09.03.2025 is not specially allowed: Google fixed it with firmware
 * 1.56.467165 (docs/STREAMING-RECHERCHE.md), and a device without that update cannot be told apart from a forged one.
 * Revocation lists (optional in openscreen) are not checked.
 */
object CastDeviceAuth {
    const val NAMESPACE = "urn:x-cast:com.google.cast.tp.deviceauth"
    private const val RSASSA_PKCS1V15 = 1L
    private const val SHA256 = 1L
    private const val MIN_RSA_BITS = 2048
    private const val MAX_CHAIN = 6
    private const val BASIC_CONSTRAINTS = "2.5.29.19"
    private const val KEY_USAGE = "2.5.29.15"
    private const val NAME_CONSTRAINTS = "2.5.29.30"
    private val SIGNATURE_ALGORITHMS = setOf("SHA1withRSA", "SHA256withRSA", "SHA384withRSA", "SHA512withRSA")

    /** DeviceAuthMessage { challenge: AuthChallenge { signature_algorithm, sender_nonce, hash_algorithm } }. */
    fun challenge(nonce: ByteArray): ByteArray {
        val challenge = Proto.Writer().apply {
            varint(1, RSASSA_PKCS1V15)
            bytes(2, nonce)
            varint(3, SHA256)
        }.toByteArray()
        return Proto.Writer().apply { bytes(1, challenge) }.toByteArray()
    }

    /** The authenticated device: its certificate and the pin Ordo remembers for it. */
    class Device(val certificate: X509Certificate) {
        val pin: String = pin(certificate)
    }

    /**
     * Checks the device's reply to our [nonce] against the TLS certificate of this very connection.
     * Throws [CertificateException] when anything does not hold.
     */
    fun verify(
        deviceAuthMessage: ByteArray,
        nonce: ByteArray,
        peerCertificateDer: ByteArray,
        now: Date = Date(),
        roots: List<X509Certificate> = CastRoots.all
    ): Device {
        val message = Proto.read(deviceAuthMessage)
        if (message[3] != null) throw CertificateException("Device refused authentication")
        val response = Proto.read(
            message[2]?.firstOrNull() as? ByteArray ?: throw CertificateException("No auth response")
        )
        val signature = response[1]?.firstOrNull() as? ByteArray ?: throw CertificateException("No signature")
        if (signature.isEmpty()) throw CertificateException("Empty signature")
        val deviceCertificate = parseCertificate(
            response[2]?.firstOrNull() as? ByteArray ?: throw CertificateException("No device certificate")
        )
        val intermediates = response[3].orEmpty().map { parseCertificate(it as ByteArray) }
        if ((response[4]?.firstOrNull() as? Long ?: RSASSA_PKCS1V15) != RSASSA_PKCS1V15) {
            throw CertificateException("Unsupported signature algorithm")
        }
        val algorithm = if ((response[6]?.firstOrNull() as? Long ?: 0L) == SHA256) "SHA256withRSA" else "SHA1withRSA"
        val echoedNonce = response[5]?.firstOrNull() as? ByteArray ?: ByteArray(0)
        if (!MessageDigest.isEqual(echoedNonce, nonce)) throw CertificateException("Reply is not for our challenge")
        checkTlsCertificate(parseCertificate(peerCertificateDer), now.time)
        verifyChain(deviceCertificate, intermediates, now, roots)
        val valid = Signature.getInstance(algorithm).run {
            initVerify(deviceCertificate.publicKey)
            update(echoedNonce)
            update(peerCertificateDer)
            verify(signature)
        }
        if (!valid) throw CertificateException("Device signature does not match this connection")
        return Device(deviceCertificate)
    }

    /** Builds and checks a path from [target] through [intermediates] to one of [roots]; throws if none holds. */
    fun verifyChain(
        target: X509Certificate,
        intermediates: List<X509Certificate>,
        now: Date,
        roots: List<X509Certificate>
    ) {
        target.checkValidity(now)
        checkKey(target)
        if (target.keyUsage?.getOrNull(0) != true) throw CertificateException("Device certificate may not sign")
        var lastError: CertificateException = CertificateException("No path to a Cast root")
        fun search(path: List<X509Certificate>): Boolean {
            val head = path.last()
            for (root in roots) {
                if (root.subjectX500Principal != head.issuerX500Principal) continue
                try {
                    checkPath(path + root, now)
                    return true
                } catch (e: CertificateException) {
                    lastError = e
                }
            }
            if (path.size >= MAX_CHAIN) return false
            return intermediates.any { next ->
                next.subjectX500Principal == head.issuerX500Principal && next !in path && search(path + next)
            }
        }
        if (!search(listOf(target))) throw lastError
    }

    /** [path] runs from the device certificate to the root; mirrors openscreen's VerifyCertificateChain. */
    private fun checkPath(path: List<X509Certificate>, now: Date) {
        var maxPathLength = path.size - 2
        for (i in path.size - 1 downTo 1) {
            val issuer = path[i]
            val subject = path[i - 1]
            val isRoot = i == path.size - 1
            if (!isRoot) {
                issuer.checkValidity(now)
                if (issuer.subjectX500Principal != issuer.issuerX500Principal) {
                    if (maxPathLength == 0) throw CertificateException("Path too long")
                    maxPathLength--
                }
            }
            issuer.keyUsage?.let { if (it.getOrNull(5) != true) throw CertificateException("Issuer may not sign certificates") }
            val pathLength = issuer.basicConstraints
            if (pathLength < 0) throw CertificateException("Issuer is not a CA")
            if (pathLength < maxPathLength) maxPathLength = pathLength
            checkKey(issuer)
            // Name constraints are not evaluated here, so a certificate that carries them is not accepted.
            if (issuer.criticalExtensionOIDs.orEmpty().any { it != BASIC_CONSTRAINTS && it != KEY_USAGE } ||
                issuer.getExtensionValue(NAME_CONSTRAINTS) != null) {
                throw CertificateException("Unsupported certificate extension")
            }
            if (subject.sigAlgName !in SIGNATURE_ALGORITHMS) throw CertificateException("Unsupported signature")
            try {
                subject.verify(issuer.publicKey)
            } catch (e: Exception) {
                throw CertificateException("Certificate not signed by its issuer", e)
            }
        }
    }

    private fun checkKey(certificate: X509Certificate) {
        val key = certificate.publicKey as? RSAPublicKey ?: throw CertificateException("Not an RSA key")
        if (key.modulus.bitLength() < MIN_RSA_BITS) throw CertificateException("RSA key too short")
    }

    private fun checkTlsCertificate(certificate: X509Certificate, now: Long) {
        certificate.checkValidity(Date(now))
        if (certificate.notAfter.time - now > MAX_TLS_CERTIFICATE_LIFETIME_MS) {
            throw CertificateException("TLS certificate lives longer than a Cast device certificate may")
        }
    }

    fun pin(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
            .joinToString("") { "%02x".format(it) }

    fun parseCertificate(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

    /**
     * Accepts only a TLS certificate that is valid right now and lives no longer than four days, like Chromium;
     * who it belongs to is proven afterwards by [verify]. Not a trust-all manager: everything else throws.
     */
    class TlsTrustManager(private val now: () -> Long = System::currentTimeMillis) : X509TrustManager {
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            checkTlsCertificate(chain?.firstOrNull() ?: throw CertificateException("No certificate"), now())

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("Ordo does not accept client connections here")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /**
     * What Ordo knew about a device name and what it sees now. A name is chosen by whoever runs the device, so a
     * known name with another device ID or another device certificate needs the user's yes before Ordo connects.
     */
    enum class Known { NEW, SAME, CHANGED }

    fun compare(remembered: String?, deviceId: String, pin: String): Known = when (remembered) {
        null -> Known.NEW
        record(deviceId, pin) -> Known.SAME
        else -> Known.CHANGED
    }

    fun record(deviceId: String, pin: String) = "$deviceId $pin"

    private const val MAX_TLS_CERTIFICATE_LIFETIME_MS = 4L * 24 * 60 * 60 * 1000
}
