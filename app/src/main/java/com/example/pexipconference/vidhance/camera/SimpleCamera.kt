package com.vidhance.inapp.solutions.utils

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
import com.vidhance.inapp.solutions.vidhance.interfaces.OnMetadataAvailableListener
import com.vidhance.inapp.solutions.vidhance.interfaces.OnStaticMetaListener

class SimpleCamera(
    private val context: Context,
    private val characteristics: Map<CaptureRequest.Key<Int?>, Int>,
    private val cameraId: String,
    private val sensorModeIndex: Int
) {
    private var previewBuilder: CaptureRequest.Builder? = null
    private var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var surface: Surface? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var metadataAvailableListener: OnMetadataAvailableListener? = null
    var cameraCharacteristics: CameraCharacteristics
    var resolution = Size(1280, 720)
    var staticMetaListener: OnStaticMetaListener? = null

    fun setOnStaticMetaListener(listener: OnStaticMetaListener?) {
        staticMetaListener = listener
    }

    fun setOnMetadataAvailableListener(listener: OnMetadataAvailableListener?) {
        metadataAvailableListener = listener
    }

    private inner class CaptureCallback : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            timestamp: Long,
            frameNumber: Long,
        ) {
            if (staticMetaListener != null) {
                staticMetaListener!!.onStaticMetaUpdated(
                    cameraId.toInt(),
                    cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!,
                    sensorModeIndex,
                    cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
                    checkIfCameraIdIsFrontFacing()
                )
            }
        }

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            if (metadataAvailableListener != null) {
                metadataAvailableListener!!.onMetadataAvailable(CameraMetaDataBase(result, cameraCharacteristics, resolution))
            }
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure,
        ) {
        }
    }

    fun setMetadataAvailableListener(listener: OnMetadataAvailableListener?) {
        metadataAvailableListener = listener
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
        if (cameraCaptureSession != null) {
            cameraCaptureSession!!.close()
            cameraCaptureSession = null
        }
        if (cameraDevice != null) {
            cameraDevice!!.close()
            cameraDevice = null
        }
    }

    fun getResolutionToFitDisplay(resolution: Size): Size {
        val displayMetrics = DisplayMetrics()
        (context as Activity).windowManager.defaultDisplay.getMetrics(displayMetrics)
        val DSI_width = displayMetrics.widthPixels
        var newWidth = DSI_width
        var newHeight = DSI_width * resolution.width / resolution.height
        if (resolution.width < resolution.height) {
            newWidth = DSI_width
            var tmp = DSI_width * resolution.height / resolution.width
            newHeight = tmp
        }
        return Size(newWidth, newHeight)
    }

    val availablePreviewSizes: Array<Size>?
        get() = try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId!!)
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

    init {
        cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
    }

    private fun checkIfCameraIdIsFrontFacing(): Boolean {
        try {
            val characteristics =
                cameraManager.getCameraCharacteristics(cameraId!!)
            return characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to access camera: " + e.message)
        }
        return false
    }

    private fun updatePreviewSession() {
        if (cameraCaptureSession != null) {
            val captureCallback: CameraCaptureSession.CaptureCallback =
                CaptureCallback()
            cameraCaptureSession!!.setRepeatingRequest(
                previewBuilder!!.build(),
                captureCallback,
                backgroundHandler,)
        }
    }

    private fun startPreview() {
        previewBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        for (key in characteristics.keys) {
            previewBuilder!!.set(key, characteristics[key])
        }
        previewBuilder!!.addTarget(surface!!)

        try {
            cameraDevice!!.createCaptureSession(
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
        backgroundThread!!.start()
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    fun stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread!!.quitSafely()
            try {
                backgroundThread!!.join()
                backgroundThread = null
                backgroundHandler = null
            } catch (e: InterruptedException) {
                Log.e(TAG, "Interrupted while stopping background thread: " + e.message)
            }
        }
    }

    companion object {
        private const val TAG = "Camera2"
    }
}
