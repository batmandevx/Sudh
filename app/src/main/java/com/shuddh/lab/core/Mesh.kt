package com.shuddh.lab.core

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * Shuddh Mesh — an offline, multi-hop message network over Bluetooth Low Energy advertisements.
 *
 * Every phone both advertises and scans. Messages are small packets in BLE service data; a phone
 * that hears a new packet shows it and re-advertises it with TTL − 1, so messages hop phone to
 * phone across a street or a village with no internet, no pairing and no accounts. Advertising
 * is connectionless, which keeps power low.
 *
 * Packet (≤ 200 bytes): 'T' | type | ttl | room(2) | id(4) | time(4) | nameLen | name | payload(UTF-8)
 * `room` is a 16-bit code of the room name: everyone relays everything, but chat only shows in its room.
 */
object MeshProto {
    const val MAGIC: Byte = 0x54
    const val HELLO = 0
    const val CHAT = 1
    const val ALERT = 2
    const val SEAL = 3
    const val SOS = 4
    val SERVICE: ParcelUuid = ParcelUuid.fromString("00005d11-0000-1000-8000-00805f9b34fb")

    data class Packet(val type: Int, val ttl: Int, val id: Int, val time: Long, val name: String, val text: String, val room: Int = 0)

    fun roomCode(room: String) = room.trim().lowercase().hashCode() and 0xFFFF

    /** QR payload that lets another phone join this room. */
    fun invite(room: String, from: String) = "SHUDDHMESH1;r=${java.net.URLEncoder.encode(room, "UTF-8")};n=${java.net.URLEncoder.encode(from, "UTF-8")}"

    fun parseInvite(text: String): Pair<String, String>? = runCatching {
        if (!text.startsWith("SHUDDHMESH1;")) return null
        val m = text.split(";").drop(1).associate { it.substringBefore("=") to java.net.URLDecoder.decode(it.substringAfter("="), "UTF-8") }
        m.getValue("r") to m["n"].orEmpty()
    }.getOrNull()

    fun encode(p: Packet, maxBytes: Int): ByteArray {
        val name = p.name.toByteArray(Charsets.UTF_8).take(16).toByteArray()
        val space = maxBytes - 14 - name.size
        var text = p.text.toByteArray(Charsets.UTF_8)
        if (text.size > space) text = text.copyOf(space.coerceAtLeast(0))
        return ByteBuffer.allocate(14 + name.size + text.size).apply {
            put(MAGIC); put(p.type.toByte()); put(p.ttl.toByte()); putShort(p.room.toShort()); putInt(p.id); putInt((p.time / 1000).toInt())
            put(name.size.toByte()); put(name); put(text)
        }.array()
    }

    fun decode(b: ByteArray): Packet? = runCatching {
        if (b.size < 14 || b[0] != MAGIC) return null
        val bb = ByteBuffer.wrap(b)
        bb.get()
        val type = bb.get().toInt(); val ttl = bb.get().toInt(); val room = bb.short.toInt() and 0xFFFF; val id = bb.int
        val time = (bb.int.toLong() and 0xffffffffL) * 1000
        val nl = bb.get().toInt().coerceIn(0, 16)
        val name = ByteArray(nl).also { bb.get(it) }.toString(Charsets.UTF_8)
        val text = ByteArray(bb.remaining()).also { bb.get(it) }.toString(Charsets.UTF_8)
        Packet(type, ttl, id, time, name, text, room)
    }.getOrNull()
}

data class MeshMessage(val packet: MeshProto.Packet, val mine: Boolean, val hops: Int, val rssi: Int, val receivedAt: Long = System.currentTimeMillis())

data class Peer(val address: String, val name: String, val rssi: Int, val lastSeen: Long, val room: Int = 0) {
    /** Rough distance from RSSI (log-distance model, 1 m ≈ −59 dBm, n = 2.2). */
    val metres: Double get() = Math.pow(10.0, (-59.0 - rssi) / 22.0).coerceIn(0.2, 60.0)
}

