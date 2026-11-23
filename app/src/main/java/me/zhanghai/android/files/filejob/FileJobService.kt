/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.DocumentsContract
import androidx.annotation.MainThread
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java8.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.NotificationIds
import me.zhanghai.android.files.backup.BackupStatus
import me.zhanghai.android.files.backup.BackupStatusLiveData
import me.zhanghai.android.files.backup.CutBackup
import me.zhanghai.android.files.provider.sftp.client.Client as SftpClient
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.home.HomeActivity
import me.zhanghai.android.files.provider.common.PosixFileModeBit
import me.zhanghai.android.files.provider.common.PosixGroup
import me.zhanghai.android.files.provider.common.PosixUser
import me.zhanghai.android.files.trash.TrashEntry
import me.zhanghai.android.files.vault.VaultEntry
import me.zhanghai.android.files.util.ForegroundNotificationManager
import me.zhanghai.android.files.util.WakeWifiLock
import me.zhanghai.android.files.util.removeFirst
import java.util.concurrent.Executors
import java.util.concurrent.Future
import me.zhanghai.android.files.compat.removeFirstCompat

class FileJobService : Service() {
    private lateinit var wakeWifiLock: WakeWifiLock

    internal lateinit var notificationManager: ForegroundNotificationManager
        private set

    private val executorService = Executors.newCachedThreadPool()

    private val runningJobs = mutableMapOf<FileJob, Future<*>>()

    private val jobThreads = ConcurrentHashMap<Int, Thread>()

