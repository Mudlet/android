package org.mudlet.android.data

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Android's backup and transfer to a new phone, of what [BackupScope] says. The XML rules alone cannot keep the logs
 * out of a cloud backup: they name literal paths, and every profile has its own `log` folder.
 */
class MudletBackupAgent : BackupAgent() {
    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) = Unit

    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) = Unit

    override fun onFullBackup(data: FullBackupDataOutput) {
        val to = BackupScope.destination(data.transportFlags)
        if (to == null) {
            Log.i(TAG, "no backup: it would not be encrypted with the screen lock")
            return
        }
        val files = filesDir.canonicalFile
        // Static rules cannot name "the newest file", so the agent picks each profile's latest saves as it backs up
        val latest = HashMap<String, String?>()
        val latestIn = { folder: String ->
            if (folder !in latest) latest[folder] = BackupScope.latestSave(File(files, folder))
            latest[folder]
        }
        files.walkTopDown()
            .onEnter { !Files.isSymbolicLink(it.toPath()) }
            .onFail { _, e -> Log.w(TAG, "a folder of the app's files could not be read for the backup", e) }
            .filter { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
            .filter { BackupScope.takes(it.relativeTo(files).invariantSeparatorsPath, BackupScope.Destination.NEW_PHONE, latestIn) }
            .forEach { fullBackupFile(it, data) }
        preferencesDir().listFiles().orEmpty()
            .filter { it.isFile && BackupScope.takesPreferences(it.name) }
            .forEach { fullBackupFile(it, data) }
    }

    override fun onQuotaExceeded(backupDataBytes: Long, quotaBytes: Long) {
        Log.w(TAG, "no cloud backup: the app's data is $backupDataBytes bytes, more than the $quotaBytes allowed")
    }

    override fun onRestoreFile(data: ParcelFileDescriptor, size: Long, destination: File, type: Int, mode: Long, mtime: Long) {
        if (kept(destination.canonicalFile)) {
            super.onRestoreFile(data, size, destination, type, mode, mtime)
        } else {
            Log.w(TAG, "a restore brought a file the app does not take back; it is left out")
            skip(data, size)
        }
    }

    private fun kept(file: File): Boolean {
        val files = filesDir.canonicalFile
        if (file.startsWith(files)) return BackupScope.restores(file.relativeTo(files).invariantSeparatorsPath)
        return file.parentFile == preferencesDir().canonicalFile && BackupScope.takesPreferences(file.name)
    }

    // Context.getSharedPreferencesPath() is hidden; this is where it points
    private fun preferencesDir() = File(applicationInfo.dataDir, "shared_prefs")

    // The pipe holds the refused file's bytes before the next file's, and is not this agent's to close
    private fun skip(data: ParcelFileDescriptor, size: Long) {
        val input = FileInputStream(data.fileDescriptor)
        val buffer = ByteArray(BUFFER_BYTES)
        var left = size
        while (left > 0) {
            val read = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            if (read < 0) throw IOException("the restore ended $left bytes into a file it was skipping")
            left -= read
        }
    }

    private companion object {
        const val TAG = "MudletBackup"
        const val BUFFER_BYTES = 64 * 1024
    }
}
