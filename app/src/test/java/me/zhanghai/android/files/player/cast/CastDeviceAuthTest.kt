/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player.cast

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
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
    private val deviceKeys = keys()
    private val deviceCertificate = certificate(deviceKeys, now - day, now + 3650 * day)
    private val tlsKeys = keys()
    private val tlsCertificate = certificate(tlsKeys, now - day / 2, now + day)
    private val nonce = ByteArray(16) { it.toByte() }

    private fun keys(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun certificate(keys: KeyPair, from: Long, until: Long): X509Certificate {
        val name = X500Name("CN=test")
        val holder = JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date(from), Date(until), name, keys.public)
            .build(JcaContentSignerBuilder("SHA256withRSA").build(keys.private))
        return JcaX509CertificateConverter().getCertificate(holder)
    }

    /** DeviceAuthMessage { response: AuthResponse { signature, client_auth_certificate, sender_nonce, hash } }. */
    private fun reply(signedBy: KeyPair = deviceKeys, signedCertificate: X509Certificate = tlsCertificate): ByteArray {
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(signedBy.private)
            update(nonce)
            update(signedCertificate.encoded)
            sign()
        }
        val response = Proto.Writer().apply {
            bytes(1, signature)
            bytes(2, deviceCertificate.encoded)
            varint(4, 1)
            bytes(5, nonce)
            varint(6, 1)
        }.toByteArray()
        return Proto.Writer().apply { bytes(2, response) }.toByteArray()
    }

    @Test
    fun challengeAsksForSha256WithTheNonce() {
        val challenge = Proto.read(Proto.read(CastDeviceAuth.challenge(nonce))[1]!!.single() as ByteArray)
        assertEquals(1L, challenge[1]!!.single())
        assertEquals(nonce.toList(), (challenge[2]!!.single() as ByteArray).toList())
        assertEquals(1L, challenge[3]!!.single())
    }

    @Test
    fun acceptsADeviceThatSignedThisConnection() {
        val pin = CastDeviceAuth.verify(reply(), tlsCertificate.encoded)
        assertEquals(CastDeviceAuth.pin(deviceCertificate), pin)
        assertEquals(64, pin.length)
        // A new TLS certificate a day later is signed by the same device certificate: the pin stays.
        val nextTls = certificate(keys(), now, now + 2 * day)
        assertEquals(pin, CastDeviceAuth.verify(reply(signedCertificate = nextTls), nextTls.encoded))
    }

    @Test
    fun refusesASignatureForAnotherConnection() {
        val otherTls = certificate(keys(), now, now + day)
        expectRefused { CastDeviceAuth.verify(reply(signedCertificate = otherTls), tlsCertificate.encoded) }
    }

    @Test
    fun refusesASignatureFromAnotherKey() {
        expectRefused { CastDeviceAuth.verify(reply(signedBy = keys()), tlsCertificate.encoded) }
    }

    @Test
    fun refusesAnAuthError() {
        val error = Proto.Writer().apply { bytes(3, Proto.Writer().apply { varint(1, 1) }.toByteArray()) }.toByteArray()
        expectRefused { CastDeviceAuth.verify(error, tlsCertificate.encoded) }
    }

    @Test
    fun anotherDeviceHasAnotherPin() {
        assertNotEquals(
            CastDeviceAuth.pin(deviceCertificate), CastDeviceAuth.pin(certificate(keys(), now, now + day))
        )
    }

    @Test
    fun tlsTrustManagerChecksTimeAndLifetime() {
        val manager = CastDeviceAuth.TlsTrustManager { now }
        manager.checkServerTrusted(arrayOf(tlsCertificate), "RSA")
        expectRefused { manager.checkServerTrusted(arrayOf(certificate(keys(), now - day, now + 30 * day)), "RSA") }
        expectRefused { manager.checkServerTrusted(arrayOf(certificate(keys(), now - 3 * day, now - day)), "RSA") }
        expectRefused { manager.checkServerTrusted(emptyArray(), "RSA") }
        expectRefused { manager.checkClientTrusted(arrayOf(tlsCertificate), "RSA") }
    }

    private fun expectRefused(block: () -> Unit) {
        try {
            block()
            fail("expected a CertificateException")
        } catch (_: CertificateException) {
        }
    }
}