    override fun onCreate() {
        super.onCreate()

        wakeWifiLock = WakeWifiLock(FileJobService::class.java.simpleName)
        notificationManager = ForegroundNotificationManager(this)
        instance = this
        deleteCutBackup()

        while (pendingJobs.isNotEmpty()) {
            startJob(pendingJobs.removeFirstCompat())
        }
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    /** Read by backup and restore, so a job stopped by the time limit shows as failed, not cancelled. */
    @Volatile
    internal var isTimedOut = false
        private set

    // API 35+: dataSync may run 6 h per 24 h in the background; the service must stop within seconds or
    // the app crashes. onDestroy cancels the running jobs, the notification tells the user.
    override fun onTimeout(startId: Int, fgsType: Int) {
        isTimedOut = true
        notifyTimedOut()
        stopSelf()
    }

    @SuppressLint("MissingPermission")
    private fun notifyTimedOut() {
        val message = getString(R.string.file_job_timeout_message)
        val notification = fileJobNotificationTemplate.createBuilder(this)
            .setOngoing(false)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentTitle(getString(R.string.file_job_timeout_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, HomeActivity::class.java), PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        NotificationManagerCompat.from(this).notify(NotificationIds.FILE_JOB_TIMEOUT, notification)
    }

    private val jobCount: Int
        get() = synchronized(runningJobs) { runningJobs.size }

    private fun startJob(job: FileJob) {
        // Synchronize on runningJobs to prevent a job from removing itself before being added.
        synchronized(runningJobs) {
            val future = executorService.submit {
                val thread = Thread.currentThread()
                jobThreads[job.id] = thread
                try {
                    job.runOn(this)
                } finally {
                    jobThreads.remove(job.id, thread)
                    SftpClient.releaseThread(thread)
                    synchronized(runningJobs) {
                        runningJobs.remove(job)
                        updateWakeWifiLockLocked()
                    }
                }
            }
            runningJobs[job] = future
            updateWakeWifiLockLocked()
        }
    }

    private fun cancelJob(id: Int) {
        synchronized(runningJobs) {
            runningJobs.removeFirst { it.key.id == id }?.value?.cancel(true)
            updateWakeWifiLockLocked()
        }
        jobThreads[id]?.let { SftpClient.disconnectThread(it) }
    }

    override fun onDestroy() {
        super.onDestroy()

        instance = null

        synchronized(runningJobs) {
            while (runningJobs.isNotEmpty()) {
                val job = runningJobs.keys.first()
                runningJobs.remove(job)?.cancel(true)
                jobThreads[job.id]?.let { SftpClient.disconnectThread(it) }
            }
            updateWakeWifiLockLocked()
        }
    }

    private fun deleteCutBackup() {
        val marked = CutBackup.peek(filesDir) ?: return
        try {
            DocumentsContract.deleteDocument(contentResolver, Uri.parse(marked))
        } catch (e: Exception) {
            e.printStackTrace()
            return
        }
        CutBackup.clear(filesDir)
    }

    // Synchronize on runningJobs to avoid the potential race condition that the lock is
    // acquired after all jobs are finished in a very short time.
    private fun updateWakeWifiLockLocked() {
        wakeWifiLock.isAcquired = jobCount > 0
    }

    companion object {
        private var instance: FileJobService? = null

        private val pendingJobs = mutableListOf<FileJob>()

        val runningJobCount: Int
            @MainThread
            get() = instance?.jobCount ?: 0

        @MainThread
        private fun startJob(job: FileJob, context: Context) {
            val instance = instance
            if (instance != null) {
                instance.startJob(job)
            } else {
                pendingJobs.add(job)
                context.startService(Intent(context, FileJobService::class.java))
            }
        }

        fun archive(
            sources: List<Path>,
            archiveFile: Path,
            format: Int,
            filter: Int,
            password: String?,
            context: Context
        ) {
            startJob(ArchiveFileJob(sources, archiveFile, format, filter, password), context)
        }

        fun copy(sources: List<Path>, targetDirectory: Path, context: Context) {
            startJob(CopyFileJob(sources, targetDirectory), context)
        }

        fun create(path: Path, createDirectory: Boolean, context: Context) {
            startJob(CreateFileJob(path, createDirectory), context)
        }

        fun delete(paths: List<Path>, context: Context, permanently: Boolean = false) {
            startJob(DeleteFileJob(paths, permanently), context)
        }

        fun restoreFromTrash(entries: List<TrashEntry>, context: Context) {
            startJob(RestoreTrashJob(entries), context)
        }

        fun moveToVault(paths: List<Path>, context: Context) {
            startJob(MoveToVaultJob(paths), context)
        }

        fun restoreFromVault(entries: List<VaultEntry>, context: Context) {
            startJob(RestoreVaultJob(entries), context)
        }

        @MainThread
        fun backup(
            folders: List<Path>,
            includeVault: Boolean,
            target: Uri,
            password: String?,
            context: Context
        ) {
            val job = BackupJob(folders, includeVault, target, password)
            // Set here, so the backup screen switches at once and knows which job to cancel.
            BackupStatusLiveData.value = BackupStatus.Running(job.id, -1, null)
            startJob(job, context)
        }

        @MainThread
        fun inspectBackup(source: Uri, password: String?, context: Context) {
            val job = RestorePreviewJob(source, password)
            BackupStatusLiveData.value = BackupStatus.RestoreChecking(job.id, source)
            startJob(job, context)
        }

        @MainThread
        fun restoreBackup(
            source: Uri,
            password: String?,
            fileCount: Int,
            size: Long,
            vaultFileCount: Int,
            context: Context
        ) {
            val job = RestoreBackupJob(source, password, fileCount, size, vaultFileCount)
            BackupStatusLiveData.value = BackupStatus.RestoreRunning(job.id, 0, null)
            startJob(job, context)
        }

        fun move(sources: List<Path>, targetDirectory: Path, context: Context) {
            startJob(MoveFileJob(sources, targetDirectory), context)
        }

        fun installApk(file: Path, context: Context) {
            startJob(InstallApkJob(file), context)
        }

        fun open(file: Path, mimeType: MimeType, withChooser: Boolean, context: Context) {
            startJob(OpenFileJob(file, mimeType, withChooser), context)
        }

        fun rename(path: Path, newName: String, context: Context) {
            startJob(RenameFileJob(path, newName), context)
        }

        fun restoreSeLinuxContext(path: Path, recursive: Boolean, context: Context) {
            startJob(RestoreFileSeLinuxContextJob(path, recursive), context)
        }

        fun save(source: Path, target: Path, context: Context) {
            startJob(SaveFileJob(source, target), context)
        }

        fun setGroup(path: Path, group: PosixGroup, recursive: Boolean, context: Context) {
            startJob(SetFileGroupJob(path, group, recursive), context)
        }

        fun setMode(
            path: Path,
            mode: Set<PosixFileModeBit>,
            recursive: Boolean,
            uppercaseX: Boolean,
            context: Context
        ) {
            startJob(SetFileModeJob(path, mode, recursive, uppercaseX), context)
        }

        fun setOwner(path: Path, owner: PosixUser, recursive: Boolean, context: Context) {
            startJob(SetFileOwnerJob(path, owner, recursive), context)
        }

        fun setSeLinuxContext(
            path: Path,
            seLinuxContext: String,
            recursive: Boolean,
            context: Context
        ) {
            startJob(SetFileSeLinuxContextJob(path, seLinuxContext, recursive), context)
        }

        fun write(
            file: Path,
            content: ByteArray,
            context: Context,
            listener: ((Boolean) -> Unit)?
        ) {
            startJob(WriteFileJob(file, content, listener), context)
        }

        @MainThread
        fun cancelJob(id: Int) {
            pendingJobs.removeFirst { it.id == id }
            instance?.cancelJob(id)
        }
    }
}
