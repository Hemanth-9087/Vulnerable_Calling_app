package com.example.voipdemo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.PowerManager
import android.text.TextUtils
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.random.Random
import android.content.SharedPreferences
import androidx.appcompat.app.AlertDialog

class MainActivity : AppCompatActivity() {

    // === Networking/RTP constants ===
    private val DISCOVERY_PORT = 50006
    private var OBSERVER_IP = "10.141.185.124"   // <-- put your laptop IP here
    private val MIRROR_TO_OBSERVER = true      // easy on/off switch
    private val VOICE_PORT = 50005
    private val RTP_VERSION = 2
    private val RTP_HEADER_SIZE = 12

    // 8kHz, mono, 16-bit PCM internally, but we send μ-law (PT=0) over RTP
    private val SAMPLE_RATE = 8000
    private val FRAME_SAMPLES = 160  // 20ms @ 8kHz
    private val FRAME_PCM_BYTES = FRAME_SAMPLES * 2 // 2 bytes per PCM16 sample

    // UI
    private lateinit var deviceList: ListView
    private lateinit var startCallBtn: Button
    private lateinit var stopCallBtn: Button
    private lateinit var statusText: TextView
    private lateinit var selectedDeviceText: TextView
    private lateinit var manualIpEdit: EditText
    private lateinit var addIpBtn: Button

    private lateinit var deviceListAdapter: ArrayAdapter<String>
    private val discoveredDevices = LinkedHashSet<String>()
    private var selectedDeviceIp: String? = null

    // Threads/sockets/audio
    private var sendThread: Thread? = null
    private var recvThread: Thread? = null
    private var discoveryThread: Thread? = null
    private var broadcastThread: Thread? = null
    private var sendSocket: DatagramSocket? = null
    private var recvSocket: DatagramSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    // RTP state
    private var rtpSequenceNumber = Random.nextInt(0, 65536)
    private var rtpTimestamp = 0
    private val rtpSSRC = Random.nextInt()

    // Locks (optional; helps on some devices)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val PERMISSIONS_REQUEST_CODE = 1234

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initUi()

        val prefs: SharedPreferences = getSharedPreferences("VoipAppPrefs", Context.MODE_PRIVATE)
        val observerIpSet = prefs.getBoolean("observer_ip_set", false)

