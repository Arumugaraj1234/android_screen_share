package com.peach.android_screen_share

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.*
import org.json.JSONObject
import org.webrtc.*
import java.util.concurrent.Executors

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenShare"
        private const val SIGNALING_URL = "wss://peachscreenshare.misoft.ca/ws"
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "screen_share_channel"
    }

    // ===== WebSocket =====
    private val httpClient = OkHttpClient()
    private lateinit var ws: WebSocket
    @Volatile private var wsConnected = false

    // ===== WebRTC =====
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null

    // ===== Projection =====
    private var projectionIntent: Intent? = null

    // ===== State =====
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var deviceId: String
    @Volatile private var captureStarted = false
    @Volatile private var shareRequested = false
    @Volatile private var offerSent = false
    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    // =====================================================
    // SERVICE LIFECYCLE
    // =====================================================

    override fun onCreate() {
        super.onCreate()

        deviceId = "${Build.MANUFACTURER}-${Build.MODEL}-${System.currentTimeMillis()}"
        createNotificationChannel()

        Log.d(TAG, "Service created: $deviceId")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        startAsForeground()

        if (captureStarted || stopping) {
            Log.w(TAG, "Already running, ignoring start")
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
        val data = intent?.getParcelableExtra<Intent>("data")

        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.e(TAG, "Invalid projection permission")
            stopSelf()
            return START_NOT_STICKY
        }

        // 🔒 Store permission intent ONCE
        projectionIntent = data

        initPeerConnectionFactory()
        initPeerConnection()
        initWebSocket()
        startCaptureOnce()

        return START_NOT_STICKY
    }

    // =====================================================
    // FOREGROUND SERVICE
    // =====================================================

    private fun startAsForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen sharing active")
            .setContentText("Your screen is being shared")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Share",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    // =====================================================
    // WEBRTC SETUP
    // =====================================================

    private fun initPeerConnectionFactory() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions()
        )

        val egl = EglBase.create()

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private fun initPeerConnection() {

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),

            // TURN (important for mobile data)
            PeerConnection.IceServer.builder("turn:18.234.108.84:3478?transport=udp")
                .setUsername("peach")
                .setPassword("PeAmISo@2026")
                .createIceServer(),

            PeerConnection.IceServer.builder("turn:18.234.108.84:3478?transport=tcp")
                .setUsername("peach")
                .setPassword("PeAmISo@2026")
                .createIceServer(),

            PeerConnection.IceServer.builder("turns:18.234.108.84:5349")
                .setUsername("peach")
                .setPassword("PeAmISo@2026")
                .createIceServer()
        )

        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType = PeerConnection.IceTransportsType.ALL
        }

        peerConnection = peerConnectionFactory.createPeerConnection(
            config,
            object : PeerConnection.Observer {

                override fun onIceCandidate(c: IceCandidate) {
                    sendSignal(
                        mapOf(
                            "type" to "candidate",
                            "deviceId" to deviceId,
                            "candidate" to mapOf(
                                "candidate" to c.sdp,
                                "sdpMid" to c.sdpMid,
                                "sdpMLineIndex" to c.sdpMLineIndex
                            )
                        )
                    )
                }

                override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                    Log.d(TAG, "PC state=$state")
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    Log.d(TAG, "ICE state=$state")
                }

                override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
                override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
                override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
                override fun onRemoveStream(p0: MediaStream?) {}
                override fun onDataChannel(p0: DataChannel?) {}
                override fun onAddStream(p0: MediaStream?) {}
                override fun onRenegotiationNeeded() {}
                override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                override fun onIceConnectionReceivingChange(p0: Boolean) {}
            }
        )
    }

    // =====================================================
    // SCREEN CAPTURE (OEM SAFE)
    // =====================================================

    private fun startCaptureOnce() {
        synchronized(this) {
            if (captureStarted) return
            captureStarted = true
        }

        executor.execute {
            try {
                val intent = projectionIntent ?: throw IllegalStateException("Projection intent null")

                val egl = EglBase.create()
                val helper = SurfaceTextureHelper.create("CaptureThread", egl.eglBaseContext)

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
                videoSource!!.adaptOutputFormat(720, 1280, 30)

                screenCapturer!!.initialize(
                    helper,
                    this,
                    videoSource!!.capturerObserver
                )

                screenCapturer!!.startCapture(720, 1280, 30)

                videoTrack = peerConnectionFactory.createVideoTrack("screen", videoSource!!)
                videoTrack!!.setEnabled(true)

                Handler(mainLooper).post {
                    peerConnection?.addTrack(videoTrack!!, listOf("screen_stream"))
                }

                Log.d(TAG, "Screen capture started")

            } catch (e: Exception) {
                Log.e(TAG, "Capture start failed", e)
                stopScreenShare()
            }
        }
    }

    // =====================================================
    // SIGNALING
    // =====================================================

    private fun initWebSocket() {
        val req = Request.Builder().url(SIGNALING_URL).build()

        ws = httpClient.newWebSocket(req, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                wsConnected = true
                webSocket.send("""{"role":"device","deviceId":"$deviceId"}""")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = JSONObject(text)
                when (json.optString("type")) {
                    "requestShare" -> {
                        shareRequested = true
                        maybeCreateOffer()
                    }
                    "answer" -> handleAnswer(json)
                    "candidate" -> handleCandidate(json)
                    "stopShare" -> stopScreenShare()
                }
            }
        })
    }

    private fun maybeCreateOffer() {
        if (!captureStarted || !wsConnected || !shareRequested || offerSent) return
        offerSent = true

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(this, sdp)
                sendSignal(
                    mapOf(
                        "type" to "offer",
                        "deviceId" to deviceId,
                        "sdp" to mapOf("type" to "offer", "sdp" to sdp.description)
                    )
                )
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
            override fun onCreateFailure(p0: String?) {}
        }, MediaConstraints())
    }

    private fun handleAnswer(msg: JSONObject) {
        val sdp = msg.getJSONObject("sdp").getString("sdp")
        peerConnection?.setRemoteDescription(
            object : SdpObserver {
                override fun onSetSuccess() {}
                override fun onSetFailure(p0: String?) {}
                override fun onCreateFailure(p0: String?) {}
                override fun onCreateSuccess(p0: SessionDescription?) {}
            },
            SessionDescription(SessionDescription.Type.ANSWER, sdp)
        )
    }

    private fun handleCandidate(msg: JSONObject) {
        val c = msg.getJSONObject("candidate")
        peerConnection?.addIceCandidate(
            IceCandidate(
                c.getString("sdpMid"),
                c.getInt("sdpMLineIndex"),
                c.getString("candidate")
            )
        )
    }

    private fun sendSignal(map: Map<String, Any?>) {
        ws.send(JSONObject(map).toString())
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
        videoSource?.dispose()

        peerConnection?.close()
        peerConnection = null

        try { ws.close(1000, "stop") } catch (_: Exception) {}

        projectionIntent = null

        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        stopScreenShare()
        super.onDestroy()
    }
}
