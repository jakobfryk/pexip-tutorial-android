package com.vidhance.inapp.solutions.utils.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.core.app.ActivityCompat
import com.vidhance.appsdk.utils.CameraMetaDataBase
import kotlin.math.ceil
import kotlin.math.roundToInt

class SimpleCamera(
    private val context: Context,
    private val characteristics: Map<CaptureRequest.Key<Int?>, Int>,
    private val cameraId: String,
    private val sensorModeIndex: Int,
) {
    private lateinit var previewBuilder: CaptureRequest.Builder
    private val cameraManager: CameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private lateinit var cameraDevice: CameraDevice
    private lateinit var cameraCaptureSession: CameraCaptureSession
    private lateinit var surface: Surface
    private lateinit var backgroundThread: HandlerThread
    private lateinit var backgroundHandler: Handler
    private val cameraCharacteristics: CameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
    private lateinit var resolution: Size
    private lateinit var staticMetaListener: OnStaticMetaListener
    private lateinit var metadataAvailableListener: OnMetadataAvailableListener

    private var exposureOffsetSteps = 0 // Store the current exposure setting

    fun setOnStaticMetaListener(listener: OnStaticMetaListener) {
        staticMetaListener = listener
    }

    fun setOnMetadataAvailableListener(listener: OnMetadataAvailableListener) {
        metadataAvailableListener = listener
    }

    private inner class CaptureCallback : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            timestamp: Long,
            frameNumber: Long,
        ) {
            staticMetaListener.onStaticMetaUpdated(
                cameraId.toInt(),
                cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!,
                sensorModeIndex,
                cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
                checkIfCameraIdIsFrontFacing(),
            )
        }

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            metadataAvailableListener.onMetadataAvailable(CameraMetaDataBase(result, cameraCharacteristics, resolution))
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure,
        ) {
        }
    }

    fun openCamera(previewSurface: Surface, inputResolution: Size) {
        surface = previewSurface
        this.resolution = inputResolution

        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return
            }
            cameraManager.openCamera(cameraId, cameraStateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to open camera: " + e.message)
        }
    }

    fun closeCamera() {
        if (this::cameraCaptureSession.isInitialized) {
            cameraCaptureSession.close()
        }
        if (this::cameraDevice.isInitialized) {
            cameraDevice.close()
        }
    }

    /**
     * Sets the flashlight status
     * @param status true to turn on the flashlight, false to turn it off
     */
    fun setFlashlight(status: Boolean) {
        previewBuilder.set(CaptureRequest.FLASH_MODE, if (status) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        updatePreviewSession()
    }

    /**
     * Changes the exposure offset by the given number of steps
     * @param darker true to make the image darker, false to make it brighter
     */
    fun exposureOffsetChange(darker: Boolean) {
        val evPerStep = cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)!!.toFloat()
        val exposureOffsetStepsPerButtonPress = ceil(1.0 / (EXPOSURE_CLICKS_PER_EV * evPerStep)).toInt()
        val maxExposureSteps = (EXPOSURE_MAX_OFFSET_EV / evPerStep).roundToInt()
        val minExposureSteps = -(EXPOSURE_MAX_OFFSET_EV / evPerStep).roundToInt()

        if (darker) {
            if ((this.exposureOffsetSteps - exposureOffsetStepsPerButtonPress) < minExposureSteps) {
                this.exposureOffsetSteps = minExposureSteps
            } else {
                this.exposureOffsetSteps -= exposureOffsetStepsPerButtonPress
            }
        } else { // brighter
            if ((this.exposureOffsetSteps + exposureOffsetStepsPerButtonPress) > maxExposureSteps) {
                this.exposureOffsetSteps = maxExposureSteps
            } else {
                this.exposureOffsetSteps += exposureOffsetStepsPerButtonPress
            }
        }
        this.updateExposure()
    }

    /**
     * Resets the exposure offset to 0
     */
    fun exposureOffsetReset() {
        this.exposureOffsetSteps = 0
        this.updateExposure()
    }

    /**
     * Updates the exposure offset by sending the updated capture request to the camera
     */
    private fun updateExposure() {
        previewBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, this.exposureOffsetSteps)
        updatePreviewSession()
    }

    fun getResolutionToFitDisplay(resolution: Size): Size {
        val displayMetrics = DisplayMetrics()
        (context as Activity).windowManager.defaultDisplay.getMetrics(displayMetrics)
        val dsiWidth = displayMetrics.widthPixels
        var newWidth = dsiWidth
        var newHeight = dsiWidth * resolution.width / resolution.height
        if (resolution.width < resolution.height) {
            newWidth = dsiWidth
            val tmp = dsiWidth * resolution.height / resolution.width
            newHeight = tmp
        }
        return Size(newWidth, newHeight)
    }

    val availablePreviewSizes: Array<Size>?
        get() = try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            map!!.getOutputSizes(SurfaceTexture::class.java)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to get camera characteristics: " + e.message)
            null
        }
    private val cameraStateCallback: CameraDevice.StateCallback =
        object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                startPreview()
            }

            override fun onDisconnected(camera: CameraDevice) {
                closeCamera()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                closeCamera()
            }
        }

    fun checkIfCameraIdIsFrontFacing(): Boolean {
        try {
            val characteristics =
                cameraManager.getCameraCharacteristics(cameraId)
            return characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to access camera: " + e.message)
        }
        return false
    }

    private fun updatePreviewSession() {
        // create instance of MyCaptureCallback class
        val captureCallback: CameraCaptureSession.CaptureCallback = CaptureCallback()
        cameraCaptureSession.setRepeatingRequest(
            previewBuilder.build(),
            captureCallback,
            backgroundHandler,
        )
    }

    private fun startPreview() {
        previewBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        for (key in characteristics.keys) {
            previewBuilder.set(key, characteristics[key])
        }
        previewBuilder.addTarget(surface)

        try {
            cameraDevice.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        cameraCaptureSession = session
                        try {
                            updatePreviewSession()
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, "Failed to set repeating request: " + e.message)
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Camera capture session configuration failed")
                    }
                },
                backgroundHandler,
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to update preview: " + e.message)
        }
    }

    fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground")
        backgroundThread.start()
        backgroundHandler = Handler(backgroundThread.looper)
    }

    fun stopBackgroundThread() {
        backgroundThread.quitSafely()
        try {
            backgroundThread.join()
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while stopping background thread: " + e.message)
        }
    }

    companion object {
        private const val TAG = "Camera2"
        private const val EXPOSURE_MAX_OFFSET_EV = 5
        private const val EXPOSURE_CLICKS_PER_EV = 3
    }
}