        if (!observerIpSet) {
            val editText = EditText(this)
            editText.setText(OBSERVER_IP) // Pre-fill with current value

            AlertDialog.Builder(this)
                .setTitle("Set Observer IP")
                .setMessage("Please enter the Observer IP address:")
                .setView(editText)
                .setPositiveButton("OK") { dialog, _ ->
                    val newIp = editText.text.toString()
                    if (newIp.isNotEmpty()) {
                        OBSERVER_IP = newIp
                        prefs.edit().putString("OBSERVER_IP", newIp).apply()
                        prefs.edit().putBoolean("observer_ip_set", true).apply()
                        Toast.makeText(this, "Observer IP set to $OBSERVER_IP", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Observer IP cannot be empty, using default.", Toast.LENGTH_SHORT).show()
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel") { dialog, _ ->
                    Toast.makeText(this, "Using default Observer IP: $OBSERVER_IP", Toast.LENGTH_SHORT).show()
                    prefs.edit().putBoolean("observer_ip_set", true).apply()
                    dialog.dismiss()
                }
                .setCancelable(false) // Prevent dismissing without action
                .show()
        } else {
            // Load saved IP if it exists
            OBSERVER_IP = prefs.getString("OBSERVER_IP", OBSERVER_IP) ?: OBSERVER_IP
        }

        checkPermissionsAndStart()
    }

    private fun initUi() {
        deviceList = findViewById(R.id.deviceList)
        startCallBtn = findViewById(R.id.startCallBtn)
        stopCallBtn = findViewById(R.id.stopCallBtn)
        statusText = findViewById(R.id.statusText)
        selectedDeviceText = findViewById(R.id.selectedDeviceText)
        manualIpEdit = findViewById(R.id.manualIpEdit)
        addIpBtn = findViewById(R.id.addIpBtn)
        
        deviceListAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_single_choice, ArrayList())
        deviceList.adapter = deviceListAdapter
        deviceList.choiceMode = ListView.CHOICE_MODE_SINGLE

        deviceList.setOnItemClickListener { _, _, position, _ ->
            selectedDeviceIp = deviceListAdapter.getItem(position)
            selectedDeviceText.text = "Selected: $selectedDeviceIp"
            selectedDeviceText.visibility = View.VISIBLE
            startCallBtn.isEnabled = true
        }

        addIpBtn.setOnClickListener {
            val ip = manualIpEdit.text?.toString()?.trim()
            if (!TextUtils.isEmpty(ip)) {
                if (!discoveredDevices.contains(ip)) {
                    discoveredDevices.add(ip!!)
                    deviceListAdapter.add(ip)
                    deviceListAdapter.notifyDataSetChanged()
                }
                // auto-select
                selectedDeviceIp = ip
                selectedDeviceText.text = "Selected: $ip"
                selectedDeviceText.visibility = View.VISIBLE
                startCallBtn.isEnabled = true
                manualIpEdit.text = null
            }
        }

        startCallBtn.setOnClickListener {
            val ip = selectedDeviceIp
            if (ip != null) {
                Toast.makeText(this, "Starting RTP call with $ip", Toast.LENGTH_SHORT).show()
                startCall(ip)
                startCallBtn.isEnabled = false
                stopCallBtn.isEnabled = true
                statusText.text = "Status: In call with $ip"
            }
        }

        stopCallBtn.setOnClickListener {
            stopCall()
            startCallBtn.isEnabled = selectedDeviceIp != null
            stopCallBtn.isEnabled = false
            statusText.text = "Status: Call ended"
            Toast.makeText(this, "Call stopped", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPermissionsAndStart() {
        val need = arrayOf(Manifest.permission.RECORD_AUDIO)
        val toAsk = need.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (toAsk.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toAsk.toTypedArray(), PERMISSIONS_REQUEST_CODE)
        } else {
            startDiscovery()
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == PERMISSIONS_REQUEST_CODE) {
            val allGranted = res.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) startDiscovery()
            else {
                Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    // === Discovery ===
    private fun startDiscovery() {
        acquireLocks()
        startDiscoveryListener()
        startDiscoveryBroadcast()
        statusText.text = "Status: Discovering devices..."
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoipRtpDemo::Wake").apply { acquire() }

        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "VoipRtpDemo::Wifi").apply { acquire() }
        multicastLock = wm.createMulticastLock("VoipRtpDemo::MCast").apply { acquire() }
    }

    private fun startDiscoveryListener() {
        discoveryThread = Thread {
            try {
                val socket = DatagramSocket(DISCOVERY_PORT)
                val buf = ByteArray(1024)
                while (!Thread.currentThread().isInterrupted) {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val msg = String(packet.data, 0, packet.length)
                    if (msg.startsWith("HELLO:")) {
                        val ip = msg.substringAfter("HELLO:")
                        runOnUiThread {
                            if (discoveredDevices.add(ip)) {
                                deviceListAdapter.add(ip)
                                deviceListAdapter.notifyDataSetChanged()
                                statusText.text = "Status: Found ${discoveredDevices.size} device(s)"
                            }
                        }
                    }
                }
                socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.apply { isDaemon = true; start() }
    }

    private fun startDiscoveryBroadcast() {
        broadcastThread = Thread {
            try {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val ip = intToIp(wm.connectionInfo.ipAddress)
                val socket = DatagramSocket()
                val bcast = InetAddress.getByName("255.255.255.255")
                while (!Thread.currentThread().isInterrupted) {
                    val msg = "HELLO:$ip"
                    val p = DatagramPacket(msg.toByteArray(), msg.length, bcast, DISCOVERY_PORT)
                    socket.send(p)
                    Thread.sleep(3000)
                }
                socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.apply { isDaemon = true; start() }
    }

    // === RTP helpers (PCMU/PT=0) ===
    private fun createRtpPacket(payload: ByteArray, samples: Int, pt: Int = 0): ByteArray {
        val bb = ByteBuffer.allocate(RTP_HEADER_SIZE + payload.size)

        // V=2,P=0,X=0,CC=0
        bb.put(((RTP_VERSION shl 6)).toByte())

        // M=0, PT=pt (0 = PCMU)
        bb.put((pt and 0x7F).toByte())

        // Seq
        bb.putShort(rtpSequenceNumber.toShort())
        rtpSequenceNumber = (rtpSequenceNumber + 1) and 0xFFFF

        // Timestamp (advance by samples, not bytes)
        bb.putInt(rtpTimestamp)
        rtpTimestamp += samples

        // SSRC
        bb.putInt(rtpSSRC)

        // Payload
        bb.put(payload)
        return bb.array()
    }

    private fun parseRtpPayload(packet: ByteArray): ByteArray {
        return if (packet.size > RTP_HEADER_SIZE) packet.copyOfRange(RTP_HEADER_SIZE, packet.size)
        else ByteArray(0)
    }

    // === G.711 μ-law ===
    private fun ulawEncode(sample: Short): Byte {
        val BIAS = 0x84
        var s = sample.toInt()
        val sign = if (s < 0) { s = -s; 0x80 } else 0x00
        if (s > 32635) s = 32635
        s += BIAS
        var exp = 7; var expMask = 0x4000
        while (exp > 0 && (s and expMask) == 0) { exp--; expMask = expMask shr 1 }
        val mant = (s shr (exp + 3)) and 0x0F
        return (sign or (exp shl 4) or mant).inv().toByte()
    }

    private fun ulawDecode(u: Byte): Short {
        val x = u.toInt().inv() and 0xFF
        val sign = x and 0x80
        val exp = (x shr 4) and 0x07
        val mant = x and 0x0F
        var s = ((mant shl 3) + 0x84) shl exp
        s -= 0x84
        return if (sign != 0) (-s).toShort() else s.toShort()
    }

    private fun pcm16LeToUlaw(src: ByteArray, len: Int): ByteArray {
        val out = ByteArray(len / 2)
        var i = 0
        var j = 0
        while (i + 1 < len) {
            val lo = src[i].toInt() and 0xFF
            val hi = src[i + 1].toInt()
            val s = ((hi shl 8) or lo).toShort()
            out[j++] = ulawEncode(s)
            i += 2
        }
        return out
    }

    private fun ulawToPcm16Le(src: ByteArray): ByteArray {
        val out = ByteArray(src.size * 2)
        var j = 0
        for (b in src) {
            val s = ulawDecode(b).toInt()
            out[j++] = (s and 0xFF).toByte()
            out[j++] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    // === Call start/stop ===
    private fun startCall(peerIp: String) {
        // route audio for comms (echo cancellation works better)
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.isSpeakerphoneOn = true

        // Build AudioRecord
        val recFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val recordBuf = maxOf(FRAME_PCM_BYTES * 4,
            AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "RECORD_AUDIO not granted", Toast.LENGTH_SHORT).show()
            return
        }

        audioRecord = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(recFormat)
            .setBufferSizeInBytes(recordBuf)
            .build()

        enableAEC(audioRecord!!.audioSessionId)

        // Build AudioTrack
        val playFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val playAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val trackBuf = maxOf(FRAME_PCM_BYTES * 8,
            AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT))

        audioTrack = AudioTrack(
            playAttrs, playFormat, trackBuf, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE
        )

        // Sockets
        sendSocket = DatagramSocket()
        recvSocket = DatagramSocket(VOICE_PORT)
        val observerAddr = if (MIRROR_TO_OBSERVER) InetAddress.getByName(OBSERVER_IP) else null
        val target = InetAddress.getByName(peerIp)

        // Reset RTP
        rtpSequenceNumber = Random.nextInt(0, 65536)
        rtpTimestamp = 0

        // Sender thread (20ms frames)
        sendThread = Thread {
            try {
                val frame = ByteArray(FRAME_PCM_BYTES)
                audioRecord!!.startRecording()
                while (!Thread.currentThread().isInterrupted) {
                    var readTotal = 0
                    while (readTotal < frame.size) {
                        val n = audioRecord!!.read(frame, readTotal, frame.size - readTotal, AudioRecord.READ_BLOCKING)
                        if (n <= 0) break
                        readTotal += n
                    }
                    if (readTotal == frame.size) {
                        val ulaw = pcm16LeToUlaw(frame, frame.size) // 160 bytes
                        val rtp = createRtpPacket(ulaw, /*samples*/ ulaw.size, /*PT*/ 0)
                        sendSocket!!.send(DatagramPacket(rtp, rtp.size, target, VOICE_PORT))
                        if (observerAddr != null) {
                            sendSocket!!.send(DatagramPacket(rtp, rtp.size, observerAddr, VOICE_PORT))
                        }
                        // 20ms pacing is governed by blocking read; no extra sleep needed
                    }
                }
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }.apply { isDaemon = true; start() }

        // Receiver thread
        recvThread = Thread {
            try {
                val netBuf = ByteArray(RTP_HEADER_SIZE + 2048)
                audioTrack!!.play()
                while (!Thread.currentThread().isInterrupted) {
                    val dp = DatagramPacket(netBuf, netBuf.size)
                    recvSocket!!.receive(dp)
                    val payload = parseRtpPayload(dp.data.copyOf(dp.length))
                    if (payload.isNotEmpty()) {
                        val pcm = ulawToPcm16Le(payload)
                        audioTrack!!.write(pcm, 0, pcm.size)
                    }
                }
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }.apply { isDaemon = true; start() }
    }

    private fun enableAEC(audioSession: Int) {
        if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(audioSession)?.apply { enabled = true }
        }
    }

    private fun stopCall() {
        sendThread?.interrupt()
        recvThread?.interrupt()
        sendThread = null
        recvThread = null

        sendSocket?.close(); sendSocket = null
        recvSocket?.close(); recvSocket = null

        audioRecord?.let {
            try { it.stop() } catch (_: Throwable) {}
            it.release()
        }
        audioRecord = null

        audioTrack?.let {
            try { it.stop() } catch (_: Throwable) {}
            it.release()
        }
        audioTrack = null
    }

    // === Utils ===
    private fun intToIp(ip: Int): String =
        "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"

    override fun onDestroy() {
        super.onDestroy()
        discoveryThread?.interrupt(); discoveryThread = null
        broadcastThread?.interrupt(); broadcastThread = null
        stopCall()
        try { wakeLock?.release() } catch (_: Throwable) {}
        try { wifiLock?.release() } catch (_: Throwable) {}
        try { multicastLock?.release() } catch (_: Throwable) {}
    }
}