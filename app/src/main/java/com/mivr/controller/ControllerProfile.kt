package com.mivr.controller

import android.bluetooth.BluetoothGattCharacteristic as Characteristic
import android.bluetooth.BluetoothGattDescriptor as Descriptor
import android.bluetooth.BluetoothGattService as Service
import java.util.UUID

internal object ControllerProfile {
    const val NAME = "OMVR-V190"
    const val MANUFACTURER_ID = 0x058E
    val DEVICE_INFORMATION = standardUuid(0x180A)
    val CONTROLLER_SERVICE = controllerUuid("5652")
    val STATUS = controllerUuid("a920")
    val CALIBRATION = controllerUuid("1d57")
    val GYRO = controllerUuid("beef")
    val SENSORS = controllerUuid("face")
    private val secondaryService = secondaryUuid("f7")
    val CLIENT_CONFIGURATION = standardUuid(0x2902)
    private val userDescription = standardUuid(0x2901)

    val deviceInformationValues = mapOf(
        standardUuid(0x2A29) to "Oculus",
        standardUuid(0x2A24) to NAME,
        standardUuid(0x2A25) to "MIVR-TEST-01",
        standardUuid(0x2A27) to "MP",
        standardUuid(0x2A26) to "2.7.1",
        standardUuid(0x2A28) to "c07df5381135",
    )

    fun services(encryptMotionSubscriptions: Boolean): List<Service> {
        val information = Service(DEVICE_INFORMATION, Service.SERVICE_TYPE_PRIMARY)
        for (id in deviceInformationValues.keys) {
            information.addCharacteristic(characteristic(id, Characteristic.PROPERTY_READ))
        }

        val controller = Service(CONTROLLER_SERVICE, Service.SERVICE_TYPE_PRIMARY)
        controller.addCharacteristic(characteristic(
            STATUS, Characteristic.PROPERTY_READ or Characteristic.PROPERTY_INDICATE, true,
        ))
        controller.addCharacteristic(characteristic(
            CALIBRATION, Characteristic.PROPERTY_READ or Characteristic.PROPERTY_WRITE, true,
        ))
        for (id in listOf(GYRO, SENSORS)) {
            controller.addCharacteristic(characteristic(
                id, Characteristic.PROPERTY_NOTIFY,
                withDescription = true, encryptSubscription = encryptMotionSubscriptions,
            ))
        }

        val secondary = Service(secondaryService, Service.SERVICE_TYPE_PRIMARY)
        secondary.addCharacteristic(characteristic(
            secondaryUuid("f8"), Characteristic.PROPERTY_READ or Characteristic.PROPERTY_WRITE,
        ))
        secondary.addCharacteristic(characteristic(
            secondaryUuid("f9"), Characteristic.PROPERTY_READ or Characteristic.PROPERTY_NOTIFY,
        ))
        secondary.addCharacteristic(characteristic(secondaryUuid("fa"), Characteristic.PROPERTY_WRITE_NO_RESPONSE))
        return listOf(information, controller, secondary)
    }

    fun statusReport(batteryPercent: Int): ByteArray = ByteArray(13).apply {
        // The headset's 1.1.281 decoder reads the first byte as a percentage.
        this[0] = (batteryPercent.takeIf { it in 0..100 } ?: 100).toByte()
    }

    private fun characteristic(
        id: UUID,
        properties: Int,
        withDescription: Boolean = false,
        encryptSubscription: Boolean = false,
    ): Characteristic {
        val readable = properties and Characteristic.PROPERTY_READ != 0
        val writable = properties and (Characteristic.PROPERTY_WRITE or Characteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        val subscribable = properties and (Characteristic.PROPERTY_NOTIFY or Characteristic.PROPERTY_INDICATE) != 0
        val permissions = (if (readable) Characteristic.PERMISSION_READ else 0) or
            (if (writable) Characteristic.PERMISSION_WRITE else 0)
        return Characteristic(id, properties, permissions).apply {
            if (subscribable) {
                val writePermission = if (encryptSubscription) {
                    Descriptor.PERMISSION_WRITE_ENCRYPTED
                } else {
                    Descriptor.PERMISSION_WRITE
                }
                addDescriptor(Descriptor(CLIENT_CONFIGURATION, Descriptor.PERMISSION_READ or writePermission))
            }
            if (withDescription) {
                addDescriptor(Descriptor(userDescription, Descriptor.PERMISSION_READ or Descriptor.PERMISSION_WRITE))
            }
        }
    }

    private fun standardUuid(id: Int): UUID = UUID.fromString(
        "0000${id.toString(16).padStart(4, '0')}-0000-1000-8000-00805f9b34fb",
    )

    private fun controllerUuid(id: String): UUID = UUID.fromString("8126$id-3692-ae93-e711-270f223c83b3")

    private fun secondaryUuid(id: String): UUID = UUID.fromString("189a54$id-11d3-47d3-b3f7-67c664a22710")
}
