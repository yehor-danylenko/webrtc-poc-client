package me.amryousef.webrtc_demo

import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

interface SignallingClientListener {
    fun onConnectionEstablished()
    fun onOfferReceived(description: SessionDescription)
    fun onAnswerReceived(description: SessionDescription)
    fun onIceCandidateReceived(iceCandidate: IceCandidate)
    fun onVideoInfoReceived(videoInfo: SignallingClient.VideoInfo)
    fun onPositionReceived(position: SignallingClient.Position)
    fun onPlayEnd()
}