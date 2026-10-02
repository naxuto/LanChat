package com.example.lanchat

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.*
import java.util.UUID

data class Msg(val name: String, val text: String, val mine: Boolean)

/** UDP broadcast chat: no server, no internet. Every phone on the same WiFi/hotspot is a peer. */
class LanChat(ctx: Context, private val name: String, private val onMsg: (Msg) -> Unit) {
    private val port = 45454
    private val me = UUID.randomUUID().toString()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var socket: DatagramSocket? = null
    private val lock = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        .createMulticastLock("lanchat").apply { setReferenceCounted(false) }

    fun start() {
        lock.acquire()
        scope.launch {
            try {
                val s = DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(port)) }
                socket = s
                val buf = ByteArray(4096)
                while (isActive) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val j = JSONObject(String(p.data, 0, p.length, Charsets.UTF_8))
                    if (j.getString("id") == me) continue
                    withContext(Dispatchers.Main) { onMsg(Msg(j.getString("n"), j.getString("t"), false)) }
                }
            } catch (_: Exception) {}
        }
    }

    fun send(text: String) {
        scope.launch {
            try {
                val b = JSONObject().put("id", me).put("n", name).put("t", text).toString().toByteArray(Charsets.UTF_8)
                for (addr in broadcasts()) socket?.send(DatagramPacket(b, b.size, addr, port))
            } catch (_: Exception) {}
        }
    }

    // Broadcast address of every active network interface (WiFi and hotspot both)
    private fun broadcasts(): List<InetAddress> {
        val out = mutableListOf<InetAddress>()
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
            if (ni.isUp && !ni.isLoopback) ni.interfaceAddresses.forEach { ia -> ia.broadcast?.let { out += it } }
        }
        if (out.isEmpty()) out += InetAddress.getByName("255.255.255.255")
        return out
    }

    fun stop() { scope.cancel(); socket?.close(); if (lock.isHeld) lock.release() }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

@Composable
fun App() {
    var name by remember { mutableStateOf("") }
    if (name.isBlank()) NameScreen { name = it } else ChatScreen(name)
}

@Composable
fun NameScreen(onDone: (String) -> Unit) {
    var t by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Text("LAN Chat", style = MaterialTheme.typography.headlineLarge)
        Text("Same WiFi te thakle internet charai chat", Modifier.padding(8.dp))
        OutlinedTextField(t, { t = it }, label = { Text("Tomar nam") }, singleLine = true)
        Spacer(Modifier.height(12.dp))
        Button({ if (t.isNotBlank()) onDone(t.trim()) }) { Text("Chat shuru koro") }
    }
}

@Composable
fun ChatScreen(name: String) {
    val ctx = LocalContext.current
    val msgs = remember { mutableStateListOf<Msg>() }
    val chat = remember { LanChat(ctx, name) { msgs.add(it) } }
    DisposableEffect(Unit) { chat.start(); onDispose { chat.stop() } }
    val list = rememberLazyListState()
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.lastIndex) }
    var input by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().navigationBarsPadding()) {
        Text("LAN Chat • $name", Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary).padding(16.dp),
            color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleMedium)
        LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp), list) {
            items(msgs) { m ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), if (m.mine) Arrangement.End else Arrangement.Start) {
                    Column(Modifier.widthIn(max = 280.dp)
                        .background(if (m.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
                        .padding(10.dp)) {
                        if (!m.mine) Text(m.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(m.text)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Message likho...") })
            Spacer(Modifier.width(8.dp))
            Button({
                val t = input.trim()
                if (t.isNotEmpty()) { msgs.add(Msg(name, t, true)); chat.send(t); input = "" }
            }) { Text("Send") }
        }
    }
}
