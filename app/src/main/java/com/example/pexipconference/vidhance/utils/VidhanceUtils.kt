package com.example.pexipconference.vidhance.utils

import android.content.Context
import android.util.Log
import androidx.annotation.RawRes
import com.vidhance.appsdk.interfaces.CalibrationHandler
import com.vidhance.appsdk.interfaces.LicenseHandler
import org.jetbrains.annotations.NotNull
import java.io.IOException

@NotNull
fun getLicenseHandler(context: Context, @RawRes id: Int) : LicenseHandler? {
    var licenseHandler = object : LicenseHandler {
        override fun loadVidhanceLicense(): ByteArray? {
            val `is` = context.resources.openRawResource(id)
            try {
                val len = `is`.available()
                val buf = ByteArray(len)
                val numRead = `is`.read(buf)
                return buf
            } catch (e: IOException) {
                Log.e(
                    javaClass.simpleName,
                    "problem with InputStream buffer read while reading raw license resource",
                    e
                )
            }
            return null
        }
    }

    return licenseHandler
}

@NotNull
fun getCalibration(context: Context, @RawRes id: Int): CalibrationHandler {
    return object : CalibrationHandler {
        override fun loadVidhanceCalibration(): ByteArray? {
            val `is` = context.resources.openRawResource(id)
            try {
                val len = `is`.available()
                val buf = ByteArray(len)
                val numRead = `is`.read(buf)
                return buf
            } catch (e: IOException) {
                Log.e(
                    javaClass.simpleName,
                    "problem with InputStream buffer read while reading raw calibration resource",
                    e
                )
            }
            return null
        }
    }
}
