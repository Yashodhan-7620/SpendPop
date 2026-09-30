package com.spendpop.app

import android.app.Application
import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

val Ink = Color(0xFF0E0B1F); val CardBg = Color(0xFF1B1636); val Pink = Color(0xFFFF2E93)
val Lime = Color(0xFFC6FF00); val Cyan = Color(0xFF00E5FF); val Sun = Color(0xFFFFD600); val Violet = Color(0xFF7C4DFF)
val Palette = listOf(Pink, Lime, Cyan, Sun, Violet)

class Vm(app: Application) : AndroidViewModel(app) {
    val tx = Db.get(app).dao().all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@Composable fun PopTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(primary = Pink, secondary = Lime, tertiary = Cyan, background = Ink, surface = CardBg,
        onPrimary = Color.Black, onBackground = Color.White, onSurface = Color.White),
    shapes = Shapes(medium = RoundedCornerShape(28.dp), large = RoundedCornerShape(36.dp)), content = content)

fun emoji(c: String) = when (c) { "Food" -> "🍕"; "Shopping" -> "🛍️"; "Travel" -> "🚕"; "Bills" -> "⚡"; "Cash" -> "🏧"; else -> "💸" }
fun inr(v: Double) = "₹" + "%,.0f".format(v)

@Composable fun LockScreen(onUnlock: () -> Unit) = Box(Modifier.fillMaxSize().background(Ink), Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🔒", fontSize = 64.sp); Text("SpendPop", fontSize = 40.sp, fontWeight = FontWeight.Black, color = Pink)
        Spacer(Modifier.height(20.dp)); Button(onClick = onUnlock, colors = ButtonDefaults.buttonColors(containerColor = Lime)) { Text("UNLOCK", fontWeight = FontWeight.Black) }
    }
}

@Composable fun App(vm: Vm, onConnect: () -> Unit, onPerms: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val list by vm.tx.collectAsState()
    Scaffold(containerColor = Ink, bottomBar = {
        NavigationBar(containerColor = CardBg) {
            listOf("Pop" to "💥", "Feed" to "🧾", "Vibes" to "🎛️").forEachIndexed { i, (t, e) ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(e, fontSize = 22.sp) }, label = { Text(t, fontWeight = FontWeight.Bold) })
            }
        }
    }) { p -> Box(Modifier.padding(p).fillMaxSize()) { when (tab) { 0 -> Dash(list); 1 -> Feed(list); else -> Settings(onConnect, onPerms) } } }
}

@Composable fun Dash(l: List<TxnEntity>) {
    val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    val d0 = cal.timeInMillis; cal.set(Calendar.DAY_OF_MONTH, 1); val m0 = cal.timeInMillis
    val deb = l.filter { it.type == "DEBIT" }
    val month = deb.filter { it.ts >= m0 }; val today = deb.filter { it.ts >= d0 }.sumOf { it.amount }
    val total = month.sumOf { it.amount }
    val cats = month.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.amount } }.entries.sortedByDescending { it.value }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("SpendPop ✨", fontSize = 34.sp, fontWeight = FontWeight.Black, color = Lime)
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(36.dp)).background(Brush.linearGradient(listOf(Pink, Violet))).padding(24.dp)) {
            Column { Text("THIS MONTH", fontWeight = FontWeight.Black, letterSpacing = 3.sp); Text(inr(total), fontSize = 52.sp, fontWeight = FontWeight.Black) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Stat("TODAY", inr(today), Cyan, Modifier.weight(1f)); Stat("POPS", "${month.size}", Sun, Modifier.weight(1f))
        }
        Text("WHERE IT WENT", fontWeight = FontWeight.Black, color = Cyan, letterSpacing = 2.sp)
        cats.forEachIndexed { i, (c, v) ->
            Column { Text("${emoji(c)} $c  ${inr(v)}", fontWeight = FontWeight.Bold)
                LinearProgressIndicator(progress = { (v / total).toFloat() }, color = Palette[i % 5], trackColor = CardBg,
                    modifier = Modifier.fillMaxWidth().height(12.dp).clip(CircleShape)) }
        }
        if (cats.isEmpty()) Text("No spends yet. Legendary self-control 😎", color = Color.Gray)
    }
}
@Composable fun Stat(t: String, v: String, c: Color, m: Modifier) = Column(m.clip(RoundedCornerShape(28.dp)).background(CardBg).border(2.dp, c, RoundedCornerShape(28.dp)).padding(16.dp)) {
    Text(t, color = c, fontWeight = FontWeight.Black, letterSpacing = 2.sp); Text(v, fontSize = 26.sp, fontWeight = FontWeight.Black) }

