package com.caleb.prism

import android.annotation.SuppressLint
import android.content.Context
import android.net.*
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.*
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.math.*

data class CrowdInvitation(val host: String, val port: Int, val session: String, val token: String,
    val key: ByteArray, val source: Boolean, val ssid: String?, val password: String?) {
    companion object {
        fun parse(text: String): CrowdInvitation {
            require(text.length <= 4096) { "The invitation is too long." }
            val uri = Uri.parse(text.trim())
            require(uri.scheme == "prism" && uri.host == "crowd") { "Paste a Prism crowd invitation from the controller." }
            fun value(name: String) = requireNotNull(uri.getQueryParameter(name)) { "Incomplete invitation." }
            val host = value("host")
            val parts = host.split('.').map { it.toIntOrNull() }
            require(parts.size == 4 && parts.all { it != null && it in 0..255 } &&
                (parts[0] == 10 || (parts[0] == 172 && parts[1] in 16..31) ||
                (parts[0] == 192 && parts[1] == 168) || parts[0] == 127)) { "The show must use a local IPv4 address." }
            val port = value("port").toIntOrNull()
            require(port != null && port in 1024..65535) { "Invalid show port." }
            val token = value("token")
            require(token.matches(Regex("[A-Za-z0-9_-]{24,64}"))) { "Invalid invitation token." }
            val session = value("sid")
            UUID.fromString(session)
            val key = Base64.decode(value("key"), Base64.URL_SAFE or Base64.NO_WRAP)
            require(key.size in 80..128) { "Invalid controller key." }
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(key))
            val ssid = uri.getQueryParameter("ssid")?.takeIf { it.isNotBlank() }
            val password = uri.getQueryParameter("password")
            require(ssid == null || ssid.toByteArray().size <= 32) { "Invalid Wi-Fi name." }
            require(password == null || password.length in 8..63) { "Invalid hotspot password." }
            return CrowdInvitation(host, port, session, token, key, uri.getQueryParameter("role") == "source", ssid, password)
        }
    }
}
data class CrowdStatus(
    val connected: Boolean = false, val source: Boolean = false, val phase: String = "Offline",
    val message: String = "", val clockMs: Double? = null, val bpm: Double = 0.0,
    val beatConfidence: Float = 0f, val barConfidence: Float = 0f, val phraseConfidence: Float = 0f,
    val phraseBar: Int = 0, val fps: Float = 0f, val missed: Long = 0
)

