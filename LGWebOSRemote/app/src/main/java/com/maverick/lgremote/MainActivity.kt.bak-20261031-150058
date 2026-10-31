package com.maverick.lgremote

import android.app.Activity
import android.os.Bundle
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.*
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import org.w3c.dom.Document
import javax.xml.parsers.DocumentBuilderFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.net.ssl.SSLSocketFactory
import java.security.cert.X509Certificate

class MainActivity : Activity() {
    // LG TVs may expose a self-signed certificate on their local pointer socket.
    // This app only connects to the user-entered LAN TV address.
    private val client: OkHttpClient = buildClient()

    private fun buildClient(): OkHttpClient {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        })
        val sslContext = SSLContext.getInstance("TLS").apply { init(null, trustAll, java.security.SecureRandom()) }
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAll[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }
    private var mainSocket: WebSocket? = null
    private var pointerSocket: WebSocket? = null
    private var tvIp = ""
    private lateinit var status: TextView
    private lateinit var ipInput: EditText
    private lateinit var macInput: EditText
    private lateinit var devicesBox: LinearLayout
    @Volatile private var discoveryRunning = false
    private val prefs by lazy { getSharedPreferences("lg_remote", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        ipInput.setText(prefs.getString("ip", ""))
        macInput.setText(prefs.getString("mac", ""))
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(20, 18, 20, 16)
            setBackgroundColor(0xFF101318.toInt())
        }
        fun label(text: String, size: Float = 15f): TextView = TextView(this).apply {
            this.text = text; textSize = size; setTextColor(0xFFE9EDF3.toInt())
        }
        val heading = label("LG TV REMOTE", 22f).apply { setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER }
        root.addView(heading, matchWrap())
        root.addView(label("Connect your phone and TV to the same Wi-Fi network, then find your TV."), marginParams())

        ipInput = EditText(this).apply {
            hint = "TV IP address (e.g. 192.168.1.25)"
            setSingleLine(true); textSize = 16f; setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFF9299A5.toInt())
            setPadding(14, 8, 14, 8); setBackgroundColor(0xFF252B34.toInt())
        }
        root.addView(ipInput, marginParams())
        macInput = EditText(this).apply {
            hint = "TV MAC address (optional; for Wake-on-LAN)"
            setSingleLine(true); textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFF9299A5.toInt())
            setPadding(14, 6, 14, 6); setBackgroundColor(0xFF252B34.toInt())
        }
        root.addView(macInput, marginParams())
        val discoverRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        discoverRow.addView(makeButton("FIND LG TVs", 0xFF2878D0.toInt()) { discoverTvs() }, LinearLayout.LayoutParams(0, 52, 1f))
        discoverRow.addView(makeButton("CONNECT", 0xFF267A62.toInt()) { connect() }, LinearLayout.LayoutParams(0, 52, 1f).apply { leftMargin = 10 })
        root.addView(discoverRow, marginParams())
        devicesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(8, 4, 8, 4) }
        root.addView(devicesBox, marginParams())
        root.addView(makeButton("DISCONNECT", 0xFF454D59.toInt()) { disconnect(); setStatus("Disconnected") }, LinearLayout.LayoutParams(-1, 48).apply { topMargin = 4 })
        status = label("Not connected", 13f).apply { gravity = Gravity.CENTER }
        root.addView(status, marginParams())

        root.addView(label("DIRECTIONAL PAD", 13f).apply { gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD) }, marginParams())
        val pad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        pad.addView(padRow(null, "▲" to "UP", null))
        pad.addView(padRow("◀" to "LEFT", "OK" to "ENTER", "▶" to "RIGHT"))
        pad.addView(padRow(null, "▼" to "DOWN", null))
        root.addView(pad, LinearLayout.LayoutParams(-1, 220))

        val homeRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        homeRow.addView(makeButton("⌂  HOME", 0xFF354252.toInt()) { sendButton("HOME") }, LinearLayout.LayoutParams(0, 54, 1f))
        root.addView(homeRow, marginParams())

        val volume = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        volume.addView(makeButton("VOL −", 0xFF354252.toInt()) { sendRequest("ssap://audio/volumeDown") }, LinearLayout.LayoutParams(0, 54, 1f))
        volume.addView(makeButton("VOL +", 0xFF354252.toInt()) { sendRequest("ssap://audio/volumeUp") }, LinearLayout.LayoutParams(0, 54, 1f).apply { leftMargin = 10 })
        root.addView(volume, marginParams())

        val power = LinearLayout(this).apply { gravity = Gravity.CENTER }
        power.addView(makeButton("⏻  POWER OFF", 0xFF9C3C42.toInt()) { sendRequest("ssap://system/turnOff") }, LinearLayout.LayoutParams(0, 54, 1f))
        power.addView(makeButton("⏻  POWER ON", 0xFF267A62.toInt()) { wakeOnLan() }, LinearLayout.LayoutParams(0, 54, 1f).apply { leftMargin = 10 })
        root.addView(power, marginParams())

        val note = label("First connection: approve the access prompt on your TV. Power On uses Wake-on-LAN and requires the TV MAC address plus standby network wake support.", 12f)
        note.gravity = Gravity.CENTER
        root.addView(note, marginParams())
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2)
    private fun marginParams() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8; bottomMargin = 8 }

    private fun makeButton(text: String, color: Int, action: () -> Unit): Button = Button(this).apply {
        this.text = text; textSize = 15f; setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(color)
        isAllCaps = false; setOnClickListener { action() }
    }

    private fun padRow(left: Pair<String, String>?, middle: Pair<String, String>?, right: Pair<String, String>?): View {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        fun add(item: Pair<String, String>?) {
            val b = if (item == null) View(this).apply { visibility = View.INVISIBLE } else makeButton(item.first, if (item.second == "ENTER") 0xFF2878D0.toInt() else 0xFF354252.toInt()) { sendButton(item.second) }
            row.addView(b, LinearLayout.LayoutParams(76, 62).apply { setMargins(5, 4, 5, 4) })
        }
        add(left); add(middle); add(right)
        return row
    }

    private fun discoverTvs() {
        if (discoveryRunning) return
        discoveryRunning = true
        devicesBox.removeAllViews()
        devicesBox.addView(TextView(this).apply { text = "Searching your Wi-Fi network for LG TVs…"; setTextColor(0xFFE9EDF3.toInt()) })
        setStatus("Discovering TVs…")
        Thread {
            val found = CopyOnWriteArrayList<Pair<String, String>>()
            var lock: android.net.wifi.WifiManager.MulticastLock? = null
            try {
                val wifi = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
                lock = wifi.createMulticastLock("lg-tv-discovery").apply { setReferenceCounted(false); acquire() }
                DatagramSocket(null).use { socket ->
                    socket.reuseAddress = true
                    socket.soTimeout = 650
                    socket.bind(java.net.InetSocketAddress(0))
                    val targets = listOf("ssdp:all", "urn:schemas-upnp-org:device:MediaRenderer:1", "urn:lge-com:service:webos-second-screen:1")
                    targets.forEach { st ->
                        val request = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n"
                        val bytes = request.toByteArray()
                        socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("239.255.255.250"), 1900))
                    }
                    val deadline = System.currentTimeMillis() + 6500
                    val buffer = ByteArray(8192)
                    while (System.currentTimeMillis() < deadline) {
                        try {
                            val packet = DatagramPacket(buffer, buffer.size)
                            socket.receive(packet)
                            val headers = String(packet.data, 0, packet.length)
                            val location = headers.lineSequence().firstOrNull { it.startsWith("LOCATION:", true) }?.substringAfter(":")?.trim()
                            if (!location.isNullOrBlank()) {
                                val ip = packet.address.hostAddress ?: continue
                                if (found.any { it.first == ip }) continue
                                val details = readDeviceDescription(location)
                                val label = details ?: "LG webOS TV ($ip)"
                                if (label.contains("LG", true) || label.contains("webOS", true) || label.contains("OLED", true) || label.contains("UHD", true)) found.add(ip to label)
                            }
                        } catch (_: java.net.SocketTimeoutException) { }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { setStatus("Discovery issue: ${e.message}") }
            } finally {
                try { if (lock?.isHeld == true) lock?.release() } catch (_: Exception) { }
                discoveryRunning = false
            }
            runOnUiThread {
                devicesBox.removeAllViews()
                if (found.isEmpty()) {
                    devicesBox.addView(TextView(this).apply { text = "No LG TV found. Check same Wi-Fi; you can enter its IP above."; setTextColor(0xFFFFC66D.toInt()) })
                    setStatus("Discovery finished — no TVs found")
                } else {
                    devicesBox.addView(TextView(this).apply { text = "FOUND DEVICES — tap your TV"; setTextColor(0xFFE9EDF3.toInt()); setTypeface(null, Typeface.BOLD) })
                    found.forEach { (ip, name) ->
                        devicesBox.addView(makeButton("$name  ·  $ip", 0xFF354252.toInt()) {
                            ipInput.setText(ip)
                            setStatus("Selected $name — press CONNECT")
                        }, LinearLayout.LayoutParams(-1, 48).apply { topMargin = 5 })
                    }
                    setStatus("Found ${found.size} possible LG TV(s)")
                }
            }
        }.start()
    }

    private fun readDeviceDescription(location: String): String? {
        return try {
            val connection = URL(location).openConnection() as HttpURLConnection
            connection.connectTimeout = 1200
            connection.readTimeout = 1200
            connection.setRequestProperty("User-Agent", "MyTVRemote/1.0")
            val xml = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            val factory = DocumentBuilderFactory.newInstance()
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
            fun tag(name: String): String? = doc.getElementsByTagName(name).item(0)?.textContent?.trim()?.takeIf { it.isNotBlank() }
            val manufacturer = tag("manufacturer") ?: ""
            val model = tag("modelName") ?: "webOS TV"
            val friendly = tag("friendlyName") ?: "LG TV"
            if (manufacturer.contains("LG", true) || friendly.contains("LG", true) || model.contains("LG", true) || model.contains("webOS", true)) "$friendly — $model" else null
        } catch (_: Exception) { null }
    }

    private fun connect() {
        tvIp = ipInput.text.toString().trim()
        if (tvIp.isBlank()) { setStatus("Enter your TV's IP address"); return }
        prefs.edit().putString("ip", tvIp).putString("mac", macInput.text.toString().trim()).apply()
        disconnect()
        setStatus("Connecting to $tvIp…")
        val request = Request.Builder().url("ws://$tvIp:3000").build()
        mainSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val savedKey = prefs.getString("key_$tvIp", null)
                val payload = JSONObject().put("forcePairing", false)
                if (!savedKey.isNullOrBlank()) payload.put("client-key", savedKey)
                payload.put("manifest", JSONObject()
                    .put("manifestVersion", 1).put("appVersion", "1.0")
                    .put("permissions", org.json.JSONArray(listOf(
                        "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CONTROL_AUDIO", "CONTROL_POWER",
                        "READ_INSTALLED_APPS", "READ_RUNNING_APPS", "READ_TV_CHANNEL_LIST",
                        "CONTROL_MOUSE_AND_KEYBOARD", "READ_NETWORK_STATE"
                    )))
                    .put("signatures", org.json.JSONArray().put(JSONObject().put("signatureVersion", 1).put("signature", "Maverick-LG-Remote"))))
                webSocket.send(JSONObject().put("type", "register").put("id", "register_0").put("payload", payload).toString())
                runOnUiThread { setStatus(if (savedKey == null) "Approve the connection prompt on your TV…" else "Authenticating…") }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    val type = msg.optString("type")
                    val id = msg.optString("id")
                    val payload = msg.optJSONObject("payload") ?: JSONObject()
                    if (type == "registered" || id == "register_0" && msg.optString("type") == "response") {
                        val key = payload.optString("client-key")
                        if (key.isNotBlank()) prefs.edit().putString("key_$tvIp", key).apply()
                        runOnUiThread { setStatus("Connected — setting up controls…") }
                        requestPointerSocket()
                    } else if (id == "pointer_socket") {
                        val socketPath = payload.optString("socketPath")
                        if (socketPath.isNotBlank()) {
                            val url = if (socketPath.startsWith("ws")) socketPath else "ws://$tvIp:3000$socketPath"
                            pointerSocket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                                override fun onOpen(webSocket: WebSocket, response: Response) { runOnUiThread { setStatus("Connected to LG TV") } }
                                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { runOnUiThread { setStatus("Pointer connection failed: ${t.message}") } }
                            })
                        } else runOnUiThread { setStatus("Connected, but TV did not provide its control socket") }
                    } else if (type == "error") {
                        runOnUiThread { setStatus("TV error: ${msg.optString("error", text)}") }
                    }
                } catch (_: Exception) { }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread { setStatus("Connection failed: ${t.message ?: "check TV IP and Wi-Fi"}") }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { runOnUiThread { setStatus("Disconnected") } }
        })
    }

    private fun requestPointerSocket() {
        val req = JSONObject().put("id", "pointer_socket").put("type", "request")
            .put("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket").put("payload", JSONObject())
        mainSocket?.send(req.toString())
    }

    private fun sendButton(name: String) {
        val socket = pointerSocket
        if (socket == null) { setStatus("Connect to the TV first"); return }
        socket.send("type:button\nname:$name\n\n")
    }

    private fun sendRequest(uri: String) {
        if (mainSocket == null) { setStatus("Connect to the TV first"); return }
        val id = "cmd_${System.nanoTime()}"
        mainSocket?.send(JSONObject().put("id", id).put("type", "request").put("uri", uri).put("payload", JSONObject()).toString())
    }

    private fun wakeOnLan() {
        val mac = macInput.text.toString().trim().replace(":", "").replace("-", "")
        if (mac.length != 12 || !mac.matches(Regex("[0-9a-fA-F]{12}"))) { setStatus("Enter a valid TV MAC address for Power On"); return }
        Thread {
            try {
                val bytes = ByteArray(6) { 0xFF.toByte() } + ByteArray(16) { i -> mac.substring((i % 6) * 2, (i % 6) * 2 + 2).toInt(16).toByte() }
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    val packet = DatagramPacket(bytes, bytes.size, InetAddress.getByName("255.255.255.255"), 9)
                    socket.send(packet)
                }
                runOnUiThread { setStatus("Wake-on-LAN packet sent. TV must support network wake in standby.") }
            } catch (e: Exception) { runOnUiThread { setStatus("Wake-on-LAN failed: ${e.message}") } }
        }.start()
    }

    private fun disconnect() {
        pointerSocket?.close(1000, "User disconnected"); pointerSocket = null
        mainSocket?.close(1000, "User disconnected"); mainSocket = null
    }

    private fun setStatus(message: String) { if (::status.isInitialized) status.text = message }
    override fun onDestroy() { disconnect(); client.dispatcher.executorService.shutdown(); super.onDestroy() }
}
