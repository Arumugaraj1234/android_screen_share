package com.mip.mdm.admin

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.*
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.*
import org.json.JSONObject
import org.webrtc.*

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenShare"
        private const val SIGNALING_URL = "wss://peachscreenshare.misoft.ca/ws"
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "screen_share_channel"
    }

    // ── WebSocket ──────────────────────────────────────────
    private val httpClient = OkHttpClient()
    private var ws: WebSocket? = null           // FIX 1: nullable — was lateinit, crashed if WS never opened
    @Volatile private var wsConnected = false

    // ── WebRTC ─────────────────────────────────────────────
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private val peerConnections = mutableMapOf<String, PeerConnection>()
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var eglBase: EglBase? = null        // FIX 2: store EglBase so it can be released on destroy

    // ── Projection ─────────────────────────────────────────
    private var projectionIntent: Intent? = null

    // ── Power ──────────────────────────────────────────────
    private var wakeLock: PowerManager.WakeLock? = null

    // ── State ──────────────────────────────────────────────
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private lateinit var deviceId: String
    @Volatile private var captureStarted = false
    @Volatile private var stopping = false

    // =====================================================
    // BIND
    // =====================================================

    override fun onBind(intent: Intent?): IBinder? = null

    // =====================================================
    // LIFECYCLE
    // =====================================================

    override fun onCreate() {
        super.onCreate()
        // FIX 3: @RequiresApi(S) removed — resolveDeviceId() handles API checks internally
        deviceId = resolveDeviceId()
        createNotificationChannel()
        Log.d(TAG, "Service created: deviceId=$deviceId")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        acquireWakeLock()

        if (captureStarted || stopping) {
            Log.w(TAG, "Already running, ignoring start")
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
        // FIX 4: use modern getParcelableExtra with type param (API 33+) with fallback
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("data")
        }

        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.e(TAG, "Invalid projection permission")
            stopSelf()
            return START_NOT_STICKY
        }

        projectionIntent = data

        initPeerConnectionFactory()
        initWebSocket()
        startCaptureOnce()

        return START_NOT_STICKY
    }

    // =====================================================
    // DEVICE ID  (mirrors MainActivity fallback chain)
    // =====================================================

    @SuppressLint("MissingPermission", "HardwareIds")
    private fun resolveDeviceId(): String {
        // 1 — IMEI (Device Owner / privileged only on API 29+)
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val imei = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                tm.getImei(0) ?: tm.getImei(1)
            } else {
                @Suppress("DEPRECATION") tm.deviceId
            }
            if (!imei.isNullOrBlank()) return imei
        } catch (e: Exception) {
            Log.w(TAG, "IMEI unavailable: ${e.message}")
        }

        // 2 — Android ID (stable fallback, always available)
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        if (!androidId.isNullOrBlank()) return androidId

        // 3 — Last resort: manufacturer + model + boot time
        return "${Build.MANUFACTURER}-${Build.MODEL}-${SystemClock.elapsedRealtime()}"
    }

    // =====================================================
    // WAKE LOCK
    // =====================================================

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScreenShare::WakeLock")
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire(10 * 60 * 1000L) // FIX 5: timeout 10 min — no-timeout acquire() causes lint warning
            Log.d(TAG, "WakeLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock failed", e)
        }
    }

    // =====================================================
    // FOREGROUND NOTIFICATION
    // =====================================================

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        // Tapping the notification opens MainActivity
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen sharing active")
            .setContentText("MDM Admin is sharing your screen")
            .setSmallIcon(R.drawable.ic_app_logo)   // FIX 6: use your own icon, not android.R
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Share",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while screen is being shared"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    // =====================================================
    // WEBRTC SETUP
    // =====================================================

    private fun initPeerConnectionFactory() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions()
        )
        eglBase = EglBase.create()  // FIX 2: store reference
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase!!.eglBaseContext))
            .createPeerConnectionFactory()
    }

    // =====================================================
    // SCREEN CAPTURE
    // =====================================================

    private fun startCaptureOnce() {
        synchronized(this) {
            if (captureStarted) return
            captureStarted = true
        }

        executor.execute {
            try {
                val intent = projectionIntent
                    ?: throw IllegalStateException("Projection intent is null")

                val helper = SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext)

                screenCapturer = ScreenCapturerAndroid(
                    intent,
                    object : MediaProjection.Callback() {
                        override fun onStop() {
                            Log.w(TAG, "MediaProjection stopped by system")
                            stopScreenShare()
                        }
                    }
                )

                videoSource = peerConnectionFactory.createVideoSource(true)
                videoSource!!.adaptOutputFormat(720, 1280, 15)

                screenCapturer!!.initialize(helper, this, videoSource!!.capturerObserver)
                screenCapturer!!.startCapture(720, 1280, 15)

                videoTrack = peerConnectionFactory.createVideoTrack("screen", videoSource!!)
                videoTrack!!.setEnabled(true)

                Log.d(TAG, "Screen capture started (multi-peer ready)")

            } catch (e: Exception) {
                Log.e(TAG, "Capture start failed", e)
                stopScreenShare()
            }
        }
    }

    // =====================================================
    // SIGNALING (WebSocket)
    // =====================================================

    private fun initWebSocket() {
        val req = Request.Builder().url(SIGNALING_URL).build()
        ws = httpClient.newWebSocket(req, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                wsConnected = true
                webSocket.send("""{"role":"device","deviceId":"$deviceId"}""")
                Log.d(TAG, "WebSocket connected, registered as $deviceId")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // FIX 7: log WS failures — was silent before
                wsConnected = false
                Log.e(TAG, "WebSocket failure: ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                wsConnected = false
                Log.d(TAG, "WebSocket closed: $code $reason")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    handleSignalingMessage(JSONObject(text))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse signaling message: $text", e)
                }
            }
        })
    }

    private fun handleSignalingMessage(json: JSONObject) {
        when (json.optString("type")) {
            "requestShare" -> {
                val dashboardId = json.getString("dashboardId")
                val pc = createPeerConnection(dashboardId)
                peerConnections[dashboardId] = pc

                pc.createOffer(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription) {
                        pc.setLocalDescription(this, sdp)
                        sendSignal(mapOf(
                            "type"        to "offer",
                            "deviceId"    to deviceId,
                            "dashboardId" to dashboardId,
                            "sdp"         to mapOf("type" to "offer", "sdp" to sdp.description)
                        ))
                    }
                    override fun onSetSuccess() {}
                    override fun onSetFailure(p0: String?) { Log.e(TAG, "SetLocal failed: $p0") }
                    override fun onCreateFailure(p0: String?) { Log.e(TAG, "CreateOffer failed: $p0") }
                }, MediaConstraints())
            }

            "answer" -> {
                val dashboardId = json.getString("dashboardId")
                val sdp = json.getJSONObject("sdp").getString("sdp")
                peerConnections[dashboardId]?.setRemoteDescription(
                    object : SdpObserver {
                        override fun onSetSuccess() {}
                        override fun onSetFailure(p0: String?) { Log.e(TAG, "SetRemote failed: $p0") }
                        override fun onCreateFailure(p0: String?) {}
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                    },
                    SessionDescription(SessionDescription.Type.ANSWER, sdp)
                )
            }

            "candidate" -> {
                val dashboardId = json.getString("dashboardId")
                val c = json.getJSONObject("candidate")
                peerConnections[dashboardId]?.addIceCandidate(
                    IceCandidate(c.getString("sdpMid"), c.getInt("sdpMLineIndex"), c.getString("candidate"))
                )
            }

            "stopShare" -> {
                val dashboardId = json.getString("dashboardId")
                Log.d(TAG, "Stopping share for $dashboardId")
                peerConnections[dashboardId]?.close()
                peerConnections.remove(dashboardId)
                if (peerConnections.isEmpty()) {
                    Log.d(TAG, "No dashboards left, stopping capture")
                    stopScreenShare()
                }
            }
        }
    }

    private fun sendSignal(map: Map<String, Any?>) {
        // FIX 1: ws is now nullable — safe call
        if (ws == null || !wsConnected) {
            Log.w(TAG, "Cannot send signal — WebSocket not connected")
            return
        }
        ws?.send(JSONObject(map).toString())
    }

    // =====================================================
    // PEER CONNECTION
    // =====================================================

    private fun createPeerConnection(dashboardId: String): PeerConnection {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("turn:18.234.108.84:3478")
                .setUsername("peach")
                .setPassword("PeAmISo@2026")
                .createIceServer()
        )

        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val pc = peerConnectionFactory.createPeerConnection(config, object : PeerConnection.Observer {

            override fun onIceCandidate(c: IceCandidate) {
                sendSignal(mapOf(
                    "type"        to "candidate",
                    "deviceId"    to deviceId,
                    "dashboardId" to dashboardId,
                    "candidate"   to mapOf(
                        "candidate"     to c.sdp,
                        "sdpMid"        to c.sdpMid,
                        "sdpMLineIndex" to c.sdpMLineIndex
                    )
                ))
            }

            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                Log.d(TAG, "[$dashboardId] PC state=$state")
                if (state == PeerConnection.PeerConnectionState.FAILED ||
                    state == PeerConnection.PeerConnectionState.CLOSED) {
                    peerConnections.remove(dashboardId)
                }
            }

            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRenegotiationNeeded() {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}

        }) ?: throw IllegalStateException("PeerConnection creation failed for $dashboardId")

        videoTrack?.let { pc.addTrack(it, listOf("screen_stream")) }

        return pc
    }

    // =====================================================
    // STOP & CLEANUP
    // =====================================================

    private fun stopScreenShare() {
        if (stopping) return
        stopping = true

        Log.d(TAG, "Stopping screen share")

        try { screenCapturer?.stopCapture() } catch (_: Exception) {}
        screenCapturer = null

        videoTrack?.dispose()
        videoTrack = null
        videoSource?.dispose()
        videoSource = null

        peerConnections.values.forEach { it.close() }
        peerConnections.clear()

        try { ws?.close(1000, "stop") } catch (_: Exception) {}  // FIX 1: safe call
        ws = null
        wsConnected = false

        // FIX 2: release EglBase
        try { eglBase?.release() } catch (_: Exception) {}
        eglBase = null

        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null

        projectionIntent = null

        executor.shutdown()

        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        stopScreenShare()
        super.onDestroy()
    }
}