/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.provider.ftp.client

import android.util.Base64
import me.zhanghai.android.files.app.application
import java.io.File
import java.io.IOException
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Properties
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * FTPS trust: certificates from a public CA are checked like in a browser (chain and host name). A
 * self-signed certificate, common on home NAS boxes, is remembered on first use per host and port; a
 * different one later refuses the connection.
 */
object TofuTrustManager : X509ExtendedTrustManager(), HostnameVerifier {
    private val system: X509TrustManager by lazy {
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private val file: File
        get() = File(application.noBackupFilesDir, "ftps_known_certificates.properties")

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) {
        check(chain, authType, socket?.inetAddress?.hostName, socket?.port)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) {
        check(chain, authType, engine?.peerHost, engine?.peerPort)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        check(chain, authType, null, null)
    }

    @Synchronized
    private fun check(chain: Array<X509Certificate>, authType: String, host: String?, port: Int?) {
        if (isPublic(chain, authType)) {
            return
        }
        if (host == null || chain.isEmpty()) {
            throw CertificateException("Untrusted certificate and no host to remember it for")
        }
        val id = "$host:$port"
        val fingerprint = fingerprint(chain[0])
        val known = load()
        val stored = known.getProperty(id)
        if (stored != null) {
            if (stored != fingerprint) {
                throw CertificateException("Certificate of $id changed – possible man-in-the-middle")
            }
            return
        }
        known.setProperty(id, fingerprint)
        try {
            file.outputStream().use { known.store(it, null) }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    /** Host names are checked for CA certificates; a remembered self-signed one stands for its host. */
    override fun verify(hostname: String, session: SSLSession): Boolean {
        val chain = try {
            session.peerCertificates.filterIsInstance<X509Certificate>().toTypedArray()
        } catch (e: Exception) {
            return false
        }
        return if (isPublic(chain, "RSA") || isPublic(chain, "ECDHE_ECDSA")) {
            HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)
        } else {
            true
        }
    }

    private fun isPublic(chain: Array<X509Certificate>, authType: String): Boolean =
        try {
            system.checkServerTrusted(chain, authType)
            true
        } catch (e: CertificateException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        throw CertificateException("Client certificates are not used")

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        throw CertificateException("Client certificates are not used")

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        throw CertificateException("Client certificates are not used")

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

    private fun load(): Properties = Properties().apply {
        if (file.exists()) {
            try {
                file.inputStream().use { load(it) }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private fun fingerprint(certificate: X509Certificate): String =
        "SHA256:" + Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded), Base64.NO_WRAP or Base64.NO_PADDING
        )
}
