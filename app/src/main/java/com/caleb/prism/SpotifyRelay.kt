package com.caleb.prism

import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import rikka.shizuku.Shizuku

/** Only an explicitly enabled Spotify session may bind and start the privileged helper. */
object SpotifyRelay {
    private const val REQUEST = 6412
    private lateinit var context: Context
    private val main = Handler(Looper.getMainLooper())
    private var initialized = false
    private var pending: ((Boolean) -> Unit)? = null
    private var service: ISpotifyRelay? = null
    private var bound = false
    private var generation = 0
    private var owner = Binder()
    var sourceUid: Int? = null; private set
    var active = false; private set
    var enabled by mutableStateOf(false); private set
    var description by mutableStateOf("Shizuku not connected"); private set
    val prepared get() = service != null && sourceUid != null
    private fun args() = Shizuku.UserServiceArgs(ComponentName(context, SpotifyRelayService::class.java))
        .daemon(false).processNameSuffix("spotify_relay").version(context.packageManager.getPackageInfo(context.packageName, 0).versionCode)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (pending == null) {
                runCatching { Shizuku.unbindUserService(args(), this, true) }; return
            }
            service = ISpotifyRelay.Stub.asInterface(binder)
            description = "Shizuku ready"
            pending?.also { pending = null; it(true) }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            val wasActive = active
            service = null; active = false; sourceUid = null; bound = false
            pending?.also { pending = null; it(false) }
            refresh()
            if (wasActive) AudioEngine.stop("Spotify helper stopped. Start Shizuku and reconnect audio.")
        }
    }
    fun init(value: Context) {
        context = value.applicationContext
        if (!initialized) {
            initialized = true
            enabled = context.getSharedPreferences("capture", 0).getBoolean("spotifyRelay", false)
            Shizuku.addBinderReceivedListenerSticky { main.post { refresh() } }
            Shizuku.addBinderDeadListener { main.post {
                val wasActive = active
                stop()
                if (wasActive) AudioEngine.stop("Shizuku stopped. Start it and reconnect audio.")
            } }
            Shizuku.addRequestPermissionResultListener { request, result ->
                if (request == REQUEST) main.post {
                    if (result == PackageManager.PERMISSION_GRANTED) bind()
                    else fail("Shizuku access declined. Allow prism in Shizuku, then reconnect.")
                }
            }
        }
        refresh()
    }
    fun updateEnabled(value: Boolean) {
        if (AudioEngine.status.value.running) return
        enabled = value
        context.getSharedPreferences("capture", 0).edit().putBoolean("spotifyRelay", value).apply()
        if (!value) stop()
    }
    fun refresh() {
        if (!initialized) return
        description = when {
            Build.VERSION.SDK_INT < 33 -> "Requires Android 13 or newer"
            !installed("moe.shizuku.privileged.api") -> "Install Shizuku to use compatibility mode"
            !Shizuku.pingBinder() -> "Open Shizuku and start it"
            runCatching { Shizuku.checkSelfPermission() }.getOrDefault(-1) != PackageManager.PERMISSION_GRANTED -> "Allow prism access to Shizuku when connecting"
            else -> "Shizuku ready // 48 kHz stereo"
        }
    }
    private fun installed(pkg: String) = runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.isSuccess
    fun prepare(result: (Boolean) -> Unit) {
        stop()
        pending = result
        if (Build.VERSION.SDK_INT < 33) { fail("Spotify compatibility needs Android 13 or newer."); return }
        sourceUid = runCatching { context.packageManager.getApplicationInfo("com.spotify.music", 0).uid }.getOrNull()
        if (sourceUid == null) { fail("Install Spotify in the same Android profile as prism."); return }
        if (!Shizuku.pingBinder()) { fail("Open Shizuku, start it with wireless debugging, then reconnect audio."); return }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) bind()
        else runCatching { Shizuku.requestPermission(REQUEST) }.onFailure { fail("Allow prism access in Shizuku, then reconnect.") }
    }
    private fun bind() {
        if (pending == null) return
        runCatching { bound = true; Shizuku.bindUserService(args(), connection) }
            .onFailure { fail("Couldn't open the Spotify helper. Restart Shizuku and reconnect.") }
        val binding = generation
        main.postDelayed({ if (generation == binding && pending != null) fail("Spotify helper timed out. Restart Shizuku and reconnect.") }, 10_000)
    }
    private fun fail(message: String) {
        val callback = pending
        pending = null; stop(); AudioEngine.report(message); callback?.invoke(false)
    }
    fun start(): Int {
        val uid = requireNotNull(sourceUid)
        require(uid != android.os.Process.myUid() && uid / 100000 == android.os.Process.myUid() / 100000)
        owner = Binder()
        requireNotNull(service).start(uid, owner)
        active = true
        return uid
    }
    fun healthy() = active && runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED && service?.isRunning == true
    }.getOrDefault(false)
    fun stop() {
        generation++
        pending = null
        runCatching { service?.stop() }
        if (bound && initialized) runCatching { Shizuku.unbindUserService(args(), connection, true) }
        bound = false; service = null; active = false; sourceUid = null
        refresh()
    }
    fun openSetup() {
        val launch = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable fun SpotifyRelayControls(locked: Boolean) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { SpotifyRelay.init(context); while (true) { SpotifyRelay.refresh(); kotlinx.coroutines.delay(1000) } }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Spotify compatibility", color = androidx.compose.ui.graphics.Color.White, fontSize = 12.sp)
                Text("Optional Shizuku audio relay", color = androidx.compose.ui.graphics.Color(0xFFB6B8C8), fontSize = 10.sp)
            }
            SettingHelp("Spotify compatibility")
            Switch(SpotifyRelay.enabled, SpotifyRelay::updateEnabled, enabled = !locked && android.os.Build.VERSION.SDK_INT >= 33,
                modifier = Modifier.semantics { contentDescription = "Spotify compatibility" })
        }
        if (SpotifyRelay.enabled) {
            Text(SpotifyRelay.description, color = androidx.compose.ui.graphics.Color(0xFFB6B8C8), fontSize = 11.sp)
            TextButton(onClick = SpotifyRelay::openSetup) { Text("OPEN SHIZUKU", fontSize = 11.sp) }
            Text("Disconnect audio to change this setting.", color = androidx.compose.ui.graphics.Color(0xFFB6B8C8), fontSize = 10.sp)
        }
    }
}
