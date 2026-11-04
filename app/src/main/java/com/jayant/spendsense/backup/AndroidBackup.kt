package com.jayant.spendsense.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.jayant.spendsense.data.keyValueStore
import com.jayant.spendsense.data.secretStore
import com.jayant.spendsense.platform.BackupFiles
import com.jayant.spendsense.security.AppIntegrity
import com.jayant.spendsense.ui.OnOffToggle
import com.jayant.spendsense.ui.SettingsGroup
import com.jayant.spendsense.ui.SettingsRow
import com.jayant.spendsense.ui.SquareField
import com.jayant.spendsense.ui.components.GhostButton
import com.jayant.spendsense.ui.components.PrimaryButton
import com.jayant.spendsense.ui.components.formatDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Storage Access Framework save / open for backup files. */
@Composable
fun rememberAndroidBackupFiles(): BackupFiles {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingSave by remember { mutableStateOf<Pair<ByteArray, (Boolean) -> Unit>?>(null) }
    var pendingOpen by remember { mutableStateOf<((ByteArray?) -> Unit)?>(null) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val (bytes, done) = pendingSave ?: return@rememberLauncherForActivityResult
        pendingSave = null
        if (uri == null) { done(false); return@rememberLauncherForActivityResult }
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) } }.isSuccess
            }
            done(ok)
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val done = pendingOpen ?: return@rememberLauncherForActivityResult
        pendingOpen = null
        if (uri == null) { done(null); return@rememberLauncherForActivityResult }
        scope.launch { done(withContext(Dispatchers.IO) { AutoBackup.readCapped(context, uri) }) }
    }
    return remember {
        object : BackupFiles {
            override fun save(name: String, bytes: ByteArray, done: (Boolean) -> Unit) {
                pendingSave = bytes to done
                runCatching { create.launch(name) }.onFailure { pendingSave = null; done(false) }
            }

            override fun open(done: (ByteArray?) -> Unit) {
                pendingOpen = done
                runCatching { open.launch(arrayOf("*/*")) }.onFailure { pendingOpen = null; done(null) }
            }
        }
    }
}

/**
 * "Auto-backup weekly to a folder I pick": a persisted SAF tree URI, a passphrase the user sets (kept only in the
 * Keystore-wrapped secret store), and a weekly WorkManager job. Keeps the 5 newest backups in that folder.
 */
object AutoBackup {
    private const val PREFS = "backup"
    private const val KEY_TREE = "tree_uri"
    private const val KEY_ON = "auto_on"
    private const val KEY_LAST = "last_auto"
    private const val KEY_LAST_OK = "last_auto_ok"
    private const val SECRET_PASS = "auto_passphrase"
    private const val WORK = "auto-backup"
    private const val KEEP = 5
    /** Exactly our own names (a real date, optional provider " (n)" suffix): anything else in the folder is left alone. */
    private val OUR_NAME = Regex("SpendSense-backup-20\\d\\d-[01]\\d-[0-3]\\d(?: \\(\\d{1,3}\\))?\\.ssbk")

    private fun prefs(c: Context) = keyValueStore(c, PREFS)
    private fun secrets(c: Context) = secretStore(c, PREFS)

    fun enabled(c: Context) = prefs(c).getBoolean(KEY_ON, false)
    fun folder(c: Context): Uri? = prefs(c).getString(KEY_TREE, null)?.let(Uri::parse)
    fun lastRun(c: Context): Pair<Long, Boolean>? = prefs(c).getLong(KEY_LAST, 0L).takeIf { it > 0 }?.let { it to prefs(c).getBoolean(KEY_LAST_OK, false) }

