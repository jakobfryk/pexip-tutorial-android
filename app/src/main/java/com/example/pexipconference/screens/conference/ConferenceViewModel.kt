package com.example.pexipconference.screens.conference

import android.app.Application
import android.util.Log

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.pexip.sdk.api.coroutines.await
import com.pexip.sdk.api.infinity.InfinityService
import com.pexip.sdk.api.infinity.RequestTokenRequest
import com.pexip.sdk.conference.ConferenceEventListener
import com.pexip.sdk.conference.DisconnectConferenceEvent
import com.pexip.sdk.conference.infinity.InfinityConference
import com.pexip.sdk.media.*
import com.pexip.sdk.media.webrtc.WebRtcMediaConnectionFactory
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.webrtc.EglBase
import java.net.URL

class ConferenceViewModel(application: Application) : AndroidViewModel(application) {

    // Initialize EGL
    val eglBase: EglBase = EglBase.create()

    // AudioTrack from the local microphone
    private lateinit var localAudioTrack: LocalAudioTrack

    // Local VideoTrack
    private val _localVideoTrack = MutableLiveData<CameraVideoTrack>()
    val localVideoTrack: LiveData<CameraVideoTrack>
        get() = _localVideoTrack

    // Remote VideoTrack
    private val _remoteVideoTrack = MutableLiveData<VideoTrack>()
    val remoteVideoTrack: LiveData<VideoTrack>
        get() = _remoteVideoTrack

    // Notify if the user is connected to the conference or not
    private val _isConnected = MutableLiveData<Boolean>()
    val isConnected: LiveData<Boolean>
        get() = _isConnected

    // Used to inform of an error to the fragment
    private val _onError = MutableLiveData<Throwable>()
    val onError: LiveData<Throwable>
        get() = _onError

    // Objects needed to initialize the conference
    private val webRtcMediaConnectionFactory: WebRtcMediaConnectionFactory

    // Objects that save the conference state
    private lateinit var conference: InfinityConference
    private lateinit var mediaConnection: MediaConnection

    init {
        // Create the webRtcMediaConnectionFactory
        WebRtcMediaConnectionFactory.initialize(application)
        webRtcMediaConnectionFactory = WebRtcMediaConnectionFactory(
            context = application,
            eglBase = eglBase
        )
    }

    override fun onCleared() {
        super.onCleared()
        if (this::conference.isInitialized) {
            conference.leave()
        }
        if (this::mediaConnection.isInitialized) {
            mediaConnection.dispose()
        }
        if (this::localAudioTrack.isInitialized) {
            localAudioTrack.dispose()
        }
        localVideoTrack.value?.dispose()
    }

    // TODO (11) Add the PIN to the startConference() parameters
    fun startConference(node: String, vmr: String, displayName: String) {
        val exceptionHandler = CoroutineExceptionHandler { _, exception ->
            // Convert the error into a more descriptive message
            _onError.postValue(exception)
        }
        viewModelScope.launch(exceptionHandler) {
            // Authenticate to the conference
            // TODO (12) Add the PIN to the createConference() call
            conference = createConference(node, vmr, displayName)

            // Get access to the local microphone and camera
            val (audioTrack, videoTrack) = getLocalMedia()
            localAudioTrack = audioTrack
            _localVideoTrack.value = videoTrack

            // Initialize the WebRTC media connection. We will sending and receiving media.
            startWebRTCConnection(conference, audioTrack, videoTrack)
        }
    }

    fun onDisconnect() {
        _isConnected.value = false
    }

    // TODO (13) Add the PIN to the method input parameters
    private suspend fun createConference(
        node: String,
        vmr: String,
        displayName: String
    ): InfinityConference {

        val okHttpClient = OkHttpClient()
        val request = RequestTokenRequest(displayName = displayName)
        val infinityService = InfinityService.create(okHttpClient)
        lateinit var conference: InfinityConference
        val nodeUrl = URL("https://${node}")

        return withContext(Dispatchers.IO) {
            // TODO (14) Set different requests depending on if we are using PIN or not
            val response = infinityService.newRequest(nodeUrl)
                .conference(vmr)
                .requestToken(request)
                .await()
            conference = InfinityConference.create(
                service = infinityService,
                node = nodeUrl,
                conferenceAlias = displayName,
                response = response
            )
            configureConferenceListeners(conference)
            return@withContext conference
        }
    }

    private fun configureConferenceListeners(conference: InfinityConference) {
        conference.registerConferenceEventListener(ConferenceEventListener { event ->
            when (event) {
                is DisconnectConferenceEvent -> {
                    _isConnected.postValue(false)
                }
                else -> {
                    Log.d("ConferenceViewModel", event.toString())
                }
            }
        })
    }

    private fun getLocalMedia(): Pair<LocalAudioTrack, CameraVideoTrack> {
        val audioTrack: LocalAudioTrack = webRtcMediaConnectionFactory.createLocalAudioTrack()
        val videoTrack: CameraVideoTrack = webRtcMediaConnectionFactory.createCameraVideoTrack()
        audioTrack.startCapture()
        videoTrack.startCapture(QualityProfile.High)
        return audioTrack to videoTrack
    }

    private fun startWebRTCConnection(
        conference: InfinityConference,
        localAudioTrack: LocalAudioTrack,
        localVideoTrack: CameraVideoTrack
    ) {
        // Define the STUN server. This is used for obtain the public IP of the participants
        // and this way be able to establish the media connection.
        val iceServer = IceServer.Builder("stun:stun.l.google.com:19302").build()
        val config = MediaConnectionConfig.Builder(conference)
            .addIceServer(iceServer)
            .presentationInMain(false)
            .build()

        // Save the media connection in a class private variable. We need it later
        // for disposing the media connection.
        mediaConnection = webRtcMediaConnectionFactory.createMediaConnection(config)

        // Attach the local media streams to the media connection.
        mediaConnection.setMainAudioTrack(localAudioTrack)
        mediaConnection.setMainVideoTrack(localVideoTrack)

        // Define a callback method for when the remote video is received.
        val mainRemoveVideTrackListener = MediaConnection.RemoteVideoTrackListener { videoTrack ->
            // We have to use postValue instead of value, because we are running this in another thread.
            _remoteVideoTrack.postValue(videoTrack)
            _isConnected.postValue(true)
        }

        // Attach the callback to the media connection.
        mediaConnection.registerMainRemoteVideoTrackListener(mainRemoveVideTrackListener)

        // Start the media connection.
        mediaConnection.start()
    }

}