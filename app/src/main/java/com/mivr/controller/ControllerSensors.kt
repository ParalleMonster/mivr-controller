package com.mivr.controller

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

internal class ControllerSensors(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val gyroSamples = ArrayDeque<MotionVector>(3)
    private var previousGyro = MotionVector()
    private var acceleration = MotionVector()
    private var magneticField = MotionVector()

    fun start() {
        register(Sensor.TYPE_GYROSCOPE, 3_800)
        register(Sensor.TYPE_ACCELEROMETER, 11_500)
        register(Sensor.TYPE_MAGNETIC_FIELD, 11_500)
    }

    fun stop() {
        manager?.unregisterListener(this)
        gyroSamples.clear()
        previousGyro = MotionVector()
        acceleration = MotionVector()
        magneticField = MotionVector()
    }

    fun gyroReport(counter: Int): ByteArray {
        // Pad slower sensor deliveries with the previous sample, keeping new samples in order.
        val first = if (gyroSamples.size == 3) gyroSamples.removeFirst() else previousGyro
        val second = if (gyroSamples.size == 2) gyroSamples.removeFirst() else first
        val third = gyroSamples.removeFirstOrNull() ?: second
        previousGyro = third
        return ControllerReports.gyro(counter, first, second, third)
    }

    fun sensorReport(counter: Int, input: ControllerInput): ByteArray =
        ControllerReports.sensors(counter, acceleration, magneticField, input)

    override fun onSensorChanged(event: SensorEvent) {
        val value = MotionVector(event.values[0], event.values[1], event.values[2])
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                if (gyroSamples.size == 3) gyroSamples.removeFirst()
                gyroSamples.addLast(value)
            }
            Sensor.TYPE_ACCELEROMETER -> acceleration = value
            Sensor.TYPE_MAGNETIC_FIELD -> magneticField = value
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private fun register(type: Int, periodMicros: Int) {
        val sensor = manager?.getDefaultSensor(type)
        if (sensor == null || !manager.registerListener(this, sensor, periodMicros)) {
            Log.w("MiVR", "Motion sensor unavailable (type=$type)")
        }
    }
}
