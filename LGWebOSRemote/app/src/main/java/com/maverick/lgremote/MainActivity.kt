package com.maverick.lgremote

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot

/**
 * A deliberately small LG webOS remote.
 *
 * The TV is controlled with LG's second-screen protocol: a WebSocket on port
 * 3000 carries the pairing handshake and SSAP requests, while a second pointer
 * socket carries D-pad, button and trackpad events.
 */
class MainActivity : Activity() {
    companion object {
        private const val PAGE_HOME = 0
        private const val PAGE_REMOTE = 1
        private const val PREFS = "lg_remote"
        private const val TV_PORT = 3000
    }

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private var clientCreated = false
    private val client: OkHttpClient by lazy {
        clientCreated = true
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }

    private var mainSocket: WebSocket? = null
    private var pointerSocket: WebSocket? = null
    private var pendingButton: String? = null
    private var tvIp = ""
    private var tvName = "My LG TV"
    private var page = PAGE_HOME
    private var trackpadMode = false
    private var checking = AtomicBoolean(false)
    private var discovering = AtomicBoolean(false)
    private var returningFromBackground = false

    private lateinit var status: TextView

    private val bg = Color.rgb(15, 17, 21)
    private val card = Color.rgb(26, 29, 35)
    private val panel = Color.rgb(38, 42, 50)
    private val white = Color.rgb(245, 247, 250)
    private val grey = Color.rgb(154, 163, 175)
    private val dim = Color.rgb(110, 118, 130)
    private val green = Color.rgb(52, 211, 153)
    private val red = Color.rgb(239, 68, 68)
    private val blue = Color.rgb(59, 130, 246)
    private val purple = Color.rgb(139, 92, 246)
    private val amber = Color.rgb(245, 158, 11)

    // ---------------------------------------------------------------- helpers

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * resources.displayMetrics.density

    private fun shape(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpf(radius)
    }

    private fun lighten(color: Int, amount: Float = 0.14f): Int {
        fun mix(c: Int) = (c + (255 - c) * amount).toInt().coerceIn(0, 255)
        return Color.rgb(mix(Color.red(color)), mix(Color.green(color)), mix(Color.blue(color)))
    }

