package com.vidhance.inapp.solutions.vidhance.camera

import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CaptureRequest
import android.view.Surface
import com.vidhance.appsdk.VidhanceInterface
import com.vidhance.appsdk.VidhanceProcessor
import com.vidhance.inapp.solutions.utils.SimpleCamera
import org.webrtc.CapturerObserver
import org.webrtc.Size
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import com.vidhance.appsdk.utils.CameraMetaDataBase
import com.vidhance.inapp.solutions.vidhance.interfaces.OnMetadataAvailableListener
import com.vidhance.inapp.solutions.vidhance.interfaces.OnStaticMetaListener


class VidhanceVideoCapture(
    private val cameraIndex: Int,
    private val sensorModeIndex: Int,
    private var vidhanceInterface: VidhanceInterface
) :
    VideoCapturer {
    interface OnSensorOrientationChangedListener {
        fun onSensorOrientationChanged(sensorOrientation: Int)
    }

    public var onSensorOrientationChangedListener: OnSensorOrientationChangedListener? = null
        set(value) {
            field = value
            if (value != null)
                value.onSensorOrientationChanged(sensorOrientation)
        }

    private var sensorOrientation: Int = 0
        set(value) {
            field = value
            if (onSensorOrientationChangedListener != null)
                onSensorOrientationChangedListener?.onSensorOrientationChanged(value)
        }

    private var context: Context? = null
    private var simpleCamera: SimpleCamera? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var meta: VidhanceProcessor.StaticMeta? = null

    private fun getRotationDegrees(context: Context): Int {
        val rotation = context.resources.configuration.orientation
        return if (rotation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 0 else 90
    }

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper?,
        context: Context?,
        observer: CapturerObserver?
    ) {
        this.context = context
        this.surfaceTextureHelper = surfaceTextureHelper
        observer!!.onCapturerStarted(true)

        this.surfaceTextureHelper!!.startListening(VideoSink { videoFrame: VideoFrame? ->
            observer!!.onFrameCaptured(
                VideoFrame(
                    videoFrame!!.buffer,
                    getRotationDegrees(context!!),
                    videoFrame!!.timestampNs
                )
            )
        })
    }

    override fun startCapture(width: Int, height: Int, fps: Int) {
        initCamera(Size(width, height))
    }

    override fun stopCapture() {
        deInitCamera()
    }

    override fun changeCaptureFormat(width: Int, height: Int, fps: Int) {
    }

    override fun dispose() {
        deInitCamera()
        surfaceTextureHelper!!.dispose()
        vidhanceInterface!!.deinitialize()
    }

    override fun isScreencast(): Boolean {
        return false
    }

    private fun deInitCamera() {
        simpleCamera!!.stopBackgroundThread()
        simpleCamera!!.closeCamera()
    }

    @Throws(CameraAccessException::class)
    private fun initCamera(inputResolution: Size) {
        val characteristics: MutableMap<CaptureRequest.Key<Int?>, Int> = HashMap()
        characteristics[CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE] =
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        characteristics[CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE] =
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
        simpleCamera =
            SimpleCamera(this.context!!, characteristics, cameraIndex.toString(), sensorModeIndex)
        simpleCamera!!.setMetadataAvailableListener(object : OnMetadataAvailableListener {
            override fun onMetadataAvailable(metadata: CameraMetaDataBase?) {
                vidhanceInterface!!.onMetaCollected(metadata)
            }
        })
        simpleCamera!!.setOnStaticMetaListener(object : OnStaticMetaListener {
            override fun onStaticMetaUpdated(
                cameraId: Int,
                sensorOrientation: Int,
                sensorModeIndex: Int,
                activeArraySize: Rect?,
                isFrontCamera: Boolean
            ) {
                meta = VidhanceProcessor.StaticMeta(
                    cameraId,
                    sensorModeIndex,
                    activeArraySize,
                    isFrontCamera,
                    sensorOrientation
                )
                vidhanceInterface!!.initializeStaticMeta(meta)
                surfaceTextureHelper!!.setFrameRotation(sensorOrientation)
                this@VidhanceVideoCapture.sensorOrientation = sensorOrientation
            }
        })
        val outputResolution = inputResolution

        vidhanceInterface!!.configureInput(
            android.util.Size(
                inputResolution.height,
                inputResolution.width
            ), 10
        )
        surfaceTextureHelper!!.setTextureSize(
            outputResolution.width,
            outputResolution.height
        )
        val surfaceTexture: SurfaceTexture = surfaceTextureHelper!!.surfaceTexture
        val outputSurface = Surface(surfaceTexture)
        vidhanceInterface!!.configureOutput(outputSurface, 10)
        simpleCamera!!.startBackgroundThread()
        simpleCamera!!.openCamera(
            vidhanceInterface!!.inputSurface,
            android.util.Size(inputResolution.width, inputResolution.height)
        )
    }
}