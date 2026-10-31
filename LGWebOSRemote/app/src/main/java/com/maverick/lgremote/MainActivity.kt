
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
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import okhttp3.*
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
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("lg_remote", MODE_PRIVATE) }
    private val trustManagers = arrayOf<TrustManager>(object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    })
    private val client: OkHttpClient by lazy {
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, trustManagers, java.security.SecureRandom())
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustManagers[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }

    private var mainSocket: WebSocket? = null
    private var pointerSocket: WebSocket? = null
    private var tvIp = ""
    private var tvName = "My LG TV"
    private var page = 0
    private var touchMode = false
    private var checking = AtomicBoolean(false)
    private var discoveryRunning = AtomicBoolean(false)
    private var returningFromBackground = false
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var ipInput: EditText
    private lateinit var macInput: EditText

    private val bg = Color.rgb(32, 32, 32)
    private val black = Color.BLACK
    private val panel = Color.rgb(65, 65, 67)
    private val white = Color.WHITE
    private val grey = Color.rgb(155, 155, 158)
    private val red = Color.rgb(244, 48, 56)

    private fun d(v: Float) = (v * resources.displayMetrics.density).toInt()
    private fun shape(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = d(radius).toFloat()
    }
    private fun label(s: String, size: Float = 15f, color: Int = white, bold: Boolean = false) =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            gravity = android.view.Gravity.CENTER
            if (bold) setTypeface(null, Typeface.BOLD)
        }
    private fun control(s: String, color: Int = panel, radius: Float = 28f,
                        size: Float = 20f, action: () -> Unit) =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(white)
            gravity = android.view.Gravity.CENTER
            background = shape(color, radius)
            isClickable = true
            isFocusable = true
            setPadding(d(5f), d(5f), d(5f), d(5f))
            setOnClickListener { action() }
        }
    private fun col() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = android.view.Gravity.CENTER_HORIZONTAL
    }
    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER
    }
    private fun params(w: Int = -1, h: Int = -2, top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply {
            topMargin = d(top.toFloat())
            bottomMargin = d(bottom.toFloat())
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = black
        tvIp = prefs.getString("ip", "") ?: ""
        tvName = prefs.getString("tv_name", "My LG TV") ?: "My LG TV"
        showHome()
        if (tvIp.isBlank()) discoverTvs() else checkSavedTv()
    }

    // HOME PAGE: one persistent TV card, no permanent discovery or polling.
    private fun showHome(message: String? = null) {
        disconnect()
        page = 0
        root = col().apply {
            setBackgroundColor(bg)
            setPadding(d(24f), d(34f), d(24f), d(24f))
        }
        root.addView(label("MY TV", 25f, white, true), params(bottom = 8))
        root.addView(label("Your personal remote", 14f, grey), params(bottom = 48))

        val card = col().apply {
            background = shape(Color.rgb(43, 43, 45), 28f)
            setPadding(d(20f), d(28f), d(20f), d(24f))
            isClickable = true
            isFocusable = true
        }
        card.addView(label("▣", 68f), params(-1, d(110f)))
        card.addView(label(tvName, 21f, white, true), params(bottom = 8))
        status = label(message ?: "Checking connection…", 14f, grey)
        card.addView(status, params(bottom = 22))
        card.addView(control("OPEN REMOTE", black, 24f, 16f) { connect() },
            params(-1, d(54f)))
        card.setOnClickListener { connect() }
        root.addView(card, params(-1, -2))
        root.addView(control("＋  Find / change TV", panel, 22f, 15f) { discoverTvs() },
            params(-1, d(52f), 22))
        root.addView(control("⚙  TV settings", black, 22f, 15f) { showSettings() },
            params(-1, d(52f), 10))
        root.addView(label("Your TV is saved on this phone. Both devices must be on the same Wi-Fi.",
            12f, grey), params(bottom = 8, top = 32))
        setContentView(root)
    }

    private fun homeStatus(s: String, online: Boolean) {
        if (page == 0 && ::status.isInitialized) {
            status.text = "●  $s"
            status.setTextColor(if (online) Color.rgb(80, 205, 130) else grey)
        }
    }

    private fun checkSavedTv() {
        if (tvIp.isBlank() || !checking.compareAndSet(false, true)) return
        homeStatus("Checking TV…", false)
        Thread {
            val online = try {
                Socket().use { it.connect(InetSocketAddress(tvIp, 3000), 1600) }
                true
            } catch (_: Exception) { false }
            runOnUiThread {
                checking.set(false)
                if (page == 0) homeStatus(
                    if (online) "Online · Ready to connect" else "Offline · Check Wi-Fi",
                    online
                )
            }
        }.start()
    }

    private fun discoverTvs() {
        if (!discoveryRunning.compareAndSet(false, true)) return
        if (page == 0) homeStatus("Searching for TVs…", false)
        Thread {
            val found = ArrayList<Pair<String, String>>()
            var lock: android.net.wifi.WifiManager.MulticastLock? = null
            try {
                val wifi = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
                lock = wifi.createMulticastLock("my-tv-discovery").apply {
                    setReferenceCounted(false); acquire()
                }
                DatagramSocket(null).use { socket ->
                    socket.soTimeout = 650
                    socket.bind(InetSocketAddress(0))
                    listOf("ssdp:all", "urn:schemas-upnp-org:device:MediaRenderer:1",
                        "urn:lge-com:service:webos-second-screen:1").forEach { st ->
                        val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                            "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n"
                        val bytes = msg.toByteArray()
                        socket.send(DatagramPacket(bytes, bytes.size,
                            InetAddress.getByName("239.255.255.250"), 1900))
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
                            if (!location.isNullOrBlank()) {
                                val ip = packet.address.hostAddress ?: continue
                                if (found.any { it.first == ip }) continue
                                val name = readDeviceName(location) ?: "LG webOS TV"
                                if (name.contains("LG", true) || name.contains("webOS", true) ||
                                    name.contains("UHD", true)) found.add(ip to name)
                            }
                        } catch (_: SocketTimeoutException) {}
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { if (lock?.isHeld == true) lock?.release() } catch (_: Exception) {}
                discoveryRunning.set(false)
            }
            runOnUiThread {
                if (found.isEmpty()) {
                    if (page == 0) homeStatus("TV not found · Check Wi-Fi and retry", false)
                    AlertDialog.Builder(this).setMessage(
                        "No LG TV was discovered. Check that both devices are on the same Wi-Fi."
                    ).setPositiveButton("Try again") { _, _ -> discoverTvs() }
                        .setNegativeButton("Close", null).show()
                } else {
                    AlertDialog.Builder(this).setTitle("Choose your LG TV")
                        .setItems(found.map { "${it.second}\n${it.first}" }.toTypedArray()) { _, i ->
                            tvIp = found[i].first
                            tvName = found[i].second
                            prefs.edit().putString("ip", tvIp).putString("tv_name", tvName).apply()
                            showHome("TV saved · Checking connection")
                            checkSavedTv()
                        }.setNegativeButton("Cancel", null).show()
                }
            }
        }.start()
    }

    private fun readDeviceName(location: String): String? = try {
        val c = URL(location).openConnection() as HttpURLConnection
        c.connectTimeout = 1200; c.readTimeout = 1200
        val xml = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        val f = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val doc = f.newDocumentBuilder().parse(xml.byteInputStream())
        fun tag(n: String) = doc.getElementsByTagName(n).item(0)?.textContent?.trim() ?: ""
        val manufacturer = tag("manufacturer")
        val model = tag("modelName")
        val friendly = tag("friendlyName").ifBlank { "LG TV" }
        if (manufacturer.contains("LG", true) || model.contains("LG", true) ||
            model.contains("webOS", true) || friendly.contains("LG", true))
            "$friendly · $model" else null
    } catch (_: Exception) { null }

    private fun showSettings() {
        val mac = EditText(this).apply {
            hint = "TV MAC address (optional, for Wake-on-LAN)"
            setSingleLine(true); setTextColor(white); setHintTextColor(grey)
            setText(prefs.getString("mac", ""))
        }
        AlertDialog.Builder(this).setTitle("TV settings")
            .setMessage("Saved TV: $tvName\nIP: $tvIp").setView(mac)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("mac", mac.text.toString().trim()).apply()
            }
            .setNeutralButton("Find another TV") { _, _ -> discoverTvs() }
            .setNegativeButton("Forget TV") { _, _ ->
                disconnect(); prefs.edit().clear().apply()
                tvIp = ""; tvName = "My LG TV"; showHome("No TV paired yet")
                discoverTvs()
            }.show()
    }

    private fun connect() {
        if (tvIp.isBlank()) { discoverTvs(); return }
        disconnect()
        homeStatus("Connecting…", false)
        mainSocket = client.newWebSocket(
            Request.Builder().url("ws://$tvIp:3000").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val savedKey = prefs.getString("key_$tvIp", null)
                    val payload = JSONObject().put("forcePairing", false)
                    if (!savedKey.isNullOrBlank()) payload.put("client-key", savedKey)
                    payload.put("manifest", JSONObject()
                        .put("manifestVersion", 1).put("appVersion", "1.0")
                        .put("permissions", JSONArray(listOf(
                            "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CONTROL_AUDIO",
                            "CONTROL_POWER", "READ_INSTALLED_APPS", "READ_RUNNING_APPS",
                            "READ_TV_CHANNEL_LIST", "CONTROL_MOUSE_AND_KEYBOARD",
                            "READ_NETWORK_STATE"
                        )))
                        .put("signatures", JSONArray().put(JSONObject()
                            .put("signatureVersion", 1)
                            .put("signature", "Maverick-LG-Remote")))
                    webSocket.send(JSONObject().put("type", "register")
                        .put("id", "register_0").put("payload", payload).toString())
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val msg = JSONObject(text)
                        val type = msg.optString("type")
                        val id = msg.optString("id")
                        val payload = msg.optJSONObject("payload") ?: JSONObject()
                        if (type == "registered" || (id == "register_0" && type == "response")) {
                            val key = payload.optString("client-key")
                            if (key.isNotBlank()) prefs.edit().putString("key_$tvIp", key).apply()
                            runOnUiThread { showRemote() }
                            requestPointerSocket()
                        } else if (id == "pointer_socket") {
                            val path = payload.optString("socketPath")
                            if (path.isNotBlank()) {
                                val wsUrl = if (path.startsWith("ws")) path else "ws://$tvIp:3000$path"
                                pointerSocket = client.newWebSocket(Request.Builder().url(wsUrl).build(),
                                    object : WebSocketListener() {
                                        override fun onOpen(webSocket: WebSocket, response: Response) {
                                            runOnUiThread { remoteStatus("Connected to $tvName") }
                                        }
                                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                                            runOnUiThread { remoteStatus("Pointer connection unavailable") }
                                        }
                                    })
                            }
                        } else if (type == "error") {
                            runOnUiThread { showHome("TV rejected connection · Try pairing again") }
                        }
                    } catch (_: Exception) {}
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    runOnUiThread { disconnect(); showHome("Offline · Check Wi-Fi and TV") }
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    runOnUiThread { if (page == 1) showHome("TV disconnected") }
                }
            })
    }

    private fun requestPointerSocket() {
        mainSocket?.send(JSONObject().put("id", "pointer_socket").put("type", "request")
            .put("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket")
            .put("payload", JSONObject()).toString())
    }

    private fun sendButton(name: String) {
        val socket = pointerSocket
        if (socket == null) { remoteStatus("Control connection not ready"); return }
        socket.send("type:button\nname:$name\n\n")
    }

    private fun sendRequest(uri: String, payload: JSONObject = JSONObject()) {
        if (mainSocket == null) { remoteStatus("Connect to your TV first"); return }
        mainSocket?.send(JSONObject().put("id", "cmd_${System.nanoTime()}")
            .put("type", "request").put("uri", uri).put("payload", payload).toString())
    }

    private fun launchApp(id: String) {
        sendRequest("ssap://system.launcher/launch", JSONObject().put("id", id))
    }

    private fun showRemote() {
        page = 1
        root = col().apply {
            setBackgroundColor(bg)
            setPadding(d(14f), d(10f), d(14f), d(10f))
        }

        // Screenshot-style top row: power, Inputs, settings.
        val top = row()
        top.addView(control("⏻", Color.rgb(244, 48, 56), 40f, 27f) {
            AlertDialog.Builder(this).setTitle("Power")
                .setItems(arrayOf("Turn TV off", "Wake TV")) { _, which ->
                    if (which == 0) sendRequest("ssap://system/turnOff") else wakeOnLan()
                }.show()
        }, LinearLayout.LayoutParams(d(56f), d(56f)))
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(control("▣  Inputs  ⌄", black, 38f, 17f) { showInputs() },
            LinearLayout.LayoutParams(d(150f), d(54f)))
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(control("⚙", black, 40f, 25f) { showSettings() },
            LinearLayout.LayoutParams(d(56f), d(56f)))
        root.addView(top, params(bottom = 24))

        // Volume column and media transport controls.
        val transport = row()
        val vol = col().apply {
            background = shape(panel, 34f)
            setPadding(d(7f), d(5f), d(7f), d(5f))
        }
        vol.addView(control("+", panel, 22f, 32f) {
            sendRequest("ssap://audio/volumeUp")
        }, params(d(76f), d(52f)))
        vol.addView(label("VOL", 14f), params(d(76f), d(34f)))
        vol.addView(control("−", panel, 22f, 32f) {
            sendRequest("ssap://audio/volumeDown")
        }, params(d(76f), d(52f)))
        transport.addView(vol, LinearLayout.LayoutParams(d(104f), d(148f)))
        val media = col().apply { setPadding(d(9f), 0, 0, 0) }
        media.addView(control("▶Ⅱ", panel, 55f, 23f) {
            sendRequest("ssap://media.controls/play")
        }, params(-1, d(66f), bottom = 9))
        val seek = row()
        seek.addView(control("◀◀", panel, 45f, 22f) {
            sendRequest("ssap://media.controls/rewind")
        }, LinearLayout.LayoutParams(0, d(66f), 1f))
        seek.addView(View(this), LinearLayout.LayoutParams(d(8f), 1))
        seek.addView(control("▶▶", panel, 45f, 22f) {
            sendRequest("ssap://media.controls/fastForward")
        }, LinearLayout.LayoutParams(0, d(66f), 1f))
        media.addView(seek)
        transport.addView(media, LinearLayout.LayoutParams(0, d(148f), 1f))
        root.addView(transport, params(bottom = 8))

        // Small page dots / mode toggle, matching reference placement.
        val modeRow = row()
        modeRow.addView(label(if (touchMode) "○  ●  ○  ○" else "○  ●  ○  ○", 12f, grey))
        modeRow.setOnClickListener { touchMode = !touchMode; showRemote() }
        root.addView(modeRow, params(bottom = 4))

        val controlArea = FrameLayout(this)
        root.addView(controlArea, LinearLayout.LayoutParams(-1, 0, 1f))
        if (touchMode) controlArea.addView(TouchSurface(this),
            FrameLayout.LayoutParams(-1, -1))
        else controlArea.addView(DirectionalPad(this),
            FrameLayout.LayoutParams(-1, -1))

        // Streaming app shortcuts: restrained marks, not oversized tiles.
        val apps = row().apply {
            setPadding(0, d(4f), 0, d(4f))
        }
        apps.addView(AppMark(this, "N", Color.rgb(229, 9, 20), "Netflix") {
            launchApp("netflix")
        }, LinearLayout.LayoutParams(0, d(58f), 1f))
        apps.addView(AppMark(this, "YT", Color.rgb(255, 35, 48), "YouTube") {
            launchApp("youtube.leanback.v4")
        }, LinearLayout.LayoutParams(0, d(58f), 1f))
        apps.addView(AppMark(this, "prime", Color.rgb(0, 164, 222), "Prime Video") {
            launchApp("amazon")
        }, LinearLayout.LayoutParams(0, d(58f), 1f))
        apps.addView(AppMark(this, "JH", Color.rgb(157, 74, 255), "JioHotstar") {
            launchApp("in.startv.hotstar")
        }, LinearLayout.LayoutParams(0, d(58f), 1f))
        root.addView(apps, params(top = 5, bottom = 5))

        val bottom = row()
        bottom.addView(control("↶", black, 42f, 29f) { sendButton("BACK") },
            LinearLayout.LayoutParams(0, d(58f), 1f))
        bottom.addView(View(this), LinearLayout.LayoutParams(d(9f), 1))
        bottom.addView(control("⌂", black, 42f, 29f) { sendButton("HOME") },
            LinearLayout.LayoutParams(0, d(58f), 1f))
        bottom.addView(View(this), LinearLayout.LayoutParams(d(9f), 1))
        bottom.addView(control("✥", black, 42f, 25f) {
            touchMode = !touchMode
            showRemote()
        }, LinearLayout.LayoutParams(0, d(58f), 1f))
        root.addView(bottom)
        status = label("Connected to $tvName", 11f, grey)
        root.addView(status, params(top = 4))
        setContentView(root)
    }

    private fun remoteStatus(s: String) {
        if (page == 1 && ::status.isInitialized) status.text = s
    }

    private fun showInputs() {
        val items = arrayOf("HDMI 1", "HDMI 2", "HDMI 3", "Live TV")
        val ids = arrayOf("HDMI_1", "HDMI_2", "HDMI_3", "LIVE_TV")
        AlertDialog.Builder(this).setTitle("Inputs").setItems(items) { _, i ->
            sendRequest("ssap://tv/switchInput", JSONObject().put("inputId", ids[i]))
        }.show()
    }

    inner class AppMark(
        context: Context,
        private val mark: String,
        private val accent: Int,
        private val caption: String,
        private val action: () -> Unit
    ) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        init { isClickable = true; setOnClickListener { action() } }
        override fun onDraw(c: Canvas) {
            val cx = width / 2f
            p.textAlign = Paint.Align.CENTER
            when (caption) {
                "Netflix" -> {
                    p.color = Color.rgb(229, 9, 20)
                    p.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                    p.textSize = d(31f).toFloat()
                    c.drawText("N", cx, d(31f).toFloat(), p)
                }
                "YouTube" -> {
                    val w = d(36f).toFloat(); val h = d(24f).toFloat()
                    p.color = Color.rgb(255, 0, 0)
                    c.drawRoundRect(cx-w/2, d(5f).toFloat(), cx+w/2,
                        d(5f).toFloat()+h, d(6f).toFloat(), d(6f).toFloat(), p)
                    val play = Path().apply {
                        moveTo(cx-d(4f), d(10f).toFloat())
                        lineTo(cx-d(4f), d(24f).toFloat())
                        lineTo(cx+d(8f), d(17f).toFloat())
                        close()
                    }
                    p.color = white; c.drawPath(play, p)
                }
                "Prime Video" -> {
                    p.color = Color.rgb(0, 164, 222)
                    p.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    p.textSize = d(18f).toFloat()
                    c.drawText("prime", cx, d(21f).toFloat(), p)
                    p.style = Paint.Style.STROKE; p.strokeWidth = d(1.5f).toFloat()
                    c.drawArc(cx-d(16f), d(16f).toFloat(), cx+d(17f), d(32f).toFloat(),
                        15f, 145f, false, p)
                    p.style = Paint.Style.FILL
                }
                else -> {
                    // Minimal multicolour star mark for JioHotstar.
                    val path = Path()
                    for (i in 0 until 10) {
                        val angle = Math.PI * i / 5.0 - Math.PI / 2.0
                        val radius = d(if (i % 2 == 0) 14f else 6f).toFloat()
                        val x = cx + kotlin.math.cos(angle).toFloat() * radius
                        val y = d(17f).toFloat() + kotlin.math.sin(angle).toFloat() * radius
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close()
                    p.color = Color.rgb(165, 75, 255); c.drawPath(path, p)
                }
            }
            p.color = white
            p.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            p.textSize = d(9f).toFloat()
            c.drawText(caption, cx, height - d(5f).toFloat(), p)
        }
    }

    inner class DirectionalPad(context: Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) * .43f
            p.color = black
            c.drawCircle(cx, cy, r, p)
            p.color = Color.rgb(160, 160, 162)
            c.drawCircle(cx, cy, r * .35f, p)
            p.color = black
            c.drawRoundRect(cx-r*.17f, cy-r*.17f, cx+r*.17f, cy+r*.17f,
                r*.07f, r*.07f, p)
            arrow(c, cx, cy-r*.60f, r*.09f, 0f)
            arrow(c, cx+r*.60f, cy, r*.09f, 90f)
            arrow(c, cx, cy+r*.60f, r*.09f, 180f)
            arrow(c, cx-r*.60f, cy, r*.09f, 270f)
        }
        private fun arrow(c: Canvas, x: Float, y: Float, s: Float, angle: Float) {
            val path = Path().apply {
                moveTo(0f, -s); lineTo(s, s); lineTo(-s, s); close()
            }
            c.save(); c.translate(x, y); c.rotate(angle)
            p.color = grey; c.drawPath(path, p); c.restore()
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action == MotionEvent.ACTION_UP) {
                val dx = e.x - width / 2f
                val dy = e.y - height / 2f
                val r = minOf(width, height) * .43f
                val dist = Math.sqrt((dx*dx + dy*dy).toDouble())
                if (dist < r*.35f) sendButton("ENTER")
                else if (dist < r*1.15f) {
                    if (kotlin.math.abs(dx) > kotlin.math.abs(dy))
                        sendButton(if (dx > 0) "RIGHT" else "LEFT")
                    else sendButton(if (dy > 0) "DOWN" else "UP")
                }
                performClick()
            }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }

    inner class TouchSurface(context: Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var lastX = 0f
        private var lastY = 0f
        private var downX = 0f
        private var downY = 0f
        private var moved = false
        override fun onDraw(c: Canvas) {
            p.color = Color.rgb(19, 19, 19)
            c.drawRoundRect(RectF(d(4f).toFloat(), d(4f).toFloat(),
                width-d(4f).toFloat(), height-d(4f).toFloat()),
                d(28f).toFloat(), d(28f).toFloat(), p)
            p.color = white; p.textSize = d(24f).toFloat()
            c.drawText("◖))", d(24f).toFloat(), d(42f).toFloat(), p)
            c.drawText("✥", d(24f).toFloat(), height-d(24f).toFloat(), p)
            p.color = Color.GRAY
            val x = width-d(43f).toFloat()
            c.drawRoundRect(x, height*.25f, x+d(17f), height*.75f,
                d(12f).toFloat(), d(12f).toFloat(), p)
            p.color = black
            c.drawRoundRect(x+d(2f), height*.43f, x+d(15f), height*.57f,
                d(7f).toFloat(), d(7f).toFloat(), p)
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX=e.x; lastY=e.y; downX=e.x; downY=e.y; moved=false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx=e.x-lastX; val dy=e.y-lastY
                    if (kotlin.math.abs(e.x-downX)>d(5f) ||
                        kotlin.math.abs(e.y-downY)>d(5f)) moved=true
                    if (moved) pointerSocket?.send(
                        "type:move\ndx:${(dx/resources.displayMetrics.density).toInt()}" +
                            "\ndy:${(dy/resources.displayMetrics.density).toInt()}\n\n")
                    lastX=e.x; lastY=e.y
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) pointerSocket?.send("type:click\n\n")
                    performClick(); return true
                }
            }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }

    private fun wakeOnLan() {
        val mac = (prefs.getString("mac", "") ?: "").replace(":", "")
            .replace("-", "").trim()
        if (mac.length != 12 || !mac.matches(Regex("[0-9a-fA-F]{12}"))) {
            remoteStatus("Add TV MAC address in TV settings for Wake-on-LAN")
            return
        }
        Thread {
            try {
                val packetBytes = ByteArray(6) { 0xFF.toByte() } +
                    ByteArray(16) { i ->
                        mac.substring((i%6)*2, (i%6)*2+2).toInt(16).toByte()
                    }
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.send(DatagramPacket(packetBytes, packetBytes.size,
                        InetAddress.getByName("255.255.255.255"), 9))
                }
                runOnUiThread { remoteStatus("Wake packet sent · TV must support network wake") }
            } catch (e: Exception) {
                runOnUiThread { remoteStatus("Wake-on-LAN failed: ${e.message}") }
            }
        }.start()
    }

    private fun disconnect() {
        pointerSocket?.close(1000, "App inactive"); pointerSocket = null
        mainSocket?.close(1000, "App inactive"); mainSocket = null
    }

    override fun onResume() {
        super.onResume()
        if (page == 0 && tvIp.isNotBlank()) checkSavedTv()
        if (page == 1 && returningFromBackground) {
            returningFromBackground = false
            connect()
        }
    }
    override fun onStop() {
        if (page == 1) returningFromBackground = true
        disconnect()
        super.onStop()
    }
    override fun onDestroy() {
        disconnect()
        if (::root.isInitialized) client.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}
