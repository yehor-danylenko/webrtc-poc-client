package me.amryousef.webrtc_demo

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.features.json.GsonSerializer
import io.ktor.client.features.json.JsonFeature
import io.ktor.client.features.websocket.WebSockets
import io.ktor.client.features.websocket.wss
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.readText
import io.ktor.util.KtorExperimentalAPI
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

@ExperimentalCoroutinesApi
@KtorExperimentalAPI
class SignallingClient(
    private val listener: SignallingClientListener
) : CoroutineScope {

    companion object {
        private const val HOST_ADDRESS = "3.234.207.34"
    }

    private val job = Job()

    private val gson = Gson()

    override val coroutineContext = Dispatchers.IO + job

    private val trustAllCerts = arrayOf(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {

        }

        @Throws(CertificateException::class)
        override fun checkServerTrusted(chain: Array<X509Certificate?>?,
                                        authType: String?) {
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    private val client = HttpClient(CIO) {
        engine {
            this.https.trustManager = trustAllCerts.first()
        }

        install(WebSockets)
        install(JsonFeature) {
            serializer = GsonSerializer()
        }
    }

    private val sendChannel = ConflatedBroadcastChannel<String>()

    init {
        connect()
    }

    fun connect() = launch {
        client.wss(host = HOST_ADDRESS, port = 8889, path = "/player") {
            listener.onConnectionEstablished()
            val sendData = sendChannel.openSubscription()
            try {
                while (true) {

                    sendData.poll()?.let {
                        Log.v(this@SignallingClient.javaClass.simpleName, "Sending: $it")
                        outgoing.send(Frame.Text(it))
                    }
                    incoming.poll()?.let { frame ->
                        if (frame is Frame.Text) {
                            val data = frame.readText()
                            Log.v(this@SignallingClient.javaClass.simpleName, "Received: $data")
                            val jsonObject = gson.fromJson(data, JsonObject::class.java)
                            withContext(Dispatchers.Main) {

                                val id = jsonObject.get("id").asString

                                if (id == "videoInfo") {
                                    val videoInfo = gson.fromJson(jsonObject, VideoInfo::class.java)
                                    listener.onVideoInfoReceived(videoInfo)
                                    return@withContext
                                }

                                if (id == "position") {
                                    val position = gson.fromJson(jsonObject, Position::class.java)
                                    listener.onPositionReceived(position)
                                    return@withContext
                                }

                                if (id == "playEnd") {
                                    listener.onPlayEnd()
                                    return@withContext
                                }

                                val startResponse = try {
                                    gson.fromJson(jsonObject, StartResponse::class.java)
                                } catch(e: Exception) {
                                    Log.e("Overlay", "failed to parse", e)
                                    null
                                }

                                if (startResponse != null) {
                                    if (startResponse.sdpAnswer?.isNotEmpty() == true) {
                                        listener.onAnswerReceived(SessionDescription(SessionDescription.Type.ANSWER, startResponse.sdpAnswer))
                                    } else {
                                        val bIceCandidate = try {
                                            gson.fromJson(jsonObject, BIceCandidate::class.java)
                                        } catch(e: Exception) {
                                            Log.e("Overlay", "failed to parse", e)
                                            null
                                        }
                                        if (bIceCandidate?.candidate != null) {
                                            listener.onIceCandidateReceived(IceCandidate(bIceCandidate.candidate.sdpMid, bIceCandidate.candidate.sdpMLineIndex, bIceCandidate.candidate.candidate))
                                        }
                                    }
                                }

                                if (jsonObject.has("serverUrl")) {
                                    //listener.onIceCandidateReceived(gson.fromJson(jsonObject, IceCandidate::class.java))
                                } else if (jsonObject.has("type") && jsonObject.get("type").asString == "OFFER") {
                                    listener.onOfferReceived(gson.fromJson(jsonObject, SessionDescription::class.java))
                                }
//                                else if (jsonObject.has("type") && jsonObject.get("type").asString == "ANSWER") {
//                                    listener.onAnswerReceived(gson.fromJson(jsonObject, SessionDescription::class.java))
//                                }
                            }
                        }
                    }
                }
            } catch (exception: Throwable) {
                Log.e("asd","asd",exception)
            }
        }
        Log.e("Overlay", "trying to connect::::|no fail")
    }

    data class BIceCandidate(val id: String, val candidate: JCandidate?)
    data class JCandidate(val candidate: String, val sdpMid: String, val sdpMLineIndex: Int)

    data class StartResponse(val id: String, val sdpAnswer: String? = "")
    data class VideoInfo(val id: String, val isSeekable: Boolean, val initSeekable: Int, val endSeekable: Int, val videoDuration: Int)
    data class Position(val position: Int)
    data class PlayEnd(val id: String)

    fun send(dataObject: Any?) = runBlocking {
        sendChannel.send(gson.toJson(dataObject))
    }

    fun disconnect() {
        client.close()
    }

    fun destroy() {
        disconnect()
        job.complete()
    }
}