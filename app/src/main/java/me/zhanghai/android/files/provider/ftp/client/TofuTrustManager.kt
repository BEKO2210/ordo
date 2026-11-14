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
        // hostString: the name or address as connected, never a reverse DNS lookup.
        check(chain, authType, (socket?.remoteSocketAddress as? java.net.InetSocketAddress)?.hostString)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) {
        check(chain, authType, engine?.peerHost)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        check(chain, authType, null)
    }

    @Synchronized
    private fun check(chain: Array<X509Certificate>, authType: String, host: String?) {
        // Pinned per host, not per port: FTPS data connections use a new port every time.
        val known = load()
        val stored = host?.let { known.getProperty(it) }
        // A pinned self-signed certificate always wins: a CA certificate for some other name must not replace it.
        if (stored != null && stored != CA) {
            if (chain.isEmpty() || fingerprint(chain[0]) != stored) {
                throw CertificateException("Certificate of $host changed – possible man-in-the-middle")
            }
            return
        }
        if (isPublic(chain, authType)) {
            // Remember that this host had a real certificate, so it can never be swapped for a self-signed one.
            if (host != null && stored == null) {
                known.setProperty(host, CA)
                save(known)
            }
            return
        }
        if (host == null || chain.isEmpty()) {
            throw CertificateException("Untrusted certificate and no host to remember it for")
        }
        val fingerprint = fingerprint(chain[0])
        if (stored != null) {
            if (stored != fingerprint) {
                throw CertificateException("Certificate of $host changed – possible man-in-the-middle")
            }
            return
        }
        known.setProperty(host, fingerprint)
        save(known)
    }

    private fun save(known: Properties) {
        try {
            file.outputStream().use { known.store(it, null) }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private const val CA = "CA"

    /**
     * The host name check is skipped only for the exact self-signed certificate pinned for this host;
     * everything else (CA certificates included) gets the platform's host name verification.
     */
    override fun verify(hostname: String, session: SSLSession): Boolean {
        val stored = load().getProperty(hostname)
        if (stored != null && stored != CA) {
            val leaf = try {
                session.peerCertificates.firstOrNull() as? X509Certificate
            } catch (e: Exception) {
                null
            }
            return leaf != null && fingerprint(leaf) == stored
        }
        return HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)
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
