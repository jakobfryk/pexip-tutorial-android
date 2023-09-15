package com.vidhance.inapp.solutions.utils.vidhancewebrtc

import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import com.vidhance.appsdk.VidhanceBuilder
import com.vidhance.appsdk.VidhanceInterface
import com.vidhance.appsdk.VidhanceProcessor
import com.vidhance.appsdk.utils.CameraMetaDataBase
import com.vidhance.appsdk.utils.SensorDataCollector
import com.vidhance.appsdk.utils.getCalibrationHandler
import com.vidhance.appsdk.utils.getLicenseHandler
import com.vidhance.inapp.solutions.R
import com.vidhance.inapp.solutions.utils.DeviceConfiguration
import com.vidhance.inapp.solutions.utils.camera.OnMetadataAvailableListener
import com.vidhance.inapp.solutions.utils.camera.OnStaticMetaListener
import com.vidhance.inapp.solutions.utils.camera.SimpleCamera
import org.webrtc.CapturerObserver
import org.webrtc.Size
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame

class VidhanceVideoCapture(private val cameraIndex: Int, private val sensorModeIndex: Int) :
    VideoCapturer {
    private var sensorOrientation: Int = 0

    /** The interface used to handle image processing events by the VidhanceProcessor. */
    private val vidhanceInterface = VidhanceInterface()
    private lateinit var context: Context
    private lateinit var simpleCamera: SimpleCamera
    private lateinit var surfaceTextureHelper: SurfaceTextureHelper
    private lateinit var meta: VidhanceProcessor.StaticMeta

    enum class ExposureOffset {
        NONE, DARKER, BRIGHTER
    }

    fun currentScreenRotation(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return this.context.display?.rotation ?: 0
        } else {
            @Suppress("DEPRECATION")
            return (this.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        }
    }

    private fun getScreenRotationDegrees(): Int {
        var screenOrientation = 0
        when (currentScreenRotation()) {
            Surface.ROTATION_0 ->
                screenOrientation = sensorOrientation
            Surface.ROTATION_90 ->
                screenOrientation = (270 + sensorOrientation) % 360
            Surface.ROTATION_180 ->
                screenOrientation = (180 + sensorOrientation) % 360
            Surface.ROTATION_270 ->
                screenOrientation = (90 + sensorOrientation) % 360
        }
        return screenOrientation
    }

    override fun initialize(surfaceTextureHelper: SurfaceTextureHelper, context: Context, observer: CapturerObserver) {
        this.context = context
        this.surfaceTextureHelper = surfaceTextureHelper
        observer.onCapturerStarted(true)

        this.surfaceTextureHelper.startListening { videoFrame: VideoFrame ->
            observer.onFrameCaptured(
                VideoFrame(
                    videoFrame.buffer,
                    getScreenRotationDegrees(),
                    videoFrame.timestampNs,
                ),
            )
        }
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
        surfaceTextureHelper.dispose()
        vidhanceInterface.deinitialize()
    }

    override fun isScreencast(): Boolean {
        return false
    }

    /**
     * Configure the Vidhance processor
     * @param mode The Vidhance mode to configure
     */
    fun configureVidhance(mode: VidhanceProcessor.VidhanceMode) {
        val vidhanceBuilder = VidhanceBuilder.DefaultConfiguration()
            .setLicenseHandler(getLicenseHandler(this.context, R.raw.vidhance))
            .setCalibrationHandler(getCalibrationHandler(this.context, DeviceConfiguration.calibrationResourceId))
            .setSensorDataHandler(SensorDataCollector(this.context))
            .setMode(mode)

        vidhanceInterface.configureVidhance(vidhanceBuilder)
    }

    /**
     * Handles the click input event.
     * @param x goes from 0 -> 1, where 0 is pixel 0 and 1 is full width of the image.
     * @param y goes from 0 -> 1, where 0 is pixel 0 and 1 is full height of the image.
     */
    fun handleClickInput(x: Float, y: Float) {
        if (x < 0f || x > 1f || y < 0f || y > 1f) {
            Log.e(TAG, "handleClickInput: invalid coordinates: $x, $y - must be between 0 and 1")
            return
        }
        var xIn = x
        var yIn = y
        // Transform the coordinates according to the screen rotation
        when (val screenRotation = getScreenRotationDegrees()) {
            0 -> {}
            90 -> {
                xIn = y
                yIn = 1.0f - x
            }
            180 -> {
                xIn = 1.0f - x
                yIn = 1.0f - y
            }
            270 -> {
                xIn = 1.0f - y
                yIn = x
            }
            else -> {
                Log.e(TAG, "handleClickInput: unsupported screen rotation: $screenRotation")
            }
        }
        vidhanceInterface.clickAndLockZoomAt(xIn, yIn)
    }

    /**
     * Sets the flashlight status
     * @param status true to turn on the flashlight, false to turn it off
     */
    fun setFlashlight(status: Boolean) {
        simpleCamera.setFlashlight(status)
    }

    /**
     * Change the exposure time offset for the camera
     * @param exposureOffset the exposure input
     */
    fun changeExposureOffset(exposureOffset: ExposureOffset) {
        when (exposureOffset) {
            ExposureOffset.NONE -> {
                simpleCamera.exposureOffsetReset()
            }
            ExposureOffset.DARKER -> {
                simpleCamera.exposureOffsetChange(true)
            }
            ExposureOffset.BRIGHTER -> {
                simpleCamera.exposureOffsetChange(false)
            }
        }
    }

    /**
     * Resets the zoom lock of Click and Lock feature
     */
    fun resetClickAndLock() {
        vidhanceInterface.resetClickAndLock()
    }

    private fun deInitCamera() {
        simpleCamera.stopBackgroundThread()
        simpleCamera.closeCamera()
    }

    @Throws(CameraAccessException::class)
    private fun initCamera(inputResolution: Size) {
        val characteristics: MutableMap<CaptureRequest.Key<Int?>, Int> = HashMap()
        characteristics[CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE] =
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        characteristics[CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE] =
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
        simpleCamera = SimpleCamera(this.context, characteristics, cameraIndex.toString(), sensorModeIndex)
        simpleCamera.setOnMetadataAvailableListener(object : OnMetadataAvailableListener {
            override fun onMetadataAvailable(metadata: CameraMetaDataBase?) {
                vidhanceInterface.onMetaCollected(metadata)
            }
        })
        simpleCamera.setOnStaticMetaListener(object : OnStaticMetaListener {
            override fun onStaticMetaUpdated(
                cameraId: Int,
                sensorOrientation: Int,
                sensorModeIndex: Int,
                activeArraySize: Rect?,
                isFrontCamera: Boolean,
            ) {
                meta = VidhanceProcessor.StaticMeta(
                    cameraId,
                    sensorModeIndex,
                    activeArraySize,
                    isFrontCamera,
                    sensorOrientation,
                )
                vidhanceInterface.initializeStaticMeta(meta)
                this@VidhanceVideoCapture.sensorOrientation = sensorOrientation
            }
        })
        val outputResolution = simpleCamera.getResolutionToFitDisplay(android.util.Size(inputResolution.width, inputResolution.height))

        vidhanceInterface.configureInput(
            android.util.Size(
                inputResolution.width,
                inputResolution.height,
            ),
            bufferPoolSize,
        )
        // TODO: width and height has to be swapped because input and output are rotated with 90 degrees. Investigate the root cause for this behaviour
        surfaceTextureHelper.setTextureSize(
            outputResolution.height,
            outputResolution.width,
        )
        val surfaceTexture: SurfaceTexture = surfaceTextureHelper.surfaceTexture
        val outputSurface = Surface(surfaceTexture)
        vidhanceInterface.configureOutput(outputSurface, bufferPoolSize)
        simpleCamera.startBackgroundThread()
        simpleCamera.openCamera(
            vidhanceInterface.inputSurface,
            android.util.Size(inputResolution.width, inputResolution.height),
        )
    }

    companion object {
        /** The tag used for logging. */
        private val TAG = VidhanceVideoCapture::class.java.simpleName

        /** The frame buffer pool size used by the VidhanceProcessor. */
        private const val bufferPoolSize = 10
    }
}
