package com.peach.android_screen_share

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.*
import org.json.JSONObject
import org.webrtc.*
import java.util.concurrent.Executors

class ScreenCaptureService : Service() {

    private val SIGNALING_URL = "wss://peachscreenshare.misoft.ca/ws"

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null

    private val httpClient = OkHttpClient()
    private lateinit var ws: WebSocket

    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var screenCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null

    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var deviceId: String
    private var wsConnected = false
    private var projectionStarted = false
    private var shareRequested = false
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
//        deviceId = "${Build.MANUFACTURER}-${Build.MODEL}-$androidId"
        deviceId = Build.MANUFACTURER + "-" +
                Build.MODEL + "-" +
                System.currentTimeMillis()

        createNotificationChannel()

        Log.d("ScreenShare", "Service created: $deviceId")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        startAsForeground()

        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data")

        initPeerConnectionFactory()
        initPeerConnection()
        initWebSocket()

        if (resultCode == Activity.RESULT_OK && data != null) {
            startScreenShare(resultCode, data)
        } else {
            Log.e("ScreenShare", "Projection intent missing…")
        }

        return START_NOT_STICKY
    }

    // ---------------- Foreground Service ----------------

    private fun startAsForeground() {
//        val notification = NotificationCompat.Builder(this, "screen_share_channel")
//            .setContentTitle("Screen Sharing Enabled")
//            .setContentText("Waiting for dashboard request…")
//            .setSmallIcon(android.R.drawable.ic_media_play)
//            .setOngoing(true)
//            .build()


//        .setForegroundServiceBehavior(
//            NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
//        )
        val notification = NotificationCompat.Builder(this, "screen_share_channel")
            .setContentTitle("Screen Sharing Active")
            .setContentText("Waiting for dashboard connection…")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+
            startForeground(
                1,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            // Android 7–9
            startForeground(1, notification)
        }


    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "screen_share_channel",
                "Screen Share",
                NotificationManager.IMPORTANCE_HIGH // 🔴 MUST BE LOW
            ).apply {
                description = "Screen sharing service"
//                setSound(null, null)
//                enableVibration(false)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }


//        if (Build.VERSION.SDK_INT >= 26) {
//            val channel = NotificationChannel(
//                "screen_share_channel",
//                "Screen Share",
//                NotificationManager.IMPORTANCE_HIGH
//            )
//            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
//        }
    }

    // ---------------- WebRTC SETUP ----------------

    private fun initPeerConnectionFactory() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions()
        )

        val egl = EglBase.create()

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()

        Log.d("ScreenShare", "PC Factory OK")
    }

    private fun initPeerConnection() {

//        val iceServers = listOf(
//            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
//        )

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                .createIceServer(),

            // TURN UDP
            PeerConnection.IceServer.builder("turn:18.234.108.84:3478?transport=udp")
                .setUsername("peach")
                .setPassword("PeAmISo@2026")
                .createIceServer(),

            // TURN TCP
            PeerConnection.IceServer.builder("turn:18.234.108.84:3478?transport=tcp")
                .setUsername("peach")
                .setPassword("PeAmISo@2026")
                .createIceServer(),

            // TURN TLS (VERY IMPORTANT for mobile networks)
            PeerConnection.IceServer.builder("turns:18.234.108.84:5349")
                .setUsername("peach")
                .setPassword("PeAmISo@2026")
                .createIceServer()
        )

        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            continualGatheringPolicy =
                PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

//        val config = PeerConnection.RTCConfiguration(iceServers)

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
                    Log.d("ScreenShare", "PC state: $state")
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    Log.d("ScreenShare", "ICE state = $state")
                }

                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                    Log.d("ScreenShare", "ICE gathering = $state")
                }

                override fun onIceConnectionReceivingChange(p0: Boolean) {}
//                override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
//                override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
                override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
                override fun onRemoveStream(p0: MediaStream?) {}
                override fun onDataChannel(p0: DataChannel?) {}
                override fun onAddStream(p0: MediaStream?) {}
                override fun onRenegotiationNeeded() {}
                override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            }
        )

        Log.d("ScreenShare", "PC created")
    }



    // ---------------- Start Screen Capture ----------------

    private fun startScreenShare(resultCode: Int, data: Intent) {
        executor.execute {
            try {
                mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
                projectionStarted = true

                val egl = EglBase.create()
                val textureHelper = SurfaceTextureHelper.create("CaptureThread", egl.eglBaseContext)

                screenCapturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopScreenShare()
                    }
                })

                videoSource = peerConnectionFactory.createVideoSource(true)

                screenCapturer!!.initialize(textureHelper, this, videoSource!!.capturerObserver)
                screenCapturer!!.startCapture(480, 960, 15)

                videoTrack = peerConnectionFactory.createVideoTrack("screen", videoSource!!)
                videoTrack!!.setEnabled(true)
                peerConnection!!.addTrack(videoTrack!!, listOf("screen_stream"))

            } catch (e: Exception) {
                Log.e("ScreenShare", "Capture error: ${e.message}")
                stopScreenShare()
            }
        }
    }

    // ---------------- WebSocket ----------------

    private fun initWebSocket() {
        val req = Request.Builder().url(SIGNALING_URL).build()

        ws = httpClient.newWebSocket(req, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                wsConnected = true

                webSocket.send(
                    """{"role":"device","deviceId":"$deviceId"}"""
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = JSONObject(text)
                when (json.optString("type")) {

                    "requestShare" -> {
                        shareRequested = true
                        makeOffer()
                    }

                    "answer" -> handleAnswer(json)

                    "candidate" -> handleCandidate(json)

                    "stopShare" -> stopScreenShare()
                }
            }
        })
    }

    private fun makeOffer() {
        if (!projectionStarted || !wsConnected || !shareRequested) return

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
        val sdpText = msg.getJSONObject("sdp").getString("sdp")
        val answer = SessionDescription(SessionDescription.Type.ANSWER, sdpText)

        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
            override fun onCreateFailure(p0: String?) {}
            override fun onCreateSuccess(p0: SessionDescription?) {}
        }, answer)
    }

    private fun handleCandidate(msg: JSONObject) {
        val c = msg.getJSONObject("candidate")

        val ice = IceCandidate(
            c.getString("sdpMid"),
            c.getInt("sdpMLineIndex"),
            c.getString("candidate")
        )

        peerConnection?.addIceCandidate(ice)
    }

    private fun sendSignal(map: Map<String, Any?>) {
        try {
            ws.send(JSONObject(map).toString())
        } catch (_: Exception) {}
    }

    // ---------------- STOP SCREEN SHARE ----------------

    private fun stopScreenShare() {
        if (stopping) return
        stopping = true

        Log.d("ScreenShare", "STOPPING SCREEN SHARE…")

        try { screenCapturer?.stopCapture() } catch (_: Exception) {}

        videoSource?.dispose()
        videoTrack?.dispose()

        peerConnection?.close()
        peerConnection = null

        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null

        try { ws.close(1000, "stop") } catch (_: Exception) {}

        httpClient.dispatcher.executorService.shutdown()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        Log.d("ScreenShare", "Screen share STOPPED SUCCESSFULLY.")
    }

    override fun onDestroy() {
        stopScreenShare()
        super.onDestroy()
    }
}
