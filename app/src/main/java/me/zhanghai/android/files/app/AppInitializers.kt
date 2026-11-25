/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.app

import android.content.Context
import android.os.AsyncTask
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.MimeTypeMap
import android.webkit.WebView
import jcifs.context.SingletonContext
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.coil.initializeCoil
import me.zhanghai.android.files.compat.PreferenceManagerCompat
import me.zhanghai.android.files.filejob.fileJobNotificationTemplate
import me.zhanghai.android.files.ftpserver.ftpServerServiceNotificationTemplate
import me.zhanghai.android.files.hiddenapi.HiddenApi
import me.zhanghai.android.files.provider.FileSystemProviders
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.storage.FtpServerAuthenticator
import me.zhanghai.android.files.storage.SftpServerAuthenticator
import me.zhanghai.android.files.storage.SmbServerAuthenticator
import me.zhanghai.android.files.storage.StorageVolumeListLiveData
import me.zhanghai.android.files.storage.WebDavServerAuthenticator
import me.zhanghai.android.files.theme.custom.CustomThemeHelper
import me.zhanghai.android.files.theme.night.NightModeHelper
import java.io.File
import java.util.Properties
import me.zhanghai.android.files.trash.Trash
import me.zhanghai.android.files.vault.Vault
import me.zhanghai.android.files.provider.ftp.client.Client as FtpClient
import me.zhanghai.android.files.launcher.LauncherIcons
import me.zhanghai.android.files.launcher.LauncherShortcuts
import me.zhanghai.android.files.provider.sftp.client.Client as SftpClient
import me.zhanghai.android.files.provider.smb.client.Client as SmbClient
import me.zhanghai.android.files.provider.webdav.client.Client as WebDavClient

val appInitializers = listOf(
    ::preloadStartPreferences,
    ::disableHiddenApiChecks,
    ::initializeWebViewDebugging,
    ::initializeCoil,
    ::initializeFileSystemProviders,
    ::upgradeApp,
    ::initializeLiveDataObjects,
    ::initializeCustomTheme,
    ::initializeNightMode,
    ::createNotificationChannels,
    ::purgeExpiredTrash,
    ::cleanUpVault,
    ::reconcileLauncherIcon
)

// Activities and services read their preferences files first in onCreate (onboarding flag, playback queue, the video
// player's stores): loading them here keeps those disk reads off the main thread. They are read through the
// application context, whose file lookup this caches.
// shortcut: loads every preferences file (each a few KB on 25.11.2026); skips one above 64 KB should a cache grow.
private fun preloadStartPreferences() {
    Thread({
        val names = File(application.applicationInfo.dataDir, "shared_prefs").listFiles()
            ?.filter { it.name.endsWith(".xml") && it.length() < 64 * 1024 }?.map { it.name.removeSuffix(".xml") }
            .orEmpty() +
            // PathSettings' file, read by every file list, exists only once a folder got its own view.
            "${PreferenceManagerCompat.getDefaultSharedPreferencesName(application)}_path"
        names.distinct().forEach { application.getSharedPreferences(it, Context.MODE_PRIVATE).all }
        // The platform's MIME table is read from disk on its first use, often a file list on the main thread.
        MimeTypeMap.getSingleton().getMimeTypeFromExtension("txt")
    }, "PreloadPreferences").start()
}

private fun disableHiddenApiChecks() {
    HiddenApi.disableHiddenApiChecks()
}

private fun initializeWebViewDebugging() {
    if (BuildConfig.DEBUG) {
        WebView.setWebContentsDebuggingEnabled(true)
    }
}

private fun initializeFileSystemProviders() {
    FileSystemProviders.install()
    FileSystemProviders.overflowWatchEvents = true
    // SingletonContext.init() calls NameServiceClientImpl.initCache() which connects to network.
    AsyncTask.THREAD_POOL_EXECUTOR.execute {
        SingletonContext.init(
            Properties().apply {
                setProperty("jcifs.netbios.cachePolicy", "0")
                setProperty("jcifs.smb.client.maxVersion", "SMB1")
            }
        )
    }
    FtpClient.authenticator = FtpServerAuthenticator
    SftpClient.authenticator = SftpServerAuthenticator
    SmbClient.authenticator = SmbServerAuthenticator
    WebDavClient.authenticator = WebDavServerAuthenticator
}

private fun initializeLiveDataObjects() {
    // Force initialization of LiveData objects so that it won't happen on a background thread.
    StorageVolumeListLiveData.value
    Settings.FILE_LIST_DEFAULT_DIRECTORY.value
}

private fun initializeCustomTheme() {
    CustomThemeHelper.initialize(application)
}

private fun initializeNightMode() {
    NightModeHelper.initialize(application)
}

private fun createNotificationChannels() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        notificationManager.createNotificationChannels(
            listOf(
                backgroundActivityStartNotificationTemplate.channelTemplate,
                fileJobNotificationTemplate.channelTemplate,
                ftpServerServiceNotificationTemplate.channelTemplate
            ).map { it.create(application) }
        )
    }
}

private fun purgeExpiredTrash() {
    Thread({
        // A background purge must never take the app down.
        try {
            Trash.purgeExpired()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }, "TrashPurge").start()
}

private fun cleanUpVault() {
    Thread({
        try {
            Vault.cleanUp()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }, "VaultCleanUp").start()
}

// Posted: this runs from a ContentProvider, possibly before androidx.startup has attached ProcessLifecycleOwner.
private fun reconcileLauncherIcon() {
    Handler(Looper.getMainLooper()).post {
        LauncherIcons.reconcile(application)
        LauncherShortcuts.publish(application)
    }
}
