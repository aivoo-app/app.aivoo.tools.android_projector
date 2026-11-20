package app.aivoo.tools.androidprojector.media

import android.content.Context
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

    private var peerConnection: PeerConnection? = null
    @Volatile var lastError: String? = null
        private set

    @Synchronized
    fun createAnswer(offerSdp: String): String? {
        stop()
        lastError = null
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        )
        val configuration = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }

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

        // Receive-only transceivers matching the browser's video/audio m-lines.
        pc.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
        )
        pc.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
        )

        val remoteLatch = CountDownLatch(1)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {}
            override fun onSetSuccess() { remoteLatch.countDown() }
            override fun onCreateFailure(message: String?) {
                lastError = "Remote description failed: ${message ?: "unknown"}"
                remoteLatch.countDown()
            }
            override fun onSetFailure(message: String?) {
                lastError = "Remote description failed: ${message ?: "unknown"}"
                remoteLatch.countDown()
            }
        }, SessionDescription(SessionDescription.Type.OFFER, offerSdp))
        remoteLatch.await(5, TimeUnit.SECONDS)

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
        answerLatch.await(5, TimeUnit.SECONDS)
        val localAnswer = answer ?: run {
            lastError = lastError ?: "No answer SDP produced"
            return null
        }

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
        localLatch.await(5, TimeUnit.SECONDS)
        waitForIce(pc)
        return pc.localDescription?.description
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

    @Synchronized
    fun stop() {
        peerConnection?.close()
        peerConnection = null
        renderer.clearImage()
    }

    fun release() {
        stop()
        renderer.release()
        eglBase.release()
        factory.dispose()
    }
}