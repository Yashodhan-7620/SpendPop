package com.spendpop.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

object Auth {
    // drive.file = NON-sensitive scope: app can only touch the sheet IT created (no access to your other Drive files)
    fun req() = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive.file"))).build()
    suspend fun token(c: Context): String? {
        val r = Identity.getAuthorizationClient(c).authorize(req()).await()
        return if (r.hasResolution()) null else r.accessToken   // null => user must tap "Connect Sheet" again
    }
}

object Sheets {
    private fun post(url: String, token: String, body: String): JSONObject {
        val cn = URL(url).openConnection() as HttpsURLConnection   // HTTPS only
        cn.requestMethod = "POST"; cn.connectTimeout = 15000; cn.readTimeout = 20000; cn.doOutput = true
        cn.setRequestProperty("Authorization", "Bearer $token"); cn.setRequestProperty("Content-Type", "application/json")
        cn.outputStream.use { it.write(body.toByteArray()) }
        if (cn.responseCode !in 200..299) throw IOException("HTTP ${cn.responseCode}")   // never log bodies
        return JSONObject(cn.inputStream.bufferedReader().readText())
    }
    // Blocks spreadsheet formula injection from attacker-controlled merchant text
    private fun clean(s: String) = s.trimStart('=', '+', '-', '@', '\t', '\r').take(60)
    private fun append(token: String, id: String, rows: JSONArray) =
        post("https://sheets.googleapis.com/v4/spreadsheets/$id/values/A1:append?valueInputOption=RAW&insertDataOption=INSERT_ROWS",
            token, JSONObject().put("values", rows).toString())

    fun create(token: String): String {
        val id = post("https://sheets.googleapis.com/v4/spreadsheets", token,
            JSONObject().put("properties", JSONObject().put("title", "SpendPop Expenses")).toString()).getString("spreadsheetId")
        append(token, id, JSONArray().put(JSONArray(listOf("Date", "Time", "Type", "Amount", "Merchant", "Bank", "Ref", "Category", "Source"))))
        return id
    }
    fun push(token: String, id: String, rows: List<TxnEntity>) {
        val d = SimpleDateFormat("dd-MM-yyyy", Locale.US); val t = SimpleDateFormat("HH:mm", Locale.US)
        val arr = JSONArray()
        rows.forEach { r -> arr.put(JSONArray(listOf(d.format(Date(r.ts)), t.format(Date(r.ts)), "${r.method} ${r.type}",
            r.amount, clean(r.merchant), r.bank, r.refMask, r.category, "SMS"))) }
        append(token, id, arr)
    }
}

class SyncWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        val dao = Db.get(applicationContext).dao()
        val rows = dao.unsynced(); if (rows.isEmpty()) return Result.success()
        val tok = Auth.token(applicationContext) ?: return Result.success()
        return try {
            val id = Prefs.sheet(applicationContext) ?: Sheets.create(tok).also { Prefs.setSheet(applicationContext, it) }
            Sheets.push(tok, id, rows); dao.markSynced(rows.map { it.id }); Result.success()
        } catch (e: Exception) { if (runAttemptCount < 5) Result.retry() else Result.failure() }
    }
    companion object {
        fun kick(c: Context) = WorkManager.getInstance(c).enqueueUniqueWork("sync", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
}

object Repo {
    suspend fun ingest(c: Context, sender: String, body: String, ts: Long, manual: Boolean = false): Boolean {
        val t = SmsParser.parse(if (manual) "MN-MANUAL" else sender, body, ts) ?: return false   // raw SMS is discarded here, never stored/logged
        val e = TxnEntity(fp = t.fp, type = t.type, amount = t.amount, merchant = t.merchant, method = t.method,
            bank = t.bank, refMask = t.refMask, ts = t.ts, category = t.category)
        if (Db.get(c).dao().insert(e) == -1L) return false   // duplicate
        SyncWorker.kick(c); notify(c, t); return true
    }
    private fun notify(c: Context, t: Txn) {
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("tx", "Transactions", NotificationManager.IMPORTANCE_DEFAULT))
        val pub = NotificationCompat.Builder(c, "tx").setSmallIcon(R.drawable.ic_launcher).setContentTitle("Transaction recorded").build()
        nm.notify((t.ts % Int.MAX_VALUE).toInt(), NotificationCompat.Builder(c, "tx").setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("₹${"%,.0f".format(t.amount)} ${if (t.type == "DEBIT") "debited" else "credited"} — ${t.merchant}")
            .setContentText("Recorded in SpendPop").setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(pub).build())
    }
}
