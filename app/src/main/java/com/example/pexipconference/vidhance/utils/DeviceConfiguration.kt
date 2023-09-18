package com.example.pexipconference.vidhance.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * This class contains device specific configuration for the Vidhance App SDK.
 */
object DeviceConfiguration {
    private const val TAG = "DeviceConfiguration"

    /** @return the Device currently in use
     */
    /** the device currently in use, will be automatically selected when initDeviceConfiguration is called  */
    private var currentDevice: Device = Device.Other

    /** @return calibration resource id to use based on the current device configuration
     */
    /** resource id of the calibration to use for the device currently in use, will be automatically selected when initDeviceConfiguration is called  */
    var calibrationResourceId = 0
        private set

    /** @return the model name of the device currently in use
     */
    private fun isModel(device: String): Boolean {
        return Build.MODEL == device
    }

    /**
     * Initializes which device configuration and calibration resource id to use.
     * It is important to initialize the configuration before using any other functions in the class.
     * @param context of the Activity from whom the initialization is called
     */
    @SuppressLint("DiscouragedApi")
    fun initDeviceConfiguration(context: Context) {
        // The file name excluding the file extension
        val calibrationFileName: String
        if (isModel("Your device")) {
            currentDevice = Device.YourDevice
            calibrationFileName = "YourDevice"
        } else if (isModel("Your 2nd device")) {
            currentDevice = Device.YourDevice2
            calibrationFileName = "YourDevice2"
        } else {
            currentDevice = Device.Other
            calibrationFileName = "default_calibration"
        }
        calibrationResourceId = context.resources.getIdentifier(calibrationFileName, "raw", context.packageName)
        Log.i(
            TAG, "Using device $currentDevice with calibration resource " + context.resources.getResourceName(
                calibrationResourceId
            ))
    }

    /** enum of devices that have verified calibrations */
    enum class Device {
        YourDevice, YourDevice2, Other
    }
}