    fun readCapped(c: Context, uri: Uri): ByteArray? = runCatching {
        c.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > BackupFormat.MAX_FILE_BYTES) return@runCatching null
            }
            out.toByteArray()
        }
    }.getOrNull()

    fun enable(c: Context, tree: Uri, passphrase: String) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        c.contentResolver.takePersistableUriPermission(tree, flags)
        secrets(c).write(SECRET_PASS, passphrase)
        prefs(c).putString(KEY_TREE, tree.toString())
        prefs(c).putBoolean(KEY_ON, true)
        schedule(c)
        runNow(c)
    }

    fun disable(c: Context) {
        prefs(c).putBoolean(KEY_ON, false)
        secrets(c).write(SECRET_PASS, null)
        folder(c)?.let { uri ->
            runCatching { c.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
        prefs(c).remove(KEY_TREE)
        WorkManager.getInstance(c).cancelUniqueWork(WORK)
    }

    private fun constraints() = Constraints.Builder().setRequiresBatteryNotLow(true).build()

    fun schedule(c: Context) {
        val req = PeriodicWorkRequestBuilder<AutoBackupWorker>(7, TimeUnit.DAYS).setConstraints(constraints()).build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun runNow(c: Context) {
        WorkManager.getInstance(c).enqueueUniqueWork("$WORK-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<AutoBackupWorker>().build())
    }

    /** Writes one backup into the chosen folder and prunes old ones. Blocking. */
    fun runOnce(c: Context): Boolean {
        if (AppIntegrity.tampered || !enabled(c)) return false
        val tree = folder(c) ?: return false
        val pass = secrets(c).read(SECRET_PASS) ?: return false
        val ok = runCatching {
            val bytes = Backups.create(c, pass)
            val resolver = c.contentResolver
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val doc = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", Backups.fileName())!!
            resolver.openOutputStream(doc, "wt")!!.use { it.write(bytes) }
            // Only after a complete write, and never the file just written (R3-10).
            runCatching { prune(c, tree, keep = DocumentsContract.getDocumentId(doc)) }
        }.isSuccess
        prefs(c).putLong(KEY_LAST, System.currentTimeMillis())
        prefs(c).putBoolean(KEY_LAST_OK, ok)
        return ok
    }

    /** Deletes our own older backups in the folder (only files named SpendSense-backup-*.ssbk), keeping [KEEP]. */
    private fun prune(c: Context, tree: Uri, keep: String) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        val ours = ArrayList<Triple<String, Long, String>>()
        c.contentResolver.query(children, cols, null, null, null)?.use { cur ->
            while (cur.moveToNext()) {
                val name = cur.getString(1) ?: continue
                if (OUR_NAME.matches(name)) ours += Triple(cur.getString(0), cur.getLong(2), name)
            }
        }
        // Newest first by the date in our own file name (providers may report no modification time).
        ours.filter { it.first != keep }.sortedByDescending { it.third }.drop(KEEP - 1).forEach { (id, _, _) ->
            runCatching { DocumentsContract.deleteDocument(c.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, id)) }
        }
    }
}

class AutoBackupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = if (AutoBackup.runOnce(applicationContext)) Result.success() else Result.retry().takeIf { runAttemptCount < 2 } ?: Result.failure()
}

/** Settings › Backup on Android: weekly auto-backup to a folder the user picks. */
@Composable
fun AndroidAutoBackupSettings() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(AutoBackup.enabled(context)) }
    var asking by remember { mutableStateOf(false) }
    var tree by remember { mutableStateOf<Uri?>(null) }
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { tree = uri; asking = true }
    }
    SettingsGroup("Auto-backup") {
        SettingsRow(
            "Auto-backup weekly to a folder I pick",
            if (on) "On. A new encrypted backup goes to your folder every week; the 5 newest are kept." else "Encrypted with a passphrase you set. Pick a folder on this phone or a synced drive.",
            onClick = { if (on) { AutoBackup.disable(context); on = false } else pickTree.launch(null) },
        ) { OnOffToggle(on) { want -> if (!want) { AutoBackup.disable(context); on = false } else pickTree.launch(null) } }
        if (asking && tree != null) {
            Spacer(Modifier.height(16.dp))
            SquareField(pass, { pass = it.take(200) }, "Auto-backup passphrase (at least ${BackupFormat.MIN_PASSPHRASE})",
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Spacer(Modifier.height(8.dp))
            SquareField(pass2, { pass2 = it.take(200) }, "Repeat passphrase",
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton("Turn on", enabled = pass.length >= BackupFormat.MIN_PASSPHRASE && pass == pass2, modifier = Modifier.weight(1f), onClick = {
                    runCatching { AutoBackup.enable(context, tree!!, pass) }.onSuccess { on = true }
                    asking = false; pass = ""; pass2 = ""; tree = null
                })
                GhostButton("Cancel", { asking = false; pass = ""; pass2 = ""; tree = null }, Modifier.weight(1f))
            }
        }
        if (on) {
            val last = remember(tick) { AutoBackup.lastRun(context) }
            SettingsRow(
                "Back up to the folder now",
                last?.let { (t, ok) -> (if (ok) "Last backup " else "Last attempt failed ") + formatDateTime(t) } ?: "No backup yet.",
                onClick = { AutoBackup.runNow(context); tick++ },
            )
        }
    }
}
