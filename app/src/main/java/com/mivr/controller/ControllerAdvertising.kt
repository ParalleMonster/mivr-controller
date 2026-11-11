package com.mivr.controller

import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.os.Build
import android.os.ParcelUuid

internal object ControllerAdvertising {
    val fixedAddress = when (Build.DEVICE) {
        "corot" -> "F2:6A:A7:11:53:CB"
        "pandora" -> "EA:27:54:8C:39:B1"
        else -> null
    }

    fun settings() = AdvertiseSettings.Builder()
        .setConnectable(true)
        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)

    fun data(): AdvertiseData = AdvertiseData.Builder()
        .addServiceUuid(ParcelUuid(ControllerProfile.DEVICE_INFORMATION))
        .addServiceUuid(ParcelUuid(ControllerProfile.CONTROLLER_SERVICE))
        .addManufacturerData(ControllerProfile.MANUFACTURER_ID, byteArrayOf(0))
        .build()

    fun scanResponse(): AdvertiseData = AdvertiseData.Builder().setIncludeDeviceName(true).build()
}
