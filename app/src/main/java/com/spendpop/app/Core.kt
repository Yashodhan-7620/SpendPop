package com.spendpop.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// ---------- Parser (add new bank patterns HERE when updating) ----------
data class Txn(val type: String, val amount: Double, val merchant: String, val method: String,
               val bank: String, val refMask: String, val ts: Long, val fp: String, val category: String)

object SmsParser {
    private val senderOk = Regex("^(?=.*[A-Z])([A-Z]{2}-)?[A-Z0-9]{3,10}(-[A-Z])?$")   // bank sender IDs only; ignores personal (numeric) senders
    private val amt = Regex("(?i)(?:rs\\.?|inr|₹)\\s*([\\d,]+(?:\\.\\d{1,2})?)")
    private val ref = Regex("(?i)(?:ref(?:erence)?(?:\\s*(?:no|number))?\\.?|rrn|utr|txn\\s*id)\\s*[:\\-]?\\s*(\\d{9,18})")
    private val vpa = Regex("[A-Za-z0-9._\\-]{2,}@[A-Za-z]{2,}")
    private val at = Regex("(?i)\\b(?:at|to|towards)\\s+([A-Za-z0-9 &._\\-]{2,30}?)(?=\\s+(?:on|via|ref|upi|using)\\b|[.,]|$)")
    private val skip = Regex("(?i)\\b(otp|one[- ]time|password|cvv|offer|cashback|loan|apply now)\\b")
    private val debit = Regex("(?i)\\b(debited|spent|withdrawn|paid|sent|purchase)\\b")
    private val credit = Regex("(?i)\\b(credited|received|refund)\\b")
    private val banks = listOf("HDFC", "SBI", "ICICI", "AXIS", "KOTAK", "PNB", "BOB", "CANARA", "IDFC", "YES", "UNION", "PAYTM")

    fun parse(sender: String, body: String, ts: Long): Txn? {
        val s = sender.uppercase()
        if (!senderOk.matches(s) || skip.containsMatchIn(body)) return null
        val type = when { debit.containsMatchIn(body) -> "DEBIT"; credit.containsMatchIn(body) -> "CREDIT"; else -> return null }
        val amount = amt.find(body)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: return null
        val method = when {
            Regex("(?i)upi|vpa").containsMatchIn(body) -> "UPI"; Regex("(?i)\\batm\\b").containsMatchIn(body) -> "ATM"
            Regex("(?i)card").containsMatchIn(body) -> "CARD"; Regex("(?i)neft|imps|rtgs").containsMatchIn(body) -> "TRANSFER"
            else -> "OTHER" }
        val merchant = if (method == "ATM") "ATM" else (vpa.find(body)?.value ?: at.find(body)?.groupValues?.get(1)?.trim() ?: "Unknown")
        val bank = banks.firstOrNull { s.contains(it) } ?: "Bank"
        val r = ref.find(body)?.groupValues?.get(1)
        val key = r ?: "$bank|$amount|${ts / 60000}|$merchant|$type"
        val fp = MessageDigest.getInstance("SHA-256").digest("$bank|$key".toByteArray()).joinToString("") { "%02x".format(it) }
        val cat = when {
            method == "ATM" -> "Cash"
            Regex("(?i)swiggy|zomato|cafe|restaurant|pizza|food").containsMatchIn(merchant) -> "Food"
            Regex("(?i)amazon|flipkart|myntra|ajio").containsMatchIn(merchant) -> "Shopping"
            Regex("(?i)uber|ola|irctc|redbus|fuel|petrol|metro").containsMatchIn(merchant) -> "Travel"
            Regex("(?i)jio|airtel|electric|recharge|bill").containsMatchIn(merchant) -> "Bills"
            else -> "Other" }
        return Txn(type, amount, merchant, method, bank, r?.let { it.take(4) + "…" } ?: "", ts, fp, cat)   // full ref never stored
    }
}

// ---------- Encrypted local DB (SQLCipher; key wrapped by Android Keystore) ----------
@Entity(tableName = "txn", indices = [Index("fp", unique = true)])
data class TxnEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val fp: String, val type: String, val amount: Double,
    val merchant: String, val method: String, val bank: String, val refMask: String, val ts: Long,
    val category: String, val synced: Boolean = false)

@Dao interface TxnDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(e: TxnEntity): Long   // -1 => duplicate
    @Query("SELECT * FROM txn ORDER BY ts DESC") fun all(): Flow<List<TxnEntity>>
    @Query("SELECT * FROM txn WHERE synced = 0 ORDER BY ts") suspend fun unsynced(): List<TxnEntity>
    @Query("UPDATE txn SET synced = 1 WHERE id IN (:ids)") suspend fun markSynced(ids: List<Long>)
    @Query("DELETE FROM txn") suspend fun wipe()
}

@Database(entities = [TxnEntity::class], version = 1, exportSchema = true)
abstract class Db : RoomDatabase() {
    abstract fun dao(): TxnDao
    companion object {
        @Volatile private var i: Db? = null
        fun get(c: Context): Db = i ?: synchronized(this) {
            i ?: run {
                System.loadLibrary("sqlcipher")
                Room.databaseBuilder(c.applicationContext, Db::class.java, "spendpop.db")
                    .openHelperFactory(SupportOpenHelperFactory(Crypto.dbPass(c.applicationContext)))
                    // UPDATES: never use fallbackToDestructiveMigration(); add .addMigrations(...) when bumping version
                    .build().also { i = it }
            }
        }
    }
}

object Crypto {
    private const val ALIAS = "spendpop_master"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return g.generateKey()
    }
    fun dbPass(c: Context): ByteArray {
        val sp = c.getSharedPreferences("sec", Context.MODE_PRIVATE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        sp.getString("k", null)?.let {
            val b = Base64.decode(it, Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12))
            return cipher.doFinal(b, 12, b.size - 12)
        }
        val pass = ByteArray(32).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, key())
        sp.edit().putString("k", Base64.encodeToString(cipher.iv + cipher.doFinal(pass), Base64.NO_WRAP)).apply()
        return pass
    }
}

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("app", Context.MODE_PRIVATE)
    fun enabled(c: Context) = sp(c).getBoolean("on", true)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("on", v).apply()
    fun sheet(c: Context): String? = sp(c).getString("sheet", null)
    fun setSheet(c: Context, id: String) = sp(c).edit().putString("sheet", id).apply()
}