@Composable fun Feed(l: List<TxnEntity>) {
    val f = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }
    LazyColumn(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Feed 🧾", fontSize = 34.sp, fontWeight = FontWeight.Black, color = Pink, modifier = Modifier.padding(top = 20.dp)) }
        items(l, key = { it.id }) { t ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(CardBg).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(emoji(t.category), fontSize = 28.sp); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) { Text(t.merchant, fontWeight = FontWeight.Bold); Text("${t.method} · ${t.bank} · ${f.format(Date(t.ts))}", fontSize = 12.sp, color = Color.Gray) }
                Column(horizontalAlignment = Alignment.End) {
                    Text((if (t.type == "DEBIT") "-" else "+") + inr(t.amount), fontWeight = FontWeight.Black, color = if (t.type == "DEBIT") Pink else Lime)
                    Text(if (t.synced) "☁️ synced" else "⏳ queued", fontSize = 11.sp, color = Color.Gray) }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable fun Settings(onConnect: () -> Unit, onPerms: () -> Unit) {
    val c: Context = LocalContext.current; val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(Prefs.enabled(c)) }; var paste by remember { mutableStateOf("") }; var msg by remember { mutableStateOf("") }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Vibes 🎛️", fontSize = 34.sp, fontWeight = FontWeight.Black, color = Cyan)
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Auto-tracking", Modifier.weight(1f), fontWeight = FontWeight.Bold)
            Switch(on, { on = it; Prefs.setEnabled(c, it) }) }
        Button(onClick = onConnect, colors = ButtonDefaults.buttonColors(containerColor = Lime), modifier = Modifier.fillMaxWidth()) { Text("CONNECT GOOGLE SHEET", fontWeight = FontWeight.Black) }
        if (BuildConfig.SMS) Button(onClick = onPerms, colors = ButtonDefaults.buttonColors(containerColor = Sun), modifier = Modifier.fillMaxWidth()) { Text("ALLOW SMS + ALERTS", fontWeight = FontWeight.Black) }
        else Button(onClick = onPerms, modifier = Modifier.fillMaxWidth()) { Text("ALLOW ALERTS", fontWeight = FontWeight.Black) }
        Text("Paste a bank SMS (parsed on-device, never saved)", color = Color.Gray)
        OutlinedTextField(paste, { paste = it }, Modifier.fillMaxWidth(), minLines = 3)
        Button(onClick = { scope.launch(Dispatchers.IO) { val ok = Repo.ingest(c, "", paste, System.currentTimeMillis(), true); msg = if (ok) "Popped! 🎉" else "Not a new bank transaction"; paste = "" } },
            colors = ButtonDefaults.buttonColors(containerColor = Cyan), modifier = Modifier.fillMaxWidth()) { Text("ADD FROM SMS TEXT", fontWeight = FontWeight.Black) }
        if (msg.isNotEmpty()) Text(msg, color = Lime)
        Button(onClick = { scope.launch(Dispatchers.IO) { Db.get(c).dao().wipe() } }, colors = ButtonDefaults.buttonColors(containerColor = Violet), modifier = Modifier.fillMaxWidth()) { Text("WIPE LOCAL DATA", fontWeight = FontWeight.Black) }
    }
}
