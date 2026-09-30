package com.spendpop.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import kotlinx.coroutines.*

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION || !Prefs.enabled(c)) return
        val m = Telephony.Sms.Intents.getMessagesFromIntent(i) ?: return
        val sender = m.firstOrNull()?.originatingAddress ?: return
        val body = m.joinToString("") { it.messageBody ?: "" }
        val pr = goAsync()
        CoroutineScope(Dispatchers.IO).launch { try { Repo.ingest(c, sender, body, System.currentTimeMillis()) } finally { pr.finish() } }
    }
}
