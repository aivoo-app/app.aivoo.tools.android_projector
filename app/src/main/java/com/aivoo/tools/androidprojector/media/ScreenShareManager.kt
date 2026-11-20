package app.aivoo.tools.androidprojector.media

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.MediaConstraints
import org.webrtc.SdpObserver
import org.webrtc.VideoTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.webrtc.SurfaceViewRenderer

class ScreenShareManager(context: Context) {
    companion object {
        @Volatile private var peerConnectionInitialized = false

        @Synchronized
        private fun ensurePeerConnectionInitialized(context: Context) {
            if (peerConnectionInitialized) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions(),
            )
            peerConnectionInitialized = true
        }
    }

    init {
        ensurePeerConnectionInitialized(context)
    }

    private val eglBase = EglBase.create()
    private val factory = PeerConnectionFactory.builder()
        .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
        .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
        .createPeerConnectionFactory()

    val renderer: SurfaceViewRenderer = SurfaceViewRenderer(context).apply {
        init(eglBase.eglBaseContext, null)
        setMirror(false)
        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
    }

    private val webrtcThread = HandlerThread("ScreenShareWebRTC").apply { start() }
    private val webrtcHandler = Handler(webrtcThread.looper)
    private var peerConnection: PeerConnection? = null
    @Volatile var lastError: String? = null
        private set
    @Volatile var lastStage: String = "idle"
        private set

    fun createAnswer(offerSdp: String): String? {
        if (Looper.myLooper() == webrtcThread.looper) {
            return createAnswerInternal(offerSdp)
        }
        val latch = CountDownLatch(1)
        var result: String? = null
        webrtcHandler.postAtFrontOfQueue {
            result = try {
                createAnswerInternal(offerSdp)
            } catch (e: Exception) {
                lastError = "${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
                null
            }
            latch.countDown()
        }
        if (!latch.await(15, TimeUnit.SECONDS)) {
            lastError = "Timed out waiting for WebRTC answer thread"
            return null
        }
        return result
    }

    private fun createAnswerInternal(offerSdp: String): String? {
        stopInternal()
        lastError = null
        lastStage = "configure"
        try {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        )
        val configuration = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }

        lastStage = "create_peer_connection"
        val pc = factory.createPeerConnection(configuration, object : PeerConnection.Observer {
            override fun onSignalingChange(signalingDescription: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(iceGatheringState: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(iceCandidate: IceCandidate?) {}
            override fun onIceCandidatesRemoved(iceCandidates: Array<out IceCandidate>?) {}
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                if (newState == PeerConnection.PeerConnectionState.FAILED) {
                    lastError = "Connection failed"
                }
            }
            override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent?) {}
            override fun onTrack(transceiver: RtpTransceiver?) {
                val video = transceiver?.receiver?.track() as? VideoTrack
                if (video != null) {
                    video.addSink(renderer)
                }
            }
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                val video = receiver?.track() as? VideoTrack
                if (video != null) {
                    video.addSink(renderer)
                }
            }
            override fun onRemoveTrack(receiver: RtpReceiver?) {}
            override fun onDataChannel(dataChannel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddStream(mediaStream: MediaStream?) {}
            override fun onRemoveStream(mediaStream: MediaStream?) {}
        }) ?: run {
            lastError = "Unable to create peer connection"
            return null
        }
        peerConnection = pc
        lastStage = "add_transceivers"

        // Receive-only transceivers matching the browser's video/audio m-lines.
        pc.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
        )
        pc.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
        )

        if (offerSdp.length < 100 || !offerSdp.startsWith("v=0")) {
            lastError = "Offer SDP is invalid or truncated (${offerSdp.length} chars)"
            lastStage = "failed"
            return null
        }

        lastStage = "set_remote_description"
        val remoteLatch = CountDownLatch(1)
        var remoteSetSuccess = false
        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {}
            override fun onSetSuccess() {
                remoteSetSuccess = true
                remoteLatch.countDown()
            }
            override fun onCreateFailure(message: String?) {
                lastError = "Remote description failed: ${message ?: "unknown"}"
                remoteLatch.countDown()
            }
            override fun onSetFailure(message: String?) {
                lastError = "Remote description failed: ${message ?: "unknown"}"
                remoteLatch.countDown()
            }
        }, SessionDescription(SessionDescription.Type.OFFER, offerSdp))
        if (!remoteLatch.await(5, TimeUnit.SECONDS)) {
            lastError = "Timed out setting remote description"
            lastStage = "failed"
            return null
        }
        if (!remoteSetSuccess) {
            lastError = lastError ?: "Remote description was not set"
            lastStage = "failed"
            return null
        }

        lastStage = "create_answer"
        val answerLatch = CountDownLatch(1)
        var answer: SessionDescription? = null
        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                answer = desc
                answerLatch.countDown()
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(message: String?) {
                lastError = "Create answer failed: ${message ?: "unknown"}"
                answerLatch.countDown()
            }
            override fun onSetFailure(message: String?) {
                lastError = "Answer description failed: ${message ?: "unknown"}"
            }
        }, MediaConstraints())
        if (!answerLatch.await(5, TimeUnit.SECONDS)) {
            lastError = "Timed out creating answer SDP"
            lastStage = "failed"
            return null
        }
        val localAnswer = answer ?: run {
            lastError = lastError ?: "No answer SDP produced"
            return null
        }

        lastStage = "set_local_description"
        val localLatch = CountDownLatch(1)
        pc.setLocalDescription(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {}
            override fun onSetSuccess() { localLatch.countDown() }
            override fun onCreateFailure(message: String?) {
                lastError = "Local description failed: ${message ?: "unknown"}"
                localLatch.countDown()
            }
            override fun onSetFailure(message: String?) {
                lastError = "Local description failed: ${message ?: "unknown"}"
                localLatch.countDown()
            }
        }, localAnswer)
        if (!localLatch.await(5, TimeUnit.SECONDS)) {
            lastError = "Timed out setting local description"
            lastStage = "failed"
            return null
        }

        lastStage = "wait_ice"
        waitForIce(pc)
        lastStage = "done"
        return pc.localDescription?.description
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            lastStage = "failed"
            stopInternal()
            return null
        }
    }

    private fun waitForIce(pc: PeerConnection) {
        var waited = 0L
        while (pc.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE && waited < 5_000L) {
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                break
            }
            waited += 50
        }
    }

    fun stop() {
        if (Looper.myLooper() == webrtcThread.looper) {
            stopInternal()
        } else {
            webrtcHandler.post { stopInternal() }
        }
    }

    private fun stopInternal() {
        peerConnection?.close()
        peerConnection = null
        renderer.clearImage()
    }

    fun release() {
        val latch = CountDownLatch(1)
        webrtcHandler.post {
            stopInternal()
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        webrtcThread.quitSafely()
        renderer.release()
        eglBase.release()
        factory.dispose()
    }
}