class Mesh(
    private val ctx: Context,
    private val nameProvider: () -> String,
    initialRoom: String = "Public",
    private val onRoomChange: (String) -> Unit = {},
    private val onAlert: (MeshProto.Packet) -> Unit,
) {
    /** Current chat room. Changing it is local — the radio relays every room's traffic. */
    var room by mutableStateOf(initialRoom); private set
    val roomCode get() = MeshProto.roomCode(room)

    fun joinRoom(name: String) { room = name.trim().ifBlank { "Public" }; onRoomChange(room) }
    val messages = mutableStateListOf<MeshMessage>()
    val peers = mutableStateMapOf<String, Peer>()
    var running by mutableStateOf(false); private set
    var status by mutableStateOf("Mesh is off"); private set
    var extended by mutableStateOf(false); private set
    var relayed by mutableStateOf(0); private set
    var heard by mutableStateOf(0); private set
    var beacon by mutableStateOf<String?>(null)

    private val main = Handler(Looper.getMainLooper())
    private val seen = LinkedHashSet<Int>()
    private data class Out(val bytes: ByteArray, val until: Long)
    private val outbox = mutableListOf<Out>()
    private var cursor = 0
    private var set: AdvertisingSet? = null
    private var ttlDefault = 4

    private val adapter: BluetoothAdapter? get() = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun hasPermissions() = permissions().all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    val bluetoothOn: Boolean get() = adapter?.isEnabled == true

    private val maxBytes get() = if (extended) 200 else 20

    @SuppressLint("MissingPermission")
    fun start() {
        val a = adapter
        when {
            a == null -> { status = "This phone has no Bluetooth"; return }
            !hasPermissions() -> { status = "Nearby-devices permission needed"; return }
            !a.isEnabled -> { status = "Turn Bluetooth on to join the mesh"; return }
        }
        if (running) return
        extended = a!!.isLeExtendedAdvertisingSupported
        val scanner = a.bluetoothLeScanner ?: run { status = "BLE scanner unavailable"; return }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).apply {
            if (extended) { setLegacy(false); setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED) }
        }.build()
        runCatching { scanner.startScan(null, settings, scanCb) }.onFailure { status = "Scan failed: ${it.message}"; return }

        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(!extended).setConnectable(false).setScannable(false)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
            .build()
        runCatching {
            a.bluetoothLeAdvertiser.startAdvertisingSet(params, dataFor(helloBytes()), null, null, null, advCb)
        }.onFailure { status = "Advertise failed: ${it.message}"; scanner.stopScan(scanCb); return }
        running = true
        status = if (extended) "Mesh live · BLE 5 extended advertising" else "Mesh live · legacy BLE (short messages)"
        main.postDelayed(rotate, 1100)
        main.postDelayed(prune, 4000)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!running) return
        running = false
        main.removeCallbacks(rotate); main.removeCallbacks(prune)
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCb) }
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(advCb) }
        set = null
        status = "Mesh is off"
    }

    fun send(type: Int, text: String) {
        val p = MeshProto.Packet(type, ttlDefault, Random.nextInt(), System.currentTimeMillis(), nameProvider(), text, roomCode)
        seen.add(p.id)
        synchronized(outbox) { outbox.add(Out(MeshProto.encode(p, maxBytes), System.currentTimeMillis() + 90_000)) }
        messages.add(0, MeshMessage(p, mine = true, hops = 0, rssi = 0))
    }

    private fun helloBytes(): ByteArray = MeshProto.encode(
        MeshProto.Packet(MeshProto.HELLO, 0, 0, System.currentTimeMillis(), nameProvider(), beacon ?: "", roomCode), maxBytes,
    )

    private fun dataFor(bytes: ByteArray) = AdvertiseData.Builder()
        .setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
        .addServiceData(MeshProto.SERVICE, bytes).build()

    /** Cycles the single advertising set through HELLO + every live outbox packet. */
    private val rotate = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            if (!running) return
            val now = System.currentTimeMillis()
            val next: ByteArray = synchronized(outbox) {
                outbox.removeAll { it.until < now }
                val slots = outbox.size + 1
                cursor = (cursor + 1) % slots
                if (cursor == outbox.size) helloBytes() else outbox[cursor].bytes
            }
            runCatching { set?.setAdvertisingData(dataFor(next)) }
            main.postDelayed(this, 1100)
        }
    }

    private val prune = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.currentTimeMillis()
            peers.keys.filter { now - (peers[it]?.lastSeen ?: 0) > 20_000 }.forEach { peers.remove(it) }
            main.postDelayed(this, 4000)
        }
    }

    private val advCb = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(s: AdvertisingSet?, txPower: Int, st: Int) {
            if (st == ADVERTISE_SUCCESS) set = s else main.post { status = "Advertising error $st"; stop() }
        }
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) = handle(r)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { handle(it) }
        override fun onScanFailed(errorCode: Int) { main.post { status = "Scan error $errorCode" } }
    }

    private fun handle(r: ScanResult) {
        val bytes = r.scanRecord?.getServiceData(MeshProto.SERVICE) ?: return
        val p = MeshProto.decode(bytes) ?: return
        main.post {
            peers[r.device.address] = Peer(r.device.address, p.name.ifBlank { "Shuddh phone" }, r.rssi, System.currentTimeMillis(), p.room)
            if (p.type == MeshProto.HELLO) {
                if (p.text.isNotBlank()) receive(p.copy(type = MeshProto.SEAL, id = (r.device.address + p.text).hashCode(), ttl = 0), r.rssi)
                return@post
            }
            receive(p, r.rssi)
        }
    }

    private fun receive(p: MeshProto.Packet, rssi: Int) {
        if (!seen.add(p.id)) return
        if (seen.size > 600) seen.remove(seen.first())
        heard++
        messages.add(0, MeshMessage(p, mine = false, hops = (ttlDefault - p.ttl).coerceAtLeast(1), rssi = rssi))
        if (messages.size > 200) messages.removeAt(messages.lastIndex)
        if (p.type == MeshProto.ALERT || p.type == MeshProto.SEAL) onAlert(p)
        if (p.ttl > 0) {
            relayed++
            synchronized(outbox) {
                outbox.add(Out(MeshProto.encode(p.copy(ttl = p.ttl - 1), maxBytes), System.currentTimeMillis() + 45_000))
            }
        }
    }
}
