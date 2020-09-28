package me.amryousef.webrtc_demo

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isGone
import com.jakewharton.rxbinding3.widget.textChanges
import com.jakewharton.rxbinding3.widget.userChanges
import io.ktor.util.KtorExperimentalAPI
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import me.amryousef.webrtc_demo.MainActivity.SessionState.*
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.SessionDescription
import java.lang.Thread.sleep
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

@ExperimentalCoroutinesApi
@KtorExperimentalAPI
class MainActivity : AppCompatActivity() {

    enum class SessionState { ORIGINAL, IN_SESSION, NOT_IN_SESSION }

    private lateinit var rtcClient: RTCClient
    private lateinit var signallingClient: SignallingClient

    private var sdpObserver: AppSdpObserver? = null

    private var videoCounter = 0

    private val videos = listOf(
        "http://kurento-media-server-poc-demo.s3-website-us-east-1.amazonaws.com/6871279892111720939.mp4",
        "http://kurento-media-server-poc-demo.s3-website-us-east-1.amazonaws.com/6871279892111720939_lq.mp4",
        "http://kurento-media-server-poc-demo.s3-website-us-east-1.amazonaws.com/TeslaCam/2020-06-14_13-32-23-back.mp4",
        "http://kurento-media-server-poc-demo.s3-website-us-east-1.amazonaws.com/TeslaCam/2020-06-14_13-32-23-front.mp4",
        "http://kurento-media-server-poc-demo.s3-website-us-east-1.amazonaws.com/TeslaCam/2020-06-14_13-32-23-left_repeater.mp4",
        "http://kurento-media-server-poc-demo.s3-website-us-east-1.amazonaws.com/TeslaCam/2020-06-14_13-32-23-right_repeater.mp4",
        "http://kurento-media-server-poc-demo.s3-website-us-east-1.amazonaws.com/dings_qa_env_portal/6871209325775687372.mp4"
        )

    private val threadPoolExecutor = Executors.newSingleThreadExecutor()
    private var positionPollingFuture: Future<*>? = null