    private fun pressable(color: Int, radius: Float): StateListDrawable {
        val normal = shape(color, radius)
        val pressed = shape(lighten(color), radius)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), normal)
        }
    }

    private fun label(
        text: String,
        size: Float = 14f,
        color: Int = white,
        bold: Boolean = false
    ) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        includeFontPadding = false
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun button(
        text: String,
        bgColor: Int = panel,
        textColor: Int = white,
        radius: Float = 18f,
        size: Float = 15f,
        action: () -> Unit
    ) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(textColor)
        gravity = Gravity.CENTER
        includeFontPadding = false
        background = pressable(bgColor, radius)
        isClickable = true
        isFocusable = true
        setPadding(dp(8f), 0, dp(8f), 0)
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            action()
        }
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun lp(
        w: Int = ViewGroup.LayoutParams.MATCH_PARENT,
        h: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        top: Int = 0,
        bottom: Int = 0,
        start: Int = 0,
        end: Int = 0
    ) = LinearLayout.LayoutParams(w, h).apply {
        topMargin = dp(top.toFloat())
        bottomMargin = dp(bottom.toFloat())
        marginStart = dp(start.toFloat())
        marginEnd = dp(end.toFloat())
    }

    private fun fixed(w: Int, h: Int, top: Int = 0) =
        LinearLayout.LayoutParams(dp(w.toFloat()), dp(h.toFloat())).apply {
            topMargin = dp(top.toFloat())
        }

    private fun weighted(height: Int, weight: Float = 1f, start: Int = 0, end: Int = 0) =
        LinearLayout.LayoutParams(0, dp(height.toFloat()), weight).apply {
            marginStart = dp(start.toFloat())
            marginEnd = dp(end.toFloat())
        }

    private fun keyName() = "key_$tvIp"

    // ------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        tvIp = prefs.getString("ip", "") ?: ""
        tvName = prefs.getString("tv_name", "My LG TV") ?: "My LG TV"
        showHome()
        if (tvIp.isNotBlank()) {
            checkSavedTv()
        } else if (!prefs.getBoolean("first_run_done", false)) {
            prefs.edit().putBoolean("first_run_done", true).apply()
            discoverTvs()
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (page == PAGE_REMOTE) showHome() else super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        if (page == PAGE_HOME && tvIp.isNotBlank()) checkSavedTv()
        if (page == PAGE_REMOTE && returningFromBackground) {
            returningFromBackground = false
            connect()
        }
    }

    override fun onStop() {
        if (page == PAGE_REMOTE) returningFromBackground = true
        disconnect()
        super.onStop()
    }

    override fun onDestroy() {
        disconnect()
        if (clientCreated) client.dispatcher.executorService.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ home

    private fun showHome(message: String? = null) {
        disconnect()
        page = PAGE_HOME
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(bg)
            isVerticalScrollBarEnabled = false
        }
        val content = column().apply {
            setPadding(dp(20f), dp(30f), dp(20f), dp(24f))
        }

        content.addView(label("MY TV REMOTE", 12f, dim, true), lp(bottom = 6))
        content.addView(label("Control your LG TV", 25f, white, true), lp(bottom = 26))

        val paired = tvIp.isNotBlank()
        val tvCard = column().apply {
            background = shape(card, 24f)
            setPadding(dp(20f), dp(22f), dp(20f), dp(20f))
            isClickable = true
            isFocusable = true
            setOnClickListener { if (paired) connect() else discoverTvs() }
        }
        tvCard.addView(TvGlyph(this), fixed(104, 96))
        tvCard.addView(label(tvName, 20f, white, true), lp(top = 14, bottom = 4))
        status = label(
            message ?: if (paired) "Checking TV…" else "No TV paired yet",
            13f,
            grey
        )
        tvCard.addView(status, lp(bottom = 18))

        tvCard.addView(
            button(
                if (paired) "OPEN REMOTE" else "FIND MY TV",
                if (paired) blue else panel,
                white,
                16f,
                15f
            ) { if (paired) connect() else discoverTvs() },
            lp(h = dp(50f))
        )
        content.addView(tvCard, lp(top = 0, bottom = 14))

        content.addView(
            button("Find / change TV", panel, white, 18f, 15f) { discoverTvs() },
            lp(h = dp(50f), bottom = 10)
        )
        content.addView(
            button("Settings", panel, white, 18f, 15f) { showSettings() },
            lp(h = dp(50f), bottom = 10)
        )
        content.addView(
            button("Troubleshoot connection", panel, white, 18f, 15f) { runDiagnostics() },
            lp(h = dp(50f))
        )

        content.addView(
            label(
                "Phone and TV must be on the same Wi-Fi.\n" +
                    "First connection asks you to allow access on the TV.",
                12f,
                dim
            ),
            lp(top = 26)
        )

        scroll.addView(content)
        setContentView(scroll)
    }

    private fun homeStatus(text: String, online: Boolean) {
        if (page == PAGE_HOME && ::status.isInitialized) {
            status.text = text
            status.setTextColor(if (online) green else grey)
        }
    }

    private fun checkSavedTv() {
        if (tvIp.isBlank() || !checking.compareAndSet(false, true)) return
        homeStatus("Checking TV…", false)
        Thread {
            val online = try {
                Socket().use { it.connect(InetSocketAddress(tvIp, TV_PORT), 1800) }
                true
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                checking.set(false)
                if (page == PAGE_HOME) {
                    homeStatus(
                        if (online) "Online · Ready to connect" else "Offline · Check Wi-Fi",
                        online
                    )
                }
            }
        }.start()
    }

    // ------------------------------------------------------------- discovery

    private fun discoverTvs() {
        if (!discovering.compareAndSet(false, true)) return
        if (page == PAGE_HOME) homeStatus("Searching for TVs…", false)
        Thread {
            val found = ArrayList<Found>()
            var lock: android.net.wifi.WifiManager.MulticastLock? = null
            try {
                val wifi = applicationContext
                    .getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
                lock = wifi.createMulticastLock("my-tv-discovery").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                DatagramSocket(null).use { socket ->
                    socket.soTimeout = 650
                    socket.bind(InetSocketAddress(0))
                    listOf(
                        "ssdp:all",
                        "urn:schemas-upnp-org:device:MediaRenderer:1",
                        "urn:lge-com:service:webos-second-screen:1"
                    ).forEach { st ->
                        val message = "M-SEARCH * HTTP/1.1\r\n" +
                            "HOST: 239.255.255.250:1900\r\n" +
                            "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n"
                        val bytes = message.toByteArray()
                        socket.send(
                            DatagramPacket(
                                bytes,
                                bytes.size,
                                InetAddress.getByName("239.255.255.250"),
                                1900
                            )
                        )
                    }
                    val deadline = System.currentTimeMillis() + 5500
                    val buffer = ByteArray(8192)
                    while (System.currentTimeMillis() < deadline) {
                        try {
                            val packet = DatagramPacket(buffer, buffer.size)
                            socket.receive(packet)
                            val response = String(packet.data, 0, packet.length)
                            val location = response.lineSequence()
                                .firstOrNull { it.startsWith("LOCATION:", true) }
                                ?.substringAfter(":")?.trim()
                            if (location.isNullOrBlank()) continue
                            val ip = packet.address.hostAddress ?: continue
                            if (found.any { it.ip == ip }) continue
                            val info = readDeviceInfo(location)
                            found.add(
                                Found(
                                    ip,
                                    info?.first ?: "Device at $ip",
                                    info?.second ?: false
                                )
                            )
                        } catch (_: SocketTimeoutException) {
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                try {
                    if (lock?.isHeld == true) lock?.release()
                } catch (_: Exception) {
                }
                discovering.set(false)
            }
            runOnUiThread {
                showDiscoveredTvs(
                    found.sortedWith(
                        compareByDescending<Found> { it.isLg }.thenBy { it.name.lowercase() }
                    )
                )
            }
        }.start()
    }

    private data class Found(val ip: String, val name: String, val isLg: Boolean)

    private fun showDiscoveredTvs(found: List<Found>) {
        if (found.isEmpty()) {
            if (page == PAGE_HOME) homeStatus("No TV found · Check Wi-Fi", false)
            AlertDialog.Builder(this)
                .setTitle("No LG TV found")
                .setMessage(
                    "Make sure the TV is on and this phone is on the same Wi-Fi.\n" +
                        "Some routers block discovery, so you can also enter the TV IP directly."
                )
                .setPositiveButton("Try again") { _, _ -> discoverTvs() }
                .setNeutralButton("Enter IP") { _, _ -> promptManualIp() }
                .setNegativeButton("Close", null)
                .show()
            return
        }
        if (page == PAGE_HOME) homeStatus("Found ${found.size} device(s)", true)
        AlertDialog.Builder(this)
            .setTitle("Choose your TV")
            .setItems(
                found.map {
                    (if (it.isLg) "LG · " else "") + it.name + "\n" + it.ip
                }.toTypedArray()
            ) { _, index ->
                saveTv(found[index].ip, found[index].name)
                showHome("TV saved · Connecting…")
                connect()
            }
            .setNeutralButton("Enter IP") { _, _ -> promptManualIp() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun readDeviceInfo(location: String): Pair<String, Boolean>? = try {
        val connection = URL(location).openConnection() as HttpURLConnection
        connection.connectTimeout = 1400
        connection.readTimeout = 1400
        val xml = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
        fun tag(name: String) =
            doc.getElementsByTagName(name).item(0)?.textContent?.trim() ?: ""
        val manufacturer = tag("manufacturer")
        val model = tag("modelName")
        val friendly = tag("friendlyName")
        val isLg = manufacturer.contains("LG", true) ||
            model.contains("LG", true) ||
            model.contains("webOS", true) ||
            friendly.contains("LG", true)
        val name = friendly.ifBlank { model }.ifBlank { "Device" }
        name to isLg
    } catch (_: Exception) {
        null
    }

    private fun saveTv(ip: String, name: String) {
        tvIp = ip
        tvName = name.ifBlank { "LG webOS TV" }
        prefs.edit().putString("ip", tvIp).putString("tv_name", tvName).apply()
    }

    private fun promptManualIp() {
        val input = EditText(this).apply {
            hint = "e.g. 192.168.1.42"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setTextColor(white)
            setHintTextColor(dim)
            setText(if (tvIp.isNotBlank()) tvIp else "")
            setPadding(dp(4f), dp(10f), dp(4f), dp(10f))
        }
        AlertDialog.Builder(this)
            .setTitle("Connect by IP")
            .setMessage("Find the TV IP in TV Settings → Network → Wi-Fi Connection.")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) {
                    saveTv(ip, if (tvName.isBlank()) "LG webOS TV" else tvName)
                    showHome("Connecting…")
                    connect()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------ connection

    private fun connect() {
        if (tvIp.isBlank()) {
            discoverTvs()
            return
        }
        disconnect()
        homeStatus("Connecting…", false)
        val request = Request.Builder().url("ws://$tvIp:$TV_PORT").build()
        mainSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val savedKey = prefs.getString(keyName(), null)
                val permissions = JSONArray(
                    listOf(
                        "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CONTROL_AUDIO",
                        "CONTROL_POWER", "READ_INSTALLED_APPS", "READ_RUNNING_APPS",
                        "READ_TV_CHANNEL_LIST", "CONTROL_MOUSE_AND_KEYBOARD",
                        "READ_NETWORK_STATE"
                    )
                )
                val manifest = JSONObject()
                    .put("manifestVersion", 1)
                    .put("appVersion", "1.2")
                    .put("permissions", permissions)
                    .put(
                        "signatures",
                        JSONArray().put(
                            JSONObject()
                                .put("signatureVersion", 1)
                                .put("signature", "MyTVRemote")
                        )
                    )
                val payload = JSONObject()
                    .put("forcePairing", false)
                    .put("pairingType", "PROMPT")
                    .put("manifest", manifest)
                if (!savedKey.isNullOrBlank()) payload.put("client-key", savedKey)
                webSocket.send(
                    JSONObject()
                        .put("type", "register")
                        .put("id", "register_0")
                        .put("payload", payload)
                        .toString()
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket === mainSocket) handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread {
                    if (webSocket !== mainSocket || isFinishing || isDestroyed) return@runOnUiThread
                    disconnect()
                    val detail = t.message?.take(90) ?: "connection failed"
                    showHome(
                        if (page == PAGE_REMOTE) "TV disconnected · $detail"
                        else "Could not reach TV · $detail"
                    )
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread {
                    if (webSocket !== mainSocket || isFinishing || isDestroyed) return@runOnUiThread
                    if (page == PAGE_REMOTE) showHome("TV disconnected")
                }
            }
        })
    }

    private fun handleMessage(text: String) {
        try {
            val message = JSONObject(text)
            val type = message.optString("type")
            val id = message.optString("id")
            val payload = message.optJSONObject("payload") ?: JSONObject()
            when {
                type == "registered" || (id == "register_0" && type == "response") -> {
                    val key = payload.optString("client-key")
                    if (key.isNotBlank()) prefs.edit().putString(keyName(), key).apply()
                    runOnUiThread {
                        showRemote()
                        remoteStatus("Connected · Ready")
                    }
                    requestPointerSocket()
                }

                id == "pointer_socket" -> {
                    val path = payload.optString("socketPath")
                    if (path.isNotBlank()) openPointerSocket(path)
                }

                type == "error" -> {
                    val reason = payload.optString("errorText")
                        .ifBlank { "TV rejected the connection · Try pairing again" }
                    runOnUiThread { showHome(reason) }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun requestPointerSocket() {
        mainSocket?.send(
            JSONObject()
                .put("id", "pointer_socket")
                .put("type", "request")
                .put("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket")
                .put("payload", JSONObject())
                .toString()
        )
    }

    private fun openPointerSocket(path: String) {
        pointerSocket?.close(1000, null)
        val url = if (path.startsWith("ws")) path else "ws://$tvIp:$TV_PORT$path"
        pointerSocket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    pendingButton?.let {
                        webSocket.send("type:button\nname:$it\n\n")
                        pendingButton = null
                    }
                    runOnUiThread { remoteStatus("Connected · Ready") }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    runOnUiThread {
                        if (webSocket === pointerSocket) {
                            remoteStatus("Control unavailable · Tap a button to retry")
                        }
                    }
                }
            }
        )
    }

    private fun disconnect() {
        pendingButton = null
        pointerSocket?.close(1000, "App inactive")
        pointerSocket = null
        mainSocket?.close(1000, "App inactive")
        mainSocket = null
    }

    private fun sendButton(name: String) {
        val socket = pointerSocket
        if (socket == null) {
            pendingButton = name
            remoteStatus("Control not ready · Retrying…")
            requestPointerSocket()
            return
        }
        socket.send("type:button\nname:$name\n\n")
    }

    private fun sendRequest(
        uri: String,
        payload: JSONObject = JSONObject(),
        id: String = "cmd_${System.nanoTime()}"
    ) {
        val socket = mainSocket
        if (socket == null) {
            remoteStatus("Not connected")
            return
        }
        socket.send(
            JSONObject()
                .put("id", id)
                .put("type", "request")
                .put("uri", uri)
                .put("payload", payload)
                .toString()
        )
    }

    private fun launchApp(id: String) {
        sendRequest("ssap://system.launcher/launch", JSONObject().put("id", id))
    }

    // ---------------------------------------------------------------- remote

    private fun showRemote() {
        page = PAGE_REMOTE
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(bg)
            isVerticalScrollBarEnabled = false
        }
        val content = column().apply {
            setPadding(dp(16f), dp(12f), dp(16f), dp(14f))
        }

        val top = row()
        top.addView(
            button("⏻", red, white, 26f, 21f) { showPowerDialog() },
            fixed(52, 52)
        )
        val titles = column().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), 0, dp(8f), 0)
        }
        titles.addView(label(tvName, 15f, white, true), lp())
        status = label("Connecting…", 11f, grey)
        titles.addView(status, lp(top = 2))
        top.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(
            button("Input", panel, white, 14f, 12f) { showInputs() },
            fixed(58, 44)
        )
        top.addView(View(this), LinearLayout.LayoutParams(dp(8f), 1))
        top.addView(
            button("⚙", panel, white, 14f, 17f) { showSettings() },
            fixed(44, 44)
        )
        content.addView(top, lp(bottom = 12))

        val padSize = minOf(
            resources.displayMetrics.widthPixels - dp(32f),
            dp(300f)
        )
        if (trackpadMode) {
            content.addView(
                Trackpad(this),
                LinearLayout.LayoutParams(padSize, padSize).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            )
        } else {
            content.addView(
                DirectionalPad(this),
                LinearLayout.LayoutParams(padSize, padSize).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            )
        }

        val quick = row()
        quick.addView(
            button("VOL −", panel, white, 14f, 12f) {
                sendRequest("ssap://audio/volumeDown")
            },
            weighted(48, 1f, end = 3)
        )
        quick.addView(
            button("MUTE", panel, white, 14f, 11f) { sendButton("MUTE") },
            weighted(48, 1f, start = 3, end = 3)
        )
        quick.addView(
            button("VOL +", panel, white, 14f, 12f) {
                sendRequest("ssap://audio/volumeUp")
            },
            weighted(48, 1f, start = 3, end = 3)
        )
        quick.addView(
            button("CH −", panel, white, 14f, 12f) { sendButton("CHANNELDOWN") },
            weighted(48, 1f, start = 3, end = 3)
        )
        quick.addView(
            button("CH +", panel, white, 14f, 12f) { sendButton("CHANNELUP") },
            weighted(48, 1f, start = 3)
        )
        content.addView(quick, lp(top = 12, bottom = 8))

        val media = row()
        media.addView(
            button("⏮", panel, white, 14f, 17f) {
                sendRequest("ssap://media.controls/rewind")
            },
            weighted(46, 1f, end = 4)
        )
        media.addView(
            button("⏯", panel, white, 14f, 17f) { sendButton("PLAY") },
            weighted(46, 1f, start = 4, end = 4)
        )
        media.addView(
            button("⏭", panel, white, 14f, 17f) {
                sendRequest("ssap://media.controls/fastForward")
            },
            weighted(46, 1f, start = 4)
        )
        content.addView(media, lp(bottom = 10))

        val apps = row()
        apps.addView(
            appTile("N", "Netflix", red) { launchApp("netflix") },
            weighted(62, 1f)
        )
        apps.addView(
            appTile("▶", "YouTube", Color.rgb(220, 38, 38)) {
                launchApp("youtube.leanback.v4")
            },
            weighted(62, 1f)
        )
        apps.addView(
            appTile("p", "Prime", Color.rgb(0, 164, 222)) { launchApp("amazon") },
            weighted(62, 1f)
        )
        apps.addView(
            appTile("H", "Hotstar", purple) { launchApp("in.startv.hotstar") },
            weighted(62, 1f)
        )
        content.addView(apps, lp(bottom = 10))

        val bottom = row()
        bottom.addView(
            button("Back", panel, white, 14f, 13f) { sendButton("BACK") },
            weighted(50, 1f, end = 4)
        )
        bottom.addView(
            button("Home", panel, white, 14f, 13f) { sendButton("HOME") },
            weighted(50, 1f, start = 4, end = 4)
        )
        bottom.addView(
            button(if (trackpadMode) "D-Pad" else "Trackpad", panel, white, 14f, 12f) {
                trackpadMode = !trackpadMode
                showRemote()
                remoteStatus(if (trackpadMode) "Trackpad · Swipe to move" else "D-Pad")
            },
            weighted(50, 1f, start = 4, end = 4)
        )
        bottom.addView(
            button("Keys", panel, white, 14f, 13f) { showTextInput() },
            weighted(50, 1f, start = 4)
        )
        content.addView(bottom)

        scroll.addView(content)
        setContentView(scroll)
    }

    private fun remoteStatus(text: String) {
        if (page == PAGE_REMOTE && ::status.isInitialized) {
            status.text = text
            status.setTextColor(if (text.contains("Ready") || text.contains("Connected")) green else grey)
        }
    }

    private fun appTile(
        mark: String,
        caption: String,
        color: Int,
        action: () -> Unit
    ): LinearLayout {
        val tile = column().apply {
            isClickable = true
            isFocusable = true
            setPadding(0, dp(2f), 0, dp(2f))
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                action()
            }
        }
        tile.addView(
            label(mark, 16f, white, true).apply {
                background = pressable(color, 15f)
                gravity = Gravity.CENTER
            },
            fixed(46, 46)
        )
        tile.addView(label(caption, 9f, dim), lp(top = 4))
        return tile
    }

    // ---------------------------------------------------------------- dialogs

    private fun showPowerDialog() {
        AlertDialog.Builder(this)
            .setTitle("Power")
            .setItems(arrayOf("Turn TV off", "Wake TV (Wake-on-LAN)")) { _, which ->
                if (which == 0) {
                    sendRequest("ssap://system/turnOff")
                    remoteStatus("Sending power off…")
                } else {
                    wakeOnLan()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showInputs() {
        val items = arrayOf("HDMI 1", "HDMI 2", "HDMI 3", "HDMI 4", "Live TV")
        val ids = arrayOf("HDMI_1", "HDMI_2", "HDMI_3", "HDMI_4", "LIVE_TV")
        AlertDialog.Builder(this)
            .setTitle("Inputs")
            .setItems(items) { _, index ->
                sendRequest("ssap://tv/switchInput", JSONObject().put("inputId", ids[index]))
                remoteStatus("Switching input…")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTextInput() {
        val input = EditText(this).apply {
            hint = "Text to type on the TV"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setTextColor(white)
            setHintTextColor(dim)
            setPadding(dp(4f), dp(10f), dp(4f), dp(10f))
        }
        AlertDialog.Builder(this)
            .setTitle("Send text to TV")
            .setView(input)
            .setPositiveButton("Send") { _, _ ->
                val text = input.text.toString()
                if (text.isNotEmpty()) {
                    sendRequest(
                        "ssap://com.webos.service.ime/insertText",
                        JSONObject().put("text", text).put("replace", true)
                    )
                    remoteStatus("Text sent")
                }
            }
            .setNeutralButton("Send + Enter") { _, _ ->
                val text = input.text.toString()
                if (text.isNotEmpty()) {
                    sendRequest(
                        "ssap://com.webos.service.ime/insertText",
                        JSONObject().put("text", text).put("replace", true)
                    )
                    sendRequest("ssap://com.webos.service.ime/sendEnterKey")
                    remoteStatus("Text sent")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSettings() {
        val items = arrayOf(
            "Rename TV",
            "Enter IP manually",
            "Set MAC for Wake-on-LAN",
            "Find another TV",
            "Test connection",
            "Forget TV"
        )
        AlertDialog.Builder(this)
            .setTitle("TV settings")
            .setItems(items) { _, index ->
                when (index) {
                    0 -> promptRename()
                    1 -> promptManualIp()
                    2 -> promptMac()
                    3 -> discoverTvs()
                    4 -> runDiagnostics()
                    5 -> forgetTv()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun promptRename() {
        val input = EditText(this).apply {
            hint = "TV name"
            setSingleLine(true)
            setTextColor(white)
            setHintTextColor(dim)
            setText(tvName)
            setPadding(dp(4f), dp(10f), dp(4f), dp(10f))
        }
        AlertDialog.Builder(this)
            .setTitle("Rename TV")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    tvName = name
                    prefs.edit().putString("tv_name", name).apply()
                    if (page == PAGE_REMOTE) showRemote() else showHome()
                    remoteStatus("Renamed")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptMac() {
        val input = EditText(this).apply {
            hint = "AA:BB:CC:DD:EE:FF"
            setSingleLine(true)
            setTextColor(white)
            setHintTextColor(dim)
            setText(prefs.getString("mac", "") ?: "")
            setPadding(dp(4f), dp(10f), dp(4f), dp(10f))
        }
        AlertDialog.Builder(this)
            .setTitle("Wake-on-LAN MAC")
            .setMessage("TV Settings → Network → Wired/Wi-Fi → MAC address")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("mac", input.text.toString().trim()).apply()
                remoteStatus("MAC saved")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun forgetTv() {
        disconnect()
        prefs.edit().clear().apply()
        tvIp = ""
        tvName = "My LG TV"
        showHome("No TV paired yet")
        discoverTvs()
    }

    private fun wakeOnLan() {
        val mac = (prefs.getString("mac", "") ?: "")
            .replace(":", "")
            .replace("-", "")
            .replace(" ", "")
            .trim()
        if (mac.length != 12 || !mac.matches(Regex("[0-9a-fA-F]{12}"))) {
            remoteStatus("Add the TV MAC in Settings first")
            AlertDialog.Builder(this)
                .setTitle("MAC address needed")
                .setMessage(
                    "Wake-on-LAN needs the TV MAC address.\n" +
                        "Add it from Settings → Set MAC for Wake-on-LAN."
                )
                .setPositiveButton("Open settings") { _, _ -> promptMac() }
                .setNegativeButton("Close", null)
                .show()
            return
        }
        remoteStatus("Sending wake packet…")
        Thread {
            try {
                val macBytes = ByteArray(6) { i ->
                    mac.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
                val packet = ByteArray(6) { 0xFF.toByte() } + ByteArray(16) { macBytes[it % 6] }
                val local = localIpv4()
                val directed = if (local.count { it == '.' } == 3) {
                    local.substringBeforeLast('.') + ".255"
                } else {
                    null
                }
                val targets = listOfNotNull("255.255.255.255", directed).distinct()
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    repeat(5) {
                        for (target in targets) {
                            for (port in intArrayOf(9, 7)) {
                                try {
                                    socket.send(
                                        DatagramPacket(
                                            packet,
                                            packet.size,
                                            InetAddress.getByName(target),
                                            port
                                        )
                                    )
                                } catch (_: Exception) {
                                }
                            }
                        }
                        Thread.sleep(150)
                    }
                }
                runOnUiThread {
                    remoteStatus("Wake sent to ${targets.joinToString()} · TV must allow network wake")
                }
            } catch (e: Exception) {
                runOnUiThread { remoteStatus("Wake failed: ${e.message}") }
            }
        }.start()
    }

    // ----------------------------------------------------------- diagnostics

    private fun runDiagnostics() {
        if (tvIp.isBlank()) {
            AlertDialog.Builder(this)
                .setTitle("Connection test")
                .setMessage("No TV address saved yet. Find your TV or enter its IP first.")
                .setPositiveButton("Enter IP") { _, _ -> promptManualIp() }
                .setNegativeButton("Close", null)
                .show()
            return
        }
        val ip = tvIp
        val progress = AlertDialog.Builder(this)
            .setTitle("Connection test")
            .setMessage("Testing $ip…")
            .setNegativeButton("Close", null)
            .show()
        Thread {
            val report = StringBuilder()
            report.append("Phone: ").append(localIpv4()).append('\n')
            report.append("TV:    ").append(ip).append("\n\n")
            for (port in intArrayOf(3000, 3001, 1900)) {
                val start = System.currentTimeMillis()
                val result = try {
                    Socket().use { it.connect(InetSocketAddress(ip, port), 2500) }
                    "open"
                } catch (e: Exception) {
                    "failed (${(e.message ?: e.javaClass.simpleName).take(70)})"
                }
                report.append("TCP ").append(port).append(": ").append(result)
                    .append(" [").append(System.currentTimeMillis() - start).append(" ms]\n")
            }
            report.append('\n')
            val http = try {
                val connection = URL("http://$ip:3000/").openConnection() as HttpURLConnection
                connection.connectTimeout = 2500
                connection.readTimeout = 2500
                "HTTP ${connection.responseCode}"
            } catch (e: Exception) {
                "failed (${(e.message ?: e.javaClass.simpleName).take(70)})"
            }
            report.append("TV web server: ").append(http).append("\n\n")
            report.append("SSDP replies (4s):\n")
            val devices = quickDiscovery()
            if (devices.isEmpty()) {
                report.append("none · router is blocking multicast\n")
            } else {
                devices.forEach { report.append("• ").append(it).append('\n') }
            }
            report.append("\nIf TCP 3000 fails, turn on \"Mobile TV On\" / \"LG Connect Apps\" ")
                .append("in the TV network settings and make sure both devices share the same Wi-Fi.")
            runOnUiThread {
                progress.dismiss()
                AlertDialog.Builder(this)
                    .setTitle("Connection test")
                    .setMessage(report.toString())
                    .setPositiveButton("Copy") { _, _ ->
                        val clipboard =
                            getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("Connection test", report.toString())
                        )
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }.start()
    }

    private fun quickDiscovery(): List<String> {
        val found = LinkedHashSet<String>()
        var lock: android.net.wifi.WifiManager.MulticastLock? = null
        try {
            val wifi = applicationContext
                .getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
            lock = wifi.createMulticastLock("my-tv-diagnostics").apply {
                setReferenceCounted(false)
                acquire()
            }
            DatagramSocket(null).use { socket ->
                socket.soTimeout = 500
                socket.bind(InetSocketAddress(0))
                val message = "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\nMX: 1\r\nST: ssdp:all\r\n\r\n"
                val bytes = message.toByteArray()
                socket.send(
                    DatagramPacket(
                        bytes,
                        bytes.size,
                        InetAddress.getByName("239.255.255.250"),
                        1900
                    )
                )
                val deadline = System.currentTimeMillis() + 4000
                val buffer = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        packet.address.hostAddress?.let { found.add(it) }
                    } catch (_: SocketTimeoutException) {
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                if (lock?.isHeld == true) lock?.release()
            } catch (_: Exception) {
            }
        }
        return found.toList()
    }

    private fun localIpv4(): String = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
            ?.hostAddress ?: "unknown"
    } catch (_: Exception) {
        "unknown"
    }

    // ------------------------------------------------------------- custom views

    private inner class TvGlyph(context: Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val left = w * 0.06f
            val top = h * 0.14f
            val right = w * 0.72f
            val bottom = h * 0.68f

            p.style = Paint.Style.FILL
            p.color = Color.rgb(30, 41, 59)
            c.drawRoundRect(RectF(left, top, right, bottom), dpf(9f), dpf(9f), p)

            p.style = Paint.Style.STROKE
            p.strokeWidth = dpf(2f)
            p.color = blue
            c.drawRoundRect(RectF(left, top, right, bottom), dpf(9f), dpf(9f), p)

            p.style = Paint.Style.FILL
            p.color = blue
            c.drawRect(w * 0.36f, bottom, w * 0.42f, h * 0.76f, p)
            c.drawRoundRect(
                RectF(w * 0.26f, h * 0.76f, w * 0.52f, h * 0.81f),
                dpf(3f),
                dpf(3f),
                p
            )

            p.color = white
            c.drawRoundRect(
                RectF(w * 0.78f, h * 0.22f, w * 0.93f, h * 0.84f),
                dpf(6f),
                dpf(6f),
                p
            )
            p.color = bg
            c.drawCircle(w * 0.855f, h * 0.36f, dpf(2.6f), p)
            c.drawCircle(w * 0.855f, h * 0.49f, dpf(2.6f), p)
            c.drawCircle(w * 0.855f, h * 0.62f, dpf(2.6f), p)
            c.drawCircle(w * 0.855f, h * 0.75f, dpf(2.6f), p)
        }
    }

    private inner class DirectionalPad(context: Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var pressedZone = 0

        init {
            isClickable = true
        }

        private fun radius() = minOf(width, height) * 0.46f

        private fun zoneAt(x: Float, y: Float): Int {
            val dx = x - width / 2f
            val dy = y - height / 2f
            val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            val r = radius()
            if (distance > r * 1.05f) return 0
            if (distance <= r * 0.34f) return 5
            return if (abs(dx) > abs(dy)) {
                if (dx > 0) 2 else 4
            } else {
                if (dy > 0) 3 else 1
            }
        }

        override fun onDraw(c: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = radius()

            p.style = Paint.Style.FILL
            p.color = card
            c.drawCircle(cx, cy, r, p)

            p.style = Paint.Style.STROKE
            p.strokeWidth = dpf(1.2f)
            p.color = Color.rgb(45, 50, 60)
            c.drawCircle(cx, cy, r, p)

            p.style = Paint.Style.FILL
            p.color = if (pressedZone == 5) blue else panel
            c.drawCircle(cx, cy, r * 0.34f, p)

            p.color = white
            p.textAlign = Paint.Align.CENTER
            p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            p.textSize = r * 0.20f
            val baseline = cy - (p.descent() + p.ascent()) / 2f
            c.drawText("OK", cx, baseline, p)

            arrow(c, cx, cy - r * 0.64f, r * 0.11f, 0f, if (pressedZone == 1) blue else grey)
            arrow(c, cx + r * 0.64f, cy, r * 0.11f, 90f, if (pressedZone == 2) blue else grey)
            arrow(c, cx, cy + r * 0.64f, r * 0.11f, 180f, if (pressedZone == 3) blue else grey)
            arrow(c, cx - r * 0.64f, cy, r * 0.11f, 270f, if (pressedZone == 4) blue else grey)
        }

        private fun arrow(c: Canvas, x: Float, y: Float, size: Float, angle: Float, color: Int) {
            val path = Path().apply {
                moveTo(0f, -size)
                lineTo(size, size)
                lineTo(-size, size)
                close()
            }
            c.save()
            c.translate(x, y)
            c.rotate(angle)
            p.style = Paint.Style.FILL
            p.color = color
            c.drawPath(path, p)
            c.restore()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressedZone = zoneAt(event.x, event.y)
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val zone = zoneAt(event.x, event.y)
                    if (zone != pressedZone) {
                        pressedZone = zone
                        invalidate()
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    val zone = zoneAt(event.x, event.y)
                    if (zone == pressedZone && zone != 0) sendButton(buttonForZone(zone))
                    pressedZone = 0
                    invalidate()
                    performClick()
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    pressedZone = 0
                    invalidate()
                    return true
                }
            }
            return true
        }

        private fun buttonForZone(zone: Int) = when (zone) {
            1 -> "UP"
            2 -> "RIGHT"
            3 -> "DOWN"
            4 -> "LEFT"
            else -> "ENTER"
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    private inner class Trackpad(context: Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var lastX = 0f
        private var lastY = 0f
        private var downX = 0f
        private var downY = 0f
        private var moved = false

        init {
            isClickable = true
        }

        override fun onDraw(c: Canvas) {
            val inset = dpf(2f)
            val bounds = RectF(inset, inset, width - inset, height - inset)
            p.style = Paint.Style.FILL
            p.color = card
            c.drawRoundRect(bounds, dpf(28f), dpf(28f), p)

            p.style = Paint.Style.STROKE
            p.strokeWidth = dpf(1.2f)
            p.color = Color.rgb(45, 50, 60)
            c.drawRoundRect(bounds, dpf(28f), dpf(28f), p)

            p.style = Paint.Style.FILL
            p.textAlign = Paint.Align.CENTER
            p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            p.color = white
            p.textSize = dpf(15f)
            c.drawText("Swipe to move", width / 2f, height / 2f - dpf(4f), p)
            p.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            p.color = dim
            p.textSize = dpf(12f)
            c.drawText("Tap to click", width / 2f, height / 2f + dpf(18f), p)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    downX = event.x
                    downY = event.y
                    moved = false
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (abs(event.x - downX) > dpf(6f) || abs(event.y - downY) > dpf(6f)) {
                        moved = true
                    }
                    if (moved) {
                        pointerSocket?.send(
                            "type:move\ndx:${dx.toInt()}\ndy:${dy.toInt()}\n\n"
                        )
                    }
                    lastX = event.x
                    lastY = event.y
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) pointerSocket?.send("type:click\n\n")
                    performClick()
                    return true
                }
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }
}
