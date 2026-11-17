/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player.cast

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test

class CastDeviceAuthTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = System.currentTimeMillis()
    private var serial = 1L

    private class Issued(val keys: KeyPair, val certificate: X509Certificate)

    private fun keys(bits: Int = 2048): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair()

    private fun issue(
        name: String,
        issuer: Issued?,
        ca: Boolean,
        from: Long = now - day,
        until: Long = now + 3650 * day,
        keys: KeyPair = keys(),
        usage: Int? = if (ca) KeyUsage.keyCertSign or KeyUsage.cRLSign else KeyUsage.digitalSignature
    ): Issued {
        val subject = X500Name("CN=$name")
        val builder = JcaX509v3CertificateBuilder(
            issuer?.let { X500Name(it.certificate.subjectX500Principal.name) } ?: subject,
            BigInteger.valueOf(serial++), Date(from), Date(until), subject, keys.public
        )
        if (ca) builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        usage?.let { builder.addExtension(Extension.keyUsage, true, KeyUsage(it)) }
        val signer = JcaContentSignerBuilder("SHA256withRSA").build((issuer?.keys ?: keys).private)
        return Issued(keys, JcaX509CertificateConverter().getCertificate(builder.build(signer)))
    }

    private val root = issue("Test Cast Root", null, ca = true)
    private val intermediate = issue("Test Cast Intermediate", root, ca = true)
    private val device = issue("Test Device", intermediate, ca = false)
    private val tls = issue("tls", null, ca = false, from = now - day / 2, until = now + day, usage = null)
    private val nonce = ByteArray(16) { it.toByte() }

    /** DeviceAuthMessage { response: AuthResponse { signature, client_auth_certificate, intermediates, nonce, hash } }. */
    private fun reply(
        signer: Issued = device,
        signed: X509Certificate = tls.certificate,
        intermediates: List<X509Certificate> = listOf(intermediate.certificate),
        echoedNonce: ByteArray = nonce
    ): ByteArray {
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(signer.keys.private)
            update(echoedNonce)
            update(signed.encoded)
            sign()
        }
        val response = Proto.Writer().apply {
            bytes(1, signature)
            bytes(2, device.certificate.encoded)
            intermediates.forEach { bytes(3, it.encoded) }
            varint(4, 1)
            bytes(5, echoedNonce)
            varint(6, 1)
        }.toByteArray()
        return Proto.Writer().apply { bytes(2, response) }.toByteArray()
    }

    private fun verify(reply: ByteArray = reply(), peer: X509Certificate = tls.certificate, at: Long = now) =
        CastDeviceAuth.verify(reply, nonce, peer.encoded, Date(at), listOf(root.certificate))

    private fun expectRefused(block: () -> Unit) {
        try {
            block()
            fail("expected a CertificateException")
        } catch (_: CertificateException) {
        }
    }

    @Test
    fun challengeAsksForSha256WithTheNonce() {
        val challenge = Proto.read(Proto.read(CastDeviceAuth.challenge(nonce))[1]!!.single() as ByteArray)
        assertEquals(1L, challenge[1]!!.single())
        assertEquals(nonce.toList(), (challenge[2]!!.single() as ByteArray).toList())
        assertEquals(1L, challenge[3]!!.single())
    }

    @Test
    fun acceptsAChainToTheRootThatSignedThisConnection() {
        val result = verify()
        assertEquals(CastDeviceAuth.pin(device.certificate), result.pin)
        assertEquals(64, result.pin.length)
        // Two days later the TLS certificate is new; the same device certificate signs it and the pin stays.
        val nextTls = issue("tls2", null, ca = false, from = now, until = now + 2 * day, usage = null)
        assertEquals(result.pin, verify(reply(signed = nextTls.certificate), nextTls.certificate).pin)
    }

    @Test
    fun refusesASignatureOverAnotherTlsCertificate() {
        val other = issue("other", null, ca = false, from = now, until = now + day, usage = null)
        expectRefused { verify(reply(signed = other.certificate)) }
    }

    @Test
    fun refusesASignatureByAnotherKey() {
        expectRefused { verify(reply(signer = issue("forger", intermediate, ca = false))) }
    }

    @Test
    fun refusesAReplyToAnotherChallenge() {
        expectRefused { verify(reply(echoedNonce = ByteArray(16) { 9 })) }
    }

    @Test
    fun refusesAnUnknownRoot() {
        expectRefused {
            CastDeviceAuth.verify(reply(), nonce, tls.certificate.encoded, Date(now), listOf(issue("Other Root", null, ca = true).certificate))
        }
    }

    @Test
    fun refusesAMissingIntermediate() {
        expectRefused { verify(reply(intermediates = emptyList())) }
    }

    @Test
    fun refusesExpiredCertificates() {
        val expiredDevice = issue("Expired Device", intermediate, ca = false, until = now - 1)
        expectRefused {
            CastDeviceAuth.verifyChain(expiredDevice.certificate, listOf(intermediate.certificate), Date(now), listOf(root.certificate))
        }
        val expiredIntermediate = issue("Old Intermediate", root, ca = true, until = now - 1)
        val chain = listOf(expiredIntermediate.certificate)
        expectRefused {
            CastDeviceAuth.verifyChain(
                issue("Device", expiredIntermediate, ca = false).certificate, chain, Date(now), listOf(root.certificate)
            )
        }
    }

    @Test
    fun refusesAnIntermediateThatIsNoCa() {
        val notCa = issue("Not a CA", root, ca = false, usage = KeyUsage.keyCertSign)
        expectRefused {
            CastDeviceAuth.verifyChain(
                issue("Device", notCa, ca = false).certificate, listOf(notCa.certificate), Date(now), listOf(root.certificate)
            )
        }
    }

    @Test
    fun refusesADeviceCertificateThatMayNotSign() {
        val noSigning = issue("Device", intermediate, ca = false, usage = KeyUsage.keyEncipherment)
        expectRefused {
            CastDeviceAuth.verifyChain(noSigning.certificate, listOf(intermediate.certificate), Date(now), listOf(root.certificate))
        }
    }

    @Test
    fun refusesShortKeys() {
        val weak = issue("Device", intermediate, ca = false, keys = keys(1024))
        expectRefused {
            CastDeviceAuth.verifyChain(weak.certificate, listOf(intermediate.certificate), Date(now), listOf(root.certificate))
        }
    }

    @Test
    fun refusesAnAuthError() {
        val error = Proto.Writer().apply { bytes(3, Proto.Writer().apply { varint(1, 1) }.toByteArray()) }.toByteArray()
        expectRefused { verify(error) }
    }

    @Test
    fun anotherDeviceHasAnotherPin() {
        assertNotEquals(CastDeviceAuth.pin(device.certificate), CastDeviceAuth.pin(issue("Other", intermediate, ca = false).certificate))
    }

    @Test
    fun embeddedRootsAreChromiumsCastRoots() {
        val fingerprints = CastRoots.all.map { root ->
            MessageDigest.getInstance("SHA-256").digest(root.encoded).joinToString("") { "%02X".format(it) }
        }
        assertEquals(
            listOf(
                "809AF14700B3FE2611AD597EB1584D6354313B64CCDB3390F097FA3E5826D6CA",
                "CAF6D1E37B532203E01A76BF07187BB731CCD38801565AB2211A2C0AB7F3BC46"
            ),
            fingerprints
        )
    }

    @Test
    fun knownNamesWithAnotherDeviceNeedAYes() {
        val record = CastDeviceAuth.record("id-1", "pin-1")
        assertEquals(CastDeviceAuth.Known.NEW, CastDeviceAuth.compare(null, "id-1", "pin-1"))
        assertEquals(CastDeviceAuth.Known.SAME, CastDeviceAuth.compare(record, "id-1", "pin-1"))
        assertEquals(CastDeviceAuth.Known.CHANGED, CastDeviceAuth.compare(record, "id-2", "pin-1"))
        assertEquals(CastDeviceAuth.Known.CHANGED, CastDeviceAuth.compare(record, "id-1", "pin-2"))
    }

    @Test
    fun tlsTrustManagerChecksTimeAndLifetime() {
        val manager = CastDeviceAuth.TlsTrustManager { now }
        manager.checkServerTrusted(arrayOf(tls.certificate), "RSA")
        expectRefused { manager.checkServerTrusted(arrayOf(issue("long", null, false, now - day, now + 30 * day).certificate), "RSA") }
        expectRefused { manager.checkServerTrusted(arrayOf(issue("old", null, false, now - 3 * day, now - day).certificate), "RSA") }
        expectRefused { manager.checkServerTrusted(emptyArray(), "RSA") }
        expectRefused { manager.checkClientTrusted(arrayOf(tls.certificate), "RSA") }
    }
}