    private var sessionState = ORIGINAL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        init()

    }

    private fun init() {
        initRTCClient()
        rtcClient.initSurfaceView(remote_view)
        initSignallingClient()
        initSDPObserver()
        setupUIListeners()
    }

    private fun initRTCClient() {
        rtcClient = RTCClient(
                application,
                object : PeerConnectionObserver() {
                    override fun onIceCandidate(p0: IceCandidate?) {
                        super.onIceCandidate(p0)
                        signallingClient.send(NIceCandidate(candidate = Candidate(p0?.sdp!!, p0.sdpMid, p0.sdpMLineIndex)))
                        rtcClient.addIceCandidate(p0)
                    }

                    override fun onAddStream(p0: MediaStream?) {
                        super.onAddStream(p0)
                        p0?.videoTracks?.get(0)?.addSink(remote_view)
                    }
                }
        )
    }

    private fun initSignallingClient() {
        signallingClient = SignallingClient(createSignallingClientListener())
    }

    private fun initSDPObserver(incrementVideo: Boolean = false) {
        sdpObserver = object : AppSdpObserver() {
            override fun onCreateSuccess(p0: SessionDescription?) {
                super.onCreateSuccess(p0)
                signallingClient.send(StartSession(sdpOffer =
                p0?.description!!
                        ,
                        videourl = videos[
                                (videoCounter + if (incrementVideo) 1 else 0).rem(videos.size)
                        ])).also {
                    if (incrementVideo) {
                        videoCounter++
                    }
                }
            }
        }
    }

    private fun setupUIListeners() {
        play_button.setOnClickListener {
            when (sessionState) {
                ORIGINAL -> {
                    Log.e("Overlay", "fps started playing")
                    rtcClient.call(sdpObserver!!)
                    sessionState = IN_SESSION
                    startPollingPosition()
                }
                IN_SESSION -> {
                    signallingClient.send(Resume())
                }
                else -> {
                    Log.e("Overlay", "fps started playing")
                    reInit()
                }
            }

        }

        pause_button.setOnClickListener {
            signallingClient.send(Pause())
        }

        switch_button.setOnClickListener {
            reInit(incrementVideo = true)
        }

        val d = throttle_time_ms.textChanges()
            .switchMap {
                val throttleMs = throttle_time_ms.text?.toString()?.toLongOrNull() ?: 0L.also {
                    Toast.makeText(this, "Wrong throttle ms, default to 0", Toast.LENGTH_LONG).show()
                }
                seekBar.userChanges().throttleFirst(throttleMs, TimeUnit.MILLISECONDS)
            }
            .subscribe {
                doSeek(it)
            }

    }

    private var onConnectedAction: ((Unit) -> Unit)? = null

    private fun reInit(incrementVideo: Boolean = false) {
        if (sessionState == IN_SESSION || sessionState == NOT_IN_SESSION) {
            stopPolling()
            signallingClient.send(Stop())
            signallingClient.destroy()
            sessionState = NOT_IN_SESSION

            remote_view.release()
            initRTCClient()
            rtcClient.initSurfaceView(remote_view)
            initSignallingClient()
            initSDPObserver(incrementVideo)

            onConnectedAction = {
                rtcClient.call(sdpObserver!!)
                sessionState = IN_SESSION
                startPollingPosition()
            }

        }
    }

    private fun doSeek(progress: Int) {
        signallingClient.send(DoSeek(position = progress * 1000))
    }

    private fun startPollingPosition() {
        positionPollingFuture?.cancel(true)

        positionPollingFuture = threadPoolExecutor.submit {
            while (true) {
                try {
                    signallingClient.send(GetPosition())
                    sleep(150)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun stopPolling() {
        positionPollingFuture?.cancel(true)
    }

    private fun createSignallingClientListener() = object : SignallingClientListener {

        private var videoInfo: SignallingClient.VideoInfo? = null

        override fun onConnectionEstablished() {
            play_button.isClickable = true

            onConnectedAction?.invoke(Unit)
            onConnectedAction = null
        }

        override fun onOfferReceived(description: SessionDescription) {
            rtcClient.onRemoteSessionReceived(description)
            remote_view_loading.isGone = true
        }

        override fun onAnswerReceived(description: SessionDescription) {
            rtcClient.onRemoteSessionReceived(description)
            remote_view_loading.isGone = true
        }

        override fun onIceCandidateReceived(iceCandidate: IceCandidate) {
            rtcClient.addIceCandidate(iceCandidate)
        }

        override fun onVideoInfoReceived(videoInfo: SignallingClient.VideoInfo) {
            this.videoInfo = videoInfo
            runOnUiThread {
                videoDuration.text = SimpleDateFormat("mm:ss", Locale.getDefault()).also {
                    it.timeZone = TimeZone.getTimeZone("UTC")
                }.format(videoInfo.videoDuration)

                seekBar.max = videoInfo.videoDuration / 1000
            }
        }

        override fun onPositionReceived(position: SignallingClient.Position) {
            videoInfo?.let {
                runOnUiThread {
                    val sdf = SimpleDateFormat("mm:ss", Locale.getDefault()).also {
                        it.timeZone = TimeZone.getTimeZone("UTC")
                    }
                    videoDuration.text = sdf.format(position.position) + "/" + sdf.format(it.videoDuration)
                    seekBar.progress = position.position / 1000
                }
            }
        }

        override fun onPlayEnd() {
            Log.e("Overlay", "fps stopped playing")
            sessionState = NOT_IN_SESSION
            stopPolling()
        }
    }

    override fun onDestroy() {
        signallingClient.send(Stop())
        signallingClient.destroy()
        super.onDestroy()
    }
}

data class StartSession(val id: String = "start", val sdpOffer: String, val videourl: String = "http://files.openvidu.io/video/format/sintel.webm")
data class Pause(val id: String = "pause")
data class Resume(val id: String = "resume")
data class GetPosition(val id: String = "getPosition")
data class DoSeek(val id: String = "doSeek", val position: Int)
data class Stop(val id: String = "stop")
data class NIceCandidate(val id: String = "onIceCandidate", val candidate: Candidate)
data class Candidate(val candidate: String, val sdpMid: String, val sdpMLineIndex: Int)