class CrowdClient(private val context: Context,
    private val rhythmInput: () -> Pair<RhythmState, PhraseState> = { AudioEngine.rhythm to AudioEngine.phrase }
) : AutoCloseable {
    private val mutableStatus = MutableStateFlow(CrowdStatus())
    val status = mutableStatus.asStateFlow()
    val clock = ShowClock()
    private val timeline = CrowdTimeline() // Only used by the GL thread.
    @Volatile private var snapshot: CrowdSnapshot? = null
    @Volatile private var generation = 0
    @Volatile private var timelineGeneration = 0
    private var renderedGeneration = -1
    @Volatile private var socket: DatagramSocket? = null
    @Volatile var connected = false; private set
    @Volatile var source = false; private set
    @Volatile var foreground = true; private set
    @Volatile var flashes = true
    @Volatile var localBlackout = false
    @Volatile var brightness = .8f
    @Volatile var measuredFps = 0f
    @Volatile private var missed = 0L
    @Volatile private var frames = 0L
    @Volatile private var phraseBar = 0
    private var callback: ConnectivityManager.NetworkCallback? = null
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var invitation: CrowdInvitation? = null
    @Volatile private var selectedNetwork: Network? = null
    private val id = UUID.randomUUID().toString()
    fun message(text: String) { mutableStatus.value = mutableStatus.value.copy(message = text) }
    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) { clock.clear(); snapshot = null; timelineGeneration++ }
    }

    /** Called after the activity has checked/requested the platform Wi-Fi permission. */
    @SuppressLint("MissingPermission")
    fun join(text: String, requestWifi: Boolean) {
        val invite = CrowdInvitation.parse(text)
        leave()
        invitation = invite; source = invite.source; connected = true; localBlackout = false
        mutableStatus.value = CrowdStatus(true, source, "Syncing", "Connecting to the show")
        if (requestWifi && invite.ssid != null) {
            val wifi = WifiNetworkSpecifier.Builder().setSsid(invite.ssid).apply {
                invite.password?.let { setWpa2Passphrase(it) }
            }.build()
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(wifi).build()
            val observer = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { if (connected && invitation === invite) start(network, invite) }
                override fun onLost(network: Network) {
                    if (connected && invitation === invite && network == selectedNetwork) {
                        stopSocket()
                        mutableStatus.value = mutableStatus.value.copy(phase = "Unsynced", message = "Hotspot disconnected. Waiting to reconnect.")
                    }
                }
                override fun onUnavailable() {
                    if (invitation === invite) message("Wi-Fi wasn't connected. Join the hotspot in Android Settings, then turn off automatic Wi-Fi joining here and retry.")
                }
            }
            callback = observer
            connectivity.requestNetwork(request, observer)
        } else {
            val network = connectivity.allNetworks.firstOrNull {
                connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            } ?: connectivity.activeNetwork
            start(network, invite)
            val observer = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (connected && invitation === invite && network != selectedNetwork) start(network, invite)
                }
                override fun onLost(network: Network) {
                    if (connected && invitation === invite && network == selectedNetwork) {
                        stopSocket()
                        mutableStatus.value = mutableStatus.value.copy(phase = "Unsynced",
                            message = "Hotspot disconnected. Waiting to reconnect.")
                    }
                }
            }
            callback = observer
            connectivity.registerNetworkCallback(NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), observer)
        }
    }

    @Synchronized private fun start(network: Network?, invite: CrowdInvitation) {
        if (!connected || invitation !== invite) return
        stopSocket()
        selectedNetwork = network
        val ownGeneration = generation
        thread(name = "Prism crowd", isDaemon = true) {
            val transport = DatagramSocket(null)
            try {
                synchronized(this) {
                    if (!connected || generation != ownGeneration) { transport.close(); return@thread }
                    socket = transport
                }
                network?.bindSocket(transport)
                transport.bind(InetSocketAddress(0))
                transport.connect(InetAddress.getByName(invite.host), invite.port)
                transport.soTimeout = 40
                val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(invite.key))
                val pending = linkedMapOf<String, Double>()
                val buffer = ByteArray(8193)
                var nextSync = 0.0; var nextHealth = 0.0; var nextAudio = 0.0
                var sequence = -1L
                fun send(type: String, body: JSONObject = JSONObject()) {
                    body.put("v", 1).put("type", type).put("id", id).put("name", Build.MODEL).put("token", invite.token)
                    val data = body.toString().toByteArray()
                    transport.send(DatagramPacket(data, data.size))
                }
                while (connected && generation == ownGeneration) {
                    if (!foreground) { Thread.sleep(100); continue }
                    val local = localTime()
                    if (local >= nextSync) {
                        val nonce = UUID.randomUUID().toString()
                        pending[nonce] = local
                        while (pending.size > 20) pending.remove(pending.keys.first())
                        send("sync", JSONObject().put("nonce", nonce).put("t1", local))
                        nextSync = local + if ((clock.estimate?.samples ?: 0) < 20) 100 else 500
                    }
                    if (local >= nextHealth) {
                        refreshStatus(local)
                        val health = status.value
                        send("health", JSONObject().put("health", JSONObject()
                            .put("state", health.phase).put("clockMs", health.clockMs ?: JSONObject.NULL)
                            .put("fps", measuredFps).put("frames", frames).put("missed", missed)
                            .put("phraseBar", phraseBar).put("beatConfidence", health.beatConfidence)))
                        nextHealth = local + 500
                    }
                    if (invite.source && local >= nextAudio && clock.estimate?.ready(local) == true) {
                        val mapping = clock.estimate!!
                        val (rhythm, phrase) = rhythmInput()
                        if (rhythm.bpm in 40f..240f) {
                            send("audio", JSONObject().put("audio", JSONObject()
                                .put("at", mapping.time(rhythm.timestamp * 1000))
                                .put("beat", rhythm.position - rhythm.barOffset)
                                .put("bpm", rhythm.bpm).put("meter", rhythm.beatsPerBar)
                                .put("confidence", rhythm.confidence).put("barConfidence", rhythm.barConfidence)
                                .put("phraseConfidence", phrase.confidence).put("phraseBar", phrase.bar ?: 0).put("phraseBars", phrase.bars)
                                .put("clockMs", mapping.error(local))
                                .put("ready", rhythm.signalPresent && rhythm.locked && rhythm.barLocked && !rhythm.coasting &&
                                    rhythm.conflict == RhythmConflict.NONE && local - rhythm.timestamp * 1000 in 0.0..500.0)))
                        }
                        nextAudio = local + 100
                    }
                    val packet = DatagramPacket(buffer, buffer.size)
                    try { transport.receive(packet) } catch (_: SocketTimeoutException) { continue }
                    val received = localTime()
                    if (packet.length > 8192) continue
                    try {
                        val envelope = JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
                        val payload = Base64.decode(envelope.getString("payload"), Base64.URL_SAFE or Base64.NO_WRAP)
                        val signature = Base64.decode(envelope.getString("signature"), Base64.URL_SAFE or Base64.NO_WRAP)
                        val verifier = Signature.getInstance("SHA256withECDSA")
                        verifier.initVerify(publicKey); verifier.update(payload)
                        if (!verifier.verify(signature)) continue
                        val message = JSONObject(String(payload, Charsets.UTF_8))
                        if (message.getInt("v") != 1 || message.getString("sid") != invite.session) continue
                        when (message.getString("type")) {
                            "sync" -> {
                                val nonce = message.getString("nonce")
                                val t1 = pending.remove(nonce) ?: continue
                                if (message.getString("id") == id && message.getDouble("t1") == t1)
                                    clock.observe(t1, message.getDouble("t2"), message.getDouble("t3"), received)
                            }
                            "state" -> {
                                val next = parseSnapshot(message, received)
                                if (next.sequence <= sequence) continue
                                val mapping = clock.estimate
                                if (mapping?.ready(received) == true &&
                                    mapping.time(received) !in next.sent - 100..next.validUntil) continue
                                sequence = next.sequence
                                snapshot = next
                            }
                            "error" -> this@CrowdClient.message(message.getString("message").take(160))
                        }
                    } catch (_: Exception) { /* Invalid packets cannot affect the show clock or renderer. */ }
                }
                runCatching { send("leave") }
            } catch (error: Exception) {
                if (generation == ownGeneration && connected) {
                    clock.clear(); snapshot = null
                    mutableStatus.value = mutableStatus.value.copy(phase = "Unsynced",
                        message = "Can't reach the show. Check the hotspot and reconnect.")
                }
            } finally {
                transport.close()
                synchronized(this) { if (socket === transport) socket = null }
            }
        }
    }

    private fun refreshStatus(local: Double) {
        val estimate = clock.estimate
        val data = snapshot
        val age = data?.let { local - it.received } ?: Double.POSITIVE_INFINITY
        val phase = when {
            estimate == null || data == null -> "Syncing"
            !estimate.ready(local) || age > 2000 || estimate.time(local) > data.validUntil -> "Unsynced"
            age > 750 -> "Coasting"
            else -> "Ready"
        }
        mutableStatus.value = CrowdStatus(connected, source, phase,
            when (phase) {
                "Syncing" -> "Waiting for the show clock"
                "Unsynced" -> "Timing is uncertain. Flashes are suppressed."
                "Coasting" -> "Brief connection gap. Following the last trusted timeline."
                else -> if (data?.blackout == true) "VJ blackout" else if (data?.musicReady == false) "Waiting for the master to find the beat and bar" else "Following the show"
            }, estimate?.error(local), data?.cueAt(estimate?.time(local) ?: 0.0)?.bpm ?: 0.0,
            data?.beatConfidence ?: 0f, data?.barConfidence ?: 0f, data?.phraseConfidence ?: 0f,
            phraseBar, measuredFps, missed)
    }

    /** GL-thread only. presentationNs shares System.nanoTime's monotonic timebase. */
    fun frame(presentationNs: Long): CrowdFrame? {
        if (!connected) return null
        if (renderedGeneration != timelineGeneration) { timeline.reset(); renderedGeneration = timelineGeneration }
        val data = snapshot ?: return null
        val estimate = clock.estimate ?: return null
        val local = presentationNs / 1e6
        val frame = timeline.sample(data, estimate.time(local), foreground && estimate.ready(local), flashes && !localBlackout)
        missed = timeline.missed; frames++; phraseBar = frame.phraseBar
        return if (localBlackout) frame.copy(dark = true) else frame
    }
    @Synchronized private fun stopSocket() {
        generation++; timelineGeneration++
        socket?.close(); socket = null; snapshot = null; clock.clear()
    }
    @Synchronized fun leave() {
        connected = false; stopSocket()
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }; callback = null
        invitation = null; selectedNetwork = null; source = false; frames = 0; missed = 0
        mutableStatus.value = CrowdStatus()
    }
    override fun close() = leave()
    companion object {
        fun localTime() = System.nanoTime() / 1e6
        private fun JSONObject.number(key: String, low: Double, high: Double): Double {
            val value = getDouble(key)
            require(value.isFinite() && value in low..high)
            return value
        }
        internal fun parseSnapshot(json: JSONObject, received: Double): CrowdSnapshot {
            val sent = json.number("sent", 0.0, 1e14)
            val expiry = json.number("validUntil", sent, sent + 3000)
            val array = json.getJSONArray("cues")
            require(array.length() in 1..2)
            val cues = (0 until array.length()).map { i ->
                val c = array.getJSONObject(i)
                val scene = Scene.entries.first { it.id == c.getInt("scene") }
                val palette = Palette.entries[c.getInt("palette")]
                val meter = c.getInt("meter"); require(meter in listOf(3,4))
                val phraseBars = c.getInt("phraseBars"); require(phraseBars in listOf(8,16,32))
                val every = c.number("flashEvery", .25, 4.0); require(every in listOf(.25,.5,1.0,3.0,4.0))
                val mode = c.getString("mode"); require(mode in listOf("flash","scene"))
                val settings = VisualSettings(scene = scene, palette = palette, audioEnabled = true,
                    speed = c.number("speed", .05, 2.0).toFloat(), intensity = c.number("intensity", .25, 1.6).toFloat(),
                    complexity = c.number("complexity", 0.0, 1.0).toFloat(), symmetry = c.getInt("symmetry").also { require(it in 3..20) },
                    distortion = c.number("distortion", 0.0, 1.5).toFloat(), zoom = c.number("zoom", .45, 3.0).toFloat(),
                    hue = c.number("hue", 0.0, 1.0).toFloat(), rotation = c.number("rotation", -1.0, 1.0).toFloat(),
                    colorSpeed = c.number("colorSpeed", 0.0, .2).toFloat(), morph = c.number("morph", 0.0, 1.5).toFloat())
                CrowdCue(c.getLong("revision"), c.number("effective", 0.0, sent + 12000),
                    c.number("anchor", -1e14, 1e14), c.number("beat", -1e9, 1e9),
                    c.number("bpm", 40.0, 240.0), meter, phraseBars, c.getLong("phraseOrigin"),
                    c.getBoolean("running"), mode == "flash", settings,
                    c.getInt("previousScene").also { require(it in listOf(0,1,2,6)) },
                    c.number("transitionAt", 0.0, sent + 12000), every,
                    c.number("flashMs", 16.0, 100.0), c.number("invertMs", 60.0, 400.0))
            }
            require(cues.zipWithNext().all { (a,b) -> a.effective <= b.effective && a.revision < b.revision })
            val music = json.getJSONObject("music")
            return CrowdSnapshot(json.getLong("seq"), sent, expiry, received, cues, json.getBoolean("blackout"),
                music.getBoolean("ready"), music.number("confidence", 0.0, 1.0).toFloat(),
                music.number("barConfidence", 0.0, 1.0).toFloat(), music.number("phraseConfidence", 0.0, 1.0).toFloat())
        }
    }
}
