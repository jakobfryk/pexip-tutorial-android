package com.vidhance.inapp.solutions.vidhance.interfaces

import android.graphics.Rect
import com.vidhance.appsdk.utils.CameraMetaDataBase

interface OnMetadataAvailableListener {
    fun onMetadataAvailable(metadata: CameraMetaDataBase?)
}

interface OnStaticMetaListener {
    fun onStaticMetaUpdated(
        cameraId: Int,
        sensorOrientation: Int,
        sensorModeIndex: Int,
        activeArraySize: Rect?,
        isFrontCamera: Boolean
    )
}