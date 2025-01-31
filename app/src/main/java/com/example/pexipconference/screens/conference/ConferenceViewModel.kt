package com.example.pexipconference.screens.conference

import android.app.Application
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.pexipconference.R
import com.pexip.sdk.api.coroutines.await
import com.pexip.sdk.api.infinity.InfinityService
import com.pexip.sdk.api.infinity.RequestTokenRequest
import com.pexip.sdk.conference.ConferenceEventListener
import com.pexip.sdk.conference.DisconnectConferenceEvent
import com.pexip.sdk.conference.MessageListener
import com.pexip.sdk.conference.PresentationStartConferenceEvent
import com.pexip.sdk.conference.PresentationStopConferenceEvent
import com.pexip.sdk.conference.infinity.InfinityConference
import com.pexip.sdk.media.*
import com.pexip.sdk.media.webrtc.WebRtcMediaConnectionFactory
import com.vidhance.appsdk.VidhanceBuilder
import com.vidhance.appsdk.VidhanceInterface
import com.vidhance.appsdk.VidhanceProcessor
import com.vidhance.appsdk.utils.SensorDataCollector
import com.vidhance.appsdk.utils.getCalibrationHandler
import com.vidhance.appsdk.utils.getLicenseHandler
import com.vidhance.inapp.solutions.vidhance.camera.VidhanceVideoCapture
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
    private val _localVideoTrack = MutableLiveData<LocalVideoTrack>()
    val localVideoTrack: LiveData<LocalVideoTrack>
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

    // Notify whether the local audio is muted or not
    private val _isLocalAudioMuted = MutableLiveData<Boolean>()
    val isLocalAudioMuted: LiveData<Boolean>
        get() = _isLocalAudioMuted

    // Notify whether the local video is muted or not
    private val _isLocalVideoMuted = MutableLiveData<Boolean>()
    val isLocalVideoMuted: LiveData<Boolean>
        get() = _isLocalVideoMuted

    // Notify whether the back camera is select or the front camera
    private val _isBackCamera = MutableLiveData<Boolean>()
    val isBackCamera: LiveData<Boolean>
        get() = _isBackCamera

    // Presentation VideoTrack
    private val _presentationVideoTrack = MutableLiveData<VideoTrack?>()
    val presentationVideoTrack: LiveData<VideoTrack?>
        get() = _presentationVideoTrack

    // Presentation in main region
    private val _isPresentationInMain = MutableLiveData<Boolean>()
    val isPresentationInMain: LiveData<Boolean>
        get() = _isPresentationInMain

    // Notify whether is sharing or not
    private val _isSharingScreen = MutableLiveData<Boolean>()
    val isSharingScreen: LiveData<Boolean>
        get() = _isSharingScreen

    // Vidhance interface for stabilizing frames
    val _vidhanceInterface = VidhanceInterface()

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

    fun startConference(node: String, vmr: String, displayName: String, pin: String?) {
        val exceptionHandler = CoroutineExceptionHandler { _, exception ->
            // Convert the error into a more descriptive message
            _onError.postValue(exception)
        }
        viewModelScope.launch(exceptionHandler) {
            // Authenticate to the conference
            conference = createConference(node, vmr, displayName, pin)

            // Get access to the local microphone and camera
            val (audioTrack, videoTrack) = getLocalMedia()
            localAudioTrack = audioTrack
            _localVideoTrack.value = videoTrack

            // Initialize the WebRTC media connection. We will sending and receiving media.
            startWebRTCConnection(conference, audioTrack, videoTrack)
        }
    }

    fun onToggleMuteAudio() {
        if (_isLocalAudioMuted.value != true) {
            localAudioTrack.stopCapture()
            _isLocalAudioMuted.value = true
        } else {
            localAudioTrack.startCapture()
            _isLocalAudioMuted.value = false
        }
    }

    fun onToggleMuteVideo() {
        if (_isLocalVideoMuted.value != true) {
            localVideoTrack.value?.stopCapture()
            _isLocalVideoMuted.value = true
        } else {
            localVideoTrack.value?.startCapture()
            _isLocalVideoMuted.value = false
        }
    }

    fun onToggleShareScreen() {
        _isSharingScreen.value = _isSharingScreen.value != true
    }

    fun onDisconnect() {
        _isConnected.value = false
    }

    fun onSwapMainSecondaryVideos() {
        _isPresentationInMain.value = _isPresentationInMain.value != true
    }

    fun extractFloats(input: String): Pair<Float, Float>? {
        val regex = Regex("""\b(0(\.\d*)?|1(\.0*)?),\s*(0(\.\d*)?|1(\.0*)?)\b""")
        val match = regex.find(input)

        if (match != null) {
            val (firstNumber, secondNumber) = match.value.split(',')
            try {
                return Pair(firstNumber.toFloat(), secondNumber.toFloat())
            } catch (e: NumberFormatException) {
                e.printStackTrace()
            }
        }

        return null
    }

    // Message listener
    private val _messageListerner = MessageListener { message ->
        val coordinates = extractFloats(message.payload)

        if (coordinates != null) {
            _vidhanceInterface.clickAndLockZoomAt(coordinates.first, coordinates.second)
        }
    }

    private suspend fun createConference(
        node: String,
        vmr: String,
        displayName: String,
        pin: String?
    ): InfinityConference {

        val okHttpClient = OkHttpClient()
        val request = RequestTokenRequest(displayName = displayName)
        val infinityService = InfinityService.create(okHttpClient)
        lateinit var conference: InfinityConference
        val nodeUrl = URL("https://${node}")

        return withContext(Dispatchers.IO) {
            val response = if (pin != null) {
                infinityService.newRequest(nodeUrl)
                    .conference(vmr)
                    .requestToken(request, pin)
                    .await()
            } else {
                infinityService.newRequest(nodeUrl)
                    .conference(vmr)
                    .requestToken(request)
                    .await()
            }
            conference = InfinityConference.create(
                service = infinityService,
                node = nodeUrl,
                conferenceAlias = displayName,
                response = response
            )
            configureConferenceListeners(conference)
            conference.messenger.registerMessageListener(_messageListerner)
            return@withContext conference
        }
    }

    private fun configureConferenceListeners(conference: InfinityConference) {
        conference.registerConferenceEventListener(ConferenceEventListener { event ->
            when (event) {
                is DisconnectConferenceEvent -> {
                    _isConnected.postValue(false)
                }

                is PresentationStartConferenceEvent -> {
                    mediaConnection.startPresentationReceive()
                    _isPresentationInMain.postValue(true)
                }

                is PresentationStopConferenceEvent -> {
                    mediaConnection.stopPresentationReceive()
                    _isPresentationInMain.postValue(false)
                    _presentationVideoTrack.postValue(null)
                }

                else -> {
                    Log.d("ConferenceViewModel", event.toString())
                }
            }
        })
    }

    private fun getVidhanceInterface(): VidhanceInterface {
        val vidhanceBuilder = VidhanceBuilder.DefaultConfiguration()
            .setLicenseHandler(getLicenseHandler(getApplication(), R.raw.vidhance))
            .setCalibrationHandler(getCalibrationHandler(getApplication(), R.raw.vidhance_mi11i))
            .setSensorDataHandler(SensorDataCollector(getApplication()))
            .setMode(VidhanceProcessor.VidhanceMode.CLICK_AND_LOCK)

        _vidhanceInterface.configureVidhance(vidhanceBuilder)
        return _vidhanceInterface
    }

    private fun getLocalMedia(): Pair<LocalAudioTrack, LocalVideoTrack> {
        val audioTrack: LocalAudioTrack = webRtcMediaConnectionFactory.createLocalAudioTrack()

        val vidhanceInterface = getVidhanceInterface()
        val vidhanceVideoCapturer = VidhanceVideoCapture(0, 100, vidhanceInterface)
        val videoTrack: LocalVideoTrack =
            webRtcMediaConnectionFactory.createLocalVideoTrack(vidhanceVideoCapturer)

        audioTrack.startCapture()
        videoTrack.startCapture(QualityProfile.High)
        return audioTrack to videoTrack
    }

    private fun startWebRTCConnection(
        conference: InfinityConference,
        localAudioTrack: LocalAudioTrack,
        localVideoTrack: LocalVideoTrack
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

        // Define a callback method for when the presentation is received
        val presentationVideoTrackListener =
            MediaConnection.RemoteVideoTrackListener { videoTrack ->
                _presentationVideoTrack.postValue(videoTrack)
            }
        // Attach the callback to the media connection
        mediaConnection.registerPresentationRemoteVideoTrackListener(presentationVideoTrackListener)

        // Enable video and audio main video tracks
        mediaConnection.setMainRemoteVideoTrackEnabled(true)
        mediaConnection.setMainRemoteAudioTrackEnabled(true)

        // Start the media connection.
        mediaConnection.start()
    }

    // The fragment will call this method for obtaining the video track
    fun startScreenShare(intent: Intent) {
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {}
        }
        val presentationVideoTrack =
            webRtcMediaConnectionFactory.createMediaProjectionVideoTrack(intent, callback)
        presentationVideoTrack.startCapture(QualityProfile.High)
        mediaConnection.setPresentationVideoTrack(presentationVideoTrack)
        _presentationVideoTrack.value = presentationVideoTrack
        _isPresentationInMain.value = false
    }

    // The fragment will call this method to stop the screen sharing
    fun stopScreenShare() {
        (presentationVideoTrack.value as LocalVideoTrack).stopCapture()
        mediaConnection.setPresentationVideoTrack(null)
        _presentationVideoTrack.value = null
        _isPresentationInMain.value = false
    }

}