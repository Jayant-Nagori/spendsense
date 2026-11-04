package com.jayant.spendsense.backup

import com.jayant.spendsense.PlatformContext
import com.jayant.spendsense.currentTimeMillis
import com.jayant.spendsense.data.Settings
import com.jayant.spendsense.data.TransactionRepository
import com.jayant.spendsense.data.openDatabase
import com.jayant.spendsense.time.ZonedDateTime

/** Backup / restore entry points shared by Settings › Backup, the recovery screen and Android's weekly job. Blocking. */
object Backups {
    sealed interface Outcome {
        data class Restored(val result: BackupData.Restored) : Outcome
        data class Failed(val message: String) : Outcome
    }

    fun fileName(now: Long = currentTimeMillis()): String {
        val d = ZonedDateTime.ofEpochMilli(now, kotlinx.datetime.TimeZone.currentSystemDefault()).toLocalDate()
        return "SpendSense-backup-$d.ssbk"
    }

    /** Encrypted backup of everything except keys and tokens. */
    fun create(context: PlatformContext, passphrase: String): ByteArray {
        val payload = BackupData(openDatabase(context)).export(Settings(context).backupSnapshot(), appVersion(), currentTimeMillis())
        return BackupFormat.seal(payload, passphrase)
    }

    /** Decrypts without touching the database: used to check the passphrase before a recovery reset. */
    fun unlock(bytes: ByteArray, passphrase: String): Pair<String?, String?> = when (val o = BackupFormat.open(bytes, passphrase)) {
        is BackupFormat.Opened.Ok -> o.payload to null
        BackupFormat.Opened.WrongPassphraseOrTampered -> null to "Wrong passphrase, or the file was changed."
        BackupFormat.Opened.NotABackup -> null to "That isn't a SpendSense backup file."
        BackupFormat.Opened.TooNew -> null to "This backup was made by a newer SpendSense. Update the app first."
    }

    /** Merges a decrypted payload into the current database (duplicates skipped). */
    fun apply(context: PlatformContext, payload: String): Outcome = try {
        val settings = Settings(context)
        val r = BackupData(openDatabase(context)).import(payload) { settings.restoreSnapshot(it) }
        runCatching { TransactionRepository.get(context).notifyChanged() }
        Outcome.Restored(r)
    } catch (e: BackupData.BadBackup) {
        Outcome.Failed("The backup is damaged or incomplete.")
    } catch (e: OutOfMemoryError) {
        Outcome.Failed("The backup is too large to restore on this phone.")    } catch (e: Exception) {
        Outcome.Failed("Couldn't restore the backup.")
    }

    fun restore(context: PlatformContext, bytes: ByteArray, passphrase: String): Outcome {
        val (payload, err) = unlock(bytes, passphrase)
        return if (payload != null) apply(context, payload) else Outcome.Failed(err ?: "Couldn't open the backup.")
    }

    fun summary(r: BackupData.Restored): String =
        "Restored ${r.added} payment${if (r.added == 1) "" else "s"}" + (if (r.duplicates > 0) ", ${r.duplicates} already here" else "") + "." +
            (if (r.rules > 0) " Also added ${r.rules} \"Not a payment\" rule${if (r.rules == 1) "" else "s"}; review them in Settings › Splits." else "")

    private fun appVersion(): String = runCatching { com.jayant.spendsense.platform.Platform.services.appVersion }.getOrDefault("")
}
