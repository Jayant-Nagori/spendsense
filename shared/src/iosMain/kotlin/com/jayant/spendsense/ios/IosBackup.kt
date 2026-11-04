package com.jayant.spendsense.ios

import com.jayant.spendsense.backup.BackupFormat
import com.jayant.spendsense.platform.BackupFiles
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeData
import platform.darwin.NSObject
import platform.posix.memcpy

private class BackupPickerDelegate(val onPick: (NSURL?) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        onPick(didPickDocumentsAtURLs.firstOrNull() as? NSURL)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = onPick(null)
}

private var backupDelegate: BackupPickerDelegate? = null

private fun topVc(): UIViewController? {
    var vc = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (vc?.presentedViewController != null) vc = vc.presentedViewController
    return vc
}

/** Backups through the iOS document picker: export (Save to Files) and open. The temp export copy is deleted after. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object IosBackupFiles : BackupFiles {
    override fun save(name: String, bytes: ByteArray, done: (Boolean) -> Unit) {
        val safe = name.filter { it.isLetterOrDigit() || it in "-_." }.ifEmpty { "SpendSense-backup.ssbk" }
        val url = NSURL.fileURLWithPath(NSTemporaryDirectory() + safe)
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        if (!data.writeToURL(url, atomically = true)) { done(false); return }
        val picker = UIDocumentPickerViewController(forExportingURLs = listOf(url), asCopy = true)
        val delegate = BackupPickerDelegate { picked ->
            backupDelegate = null
            NSFileManager.defaultManager.removeItemAtURL(url, null)
            done(picked != null)
        }
        backupDelegate = delegate
        picker.delegate = delegate
        topVc()?.presentViewController(picker, animated = true, completion = null) ?: run { backupDelegate = null; done(false) }
    }

    override fun open(done: (ByteArray?) -> Unit) {
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeData), asCopy = true)
        val delegate = BackupPickerDelegate { url ->
            backupDelegate = null
            done(url?.let(::read))
        }
        backupDelegate = delegate
        picker.delegate = delegate
        topVc()?.presentViewController(picker, animated = true, completion = null) ?: run { backupDelegate = null; done(null) }
    }

    private fun read(url: NSURL): ByteArray? {
        val scoped = url.startAccessingSecurityScopedResource()
        try {
            val data = NSData.dataWithContentsOfURL(url) ?: return null
            if (data.length.toLong() > BackupFormat.MAX_FILE_BYTES) return null
            val out = ByteArray(data.length.toInt())
            if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
            return out
        } finally {
            if (scoped) url.stopAccessingSecurityScopedResource()
        }
    }
}
