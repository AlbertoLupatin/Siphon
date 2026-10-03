package app.siphon.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Finds AirPlay speakers (mDNS `_raop._tcp`) and switches the player's audio between this device
 * and one of them. Bluetooth headphones such as AirPods need nothing here: they are ordinary
 * Android audio outputs, chosen in the system output switcher.
 */
class OutputManager(context: Context) {

    data class Device(
        val id: String,
        val name: String,
        val detail: String?,
        val host: InetAddress,
        val port: Int,
        val encryptionTypes: Set<Int>,
        val needsPassword: Boolean,
    )

    data class State(
        val devices: List<Device> = emptyList(),
        val active: Device? = null,
        val connecting: Boolean = false,
        val searching: Boolean = false,
        val message: String? = null,
        val volume: Float = 0.6f,
    )

    private val nsd = context.getSystemService(NsdManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** Installed into the player's audio pipeline by the playback service. */
    val tap = AirPlayTap()

    private var player: Player? = null
    private var client: RaopClient? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private var resolving = false

    private val playerListener = object : Player.Listener {
        // The speaker keeps ~2 s of audio buffered; drop it so pause and seek feel immediate.
        override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) flushRemote() }
        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) flushRemote()
        }
    }

    fun bindPlayer(p: Player) {
        player?.removeListener(playerListener)
        player = p
        p.addListener(playerListener)
    }

    fun unbindPlayer() {
        useLocal()
        player?.removeListener(playerListener)
        player = null
    }

    // ---- discovery ----

    fun startDiscovery() {
        if (discovery != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = _state.update { it.copy(searching = true) }
            override fun onDiscoveryStopped(serviceType: String) = _state.update { it.copy(searching = false) }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                _state.update { it.copy(searching = false, message = "Couldn't search the network (error $errorCode)") }
                discovery = null
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) { resolveQueue.add(info); resolveNext() }
            override fun onServiceLost(info: NsdServiceInfo) {
                _state.update { s -> s.copy(devices = s.devices.filterNot { it.id == info.serviceName }) }
            }
        }
        discovery = listener
        runCatching { nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { discovery = null; _state.update { s -> s.copy(message = "Network search unavailable") } }
    }

    fun stopDiscovery() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        _state.update { it.copy(searching = false) }
    }

    /** Older NsdManager versions resolve one service at a time, so resolves are queued. */
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val next = resolveQueue.poll() ?: return
        resolving = true
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                scope.launch { resolving = false; resolveNext() }
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                scope.launch {
                    resolving = false
                    toDevice(info)?.let { d -> _state.update { s -> s.copy(devices = (s.devices.filterNot { it.id == d.id } + d).sortedBy { it.name }) } }
                    resolveNext()
                }
            }
        })
    }

    @Suppress("DEPRECATION")
    private fun toDevice(info: NsdServiceInfo): Device? {
        val host = (if (Build.VERSION.SDK_INT >= 34) info.hostAddresses.firstOrNull() else info.host) ?: return null
        val txt = info.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8).orEmpty() }
        // Service names look like "AABBCCDDEEFF@Living Room".
        val name = info.serviceName.substringAfter('@')
        val et = txt["et"].orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
        return Device(
            id = info.serviceName,
            name = name,
            detail = model(txt["am"]),
            host = host,
            port = info.port,
            encryptionTypes = et,
            needsPassword = txt["pw"] == "true",
        )
    }

    private fun model(am: String?): String? = when {
        am == null -> null
        am.startsWith("AudioAccessory5") -> "HomePod mini"
        am.startsWith("AudioAccessory6") -> "HomePod (2nd generation)"
        am.startsWith("AudioAccessory") -> "HomePod"
        am.startsWith("AppleTV") -> "Apple TV"
        am.startsWith("AirPort") -> "AirPort Express"
        am.startsWith("Mac") || am.contains("Book") -> "Mac"
        else -> null
    }

    // ---- switching ----

    fun use(id: String) {
        val device = _state.value.devices.firstOrNull { it.id == id } ?: return
        if (device.needsPassword) {
            _state.update { it.copy(message = "${device.name} asks for a password, which Siphon doesn't support yet. Remove it in the Home app.") }
            return
        }
        scope.launch {
            _state.update { it.copy(connecting = true, message = "Connecting to ${device.name}…") }
            closeClient()
            // Unencrypted when the speaker allows it (et=0), otherwise classic RSA/AES (et=1).
            val encrypt = 0 !in device.encryptionTypes && 1 in device.encryptionTypes
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    RaopClient(device.host, device.port, encrypt) { Log.d("Siphon/AirPlay", it) }
                        .also { it.connect(_state.value.volume) }
                }
            }
            result.onSuccess { c ->
                client = c
                tap.target = c
                player?.volume = 0f // local output silent; the tap still sees the PCM
                _state.update { it.copy(active = device, connecting = false, message = "Audio plays on ${device.name} about 2 seconds after the picture.") }
            }.onFailure { e ->
                Log.w("Siphon/AirPlay", "connect failed", e)
                _state.update { it.copy(active = null, connecting = false, message = e.message ?: "Couldn't connect to ${device.name}") }
            }
        }
    }

    fun useLocal() {
        closeClient()
        player?.volume = 1f
        _state.update { it.copy(active = null, connecting = false, message = null) }
    }

    fun setVolume(v: Float) {
        _state.update { it.copy(volume = v) }
        val c = client ?: return
        scope.launch(Dispatchers.IO) { c.setVolume(v) }
    }

    private fun flushRemote() {
        val c = client ?: return
        scope.launch(Dispatchers.IO) { c.flush() }
    }

    private fun closeClient() {
        tap.target = null
        val c = client ?: return
        client = null
        scope.launch(Dispatchers.IO) { c.close() }
    }

    companion object {
        private const val SERVICE = "_raop._tcp"
    }
}
