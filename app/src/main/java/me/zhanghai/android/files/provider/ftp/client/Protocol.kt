/*
 * Copyright (c) 2022 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.ftp.client

import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPSClient

enum class Protocol(val scheme: String, val defaultPort: Int, val createClient: () -> FTPClient) {
    FTP("ftp", FTPClient.DEFAULT_PORT, ::FTPClient),
    FTPS("ftps", FTPSClient.DEFAULT_FTPS_PORT, { secure(FTPSClient(true)) }),
    FTPES("ftpes", FTPClient.DEFAULT_PORT, { secure(FTPSClient(false)) });

    companion object {
        val SCHEMES = entries.map { it.scheme }

        // commons-net only checks certificate dates by default; see TofuTrustManager.
        private fun secure(client: FTPSClient): FTPSClient = client.apply {
            trustManager = TofuTrustManager
            hostnameVerifier = TofuTrustManager
        }

        fun fromScheme(scheme: String): Protocol =
            entries.firstOrNull { it.scheme == scheme } ?: throw IllegalArgumentException(scheme)
    }
}
