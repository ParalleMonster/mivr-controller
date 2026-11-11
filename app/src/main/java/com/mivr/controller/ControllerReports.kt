package com.mivr.controller

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

internal data class MotionVector(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f)

internal class ControllerInput {
    private var touchButtons = 0
    var volumeTrigger = false
    val buttons get() = touchButtons or (if (volumeTrigger) ControllerReports.TRIGGER else 0)
    var touchX = 160
        private set
    var touchY = 160
        private set

    fun setButton(mask: Int, pressed: Boolean) {
        touchButtons = if (pressed) touchButtons or mask else touchButtons and mask.inv()
    }

    fun setTouch(x: Float, y: Float) {
        touchX = (x.coerceIn(0f, 1f) * 320).roundToInt()
        touchY = (y.coerceIn(0f, 1f) * 320).roundToInt()
        setButton(ControllerReports.TOUCHING, true)
    }

    fun releaseAll() {
        touchButtons = 0
        volumeTrigger = false
    }
}

internal object ControllerReports {
    const val OCULUS = 0x01
    const val BACK = 0x02
    const val TRIGGER = 0x04
    const val TOUCHPAD_CLICK = 0x08
    const val TOUCHING = 0x10

    // Scale factors used by the 1.1.281 headset decoders.
    private const val GYRO_RADIANS_PER_COUNT = 0.06103702f * 0.017453292f
    private const val ACCEL_MS2_PER_COUNT = 9.81f / 128f
    private const val MAG_MICROTESLA_PER_COUNT = 0.15f

    fun gyro(counter: Int, first: MotionVector, second: MotionVector, third: MotionVector): ByteArray =
        packet(counter).apply {
            putVector(first, GYRO_RADIANS_PER_COUNT)
            putVector(second, GYRO_RADIANS_PER_COUNT)
            putVector(third, GYRO_RADIANS_PER_COUNT)
        }.array()

    fun sensors(counter: Int, acceleration: MotionVector, magneticField: MotionVector, input: ControllerInput): ByteArray =
        packet(counter).apply {
            putVector(acceleration, ACCEL_MS2_PER_COUNT, -2048, 2047, 0xfff)
            putVector(magneticField, MAG_MICROTESLA_PER_COUNT)
            putShort(input.touchX.toShort())
            putShort(input.touchY.toShort())
            put(0) // Nominal temperature code for the controller IMU.
            put(input.buttons.toByte())
        }.array()

    private fun packet(counter: Int) = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        .putShort(counter.toShort())

    private fun ByteBuffer.putVector(
        vector: MotionVector,
        unitsPerCount: Float,
        min: Int = Short.MIN_VALUE.toInt(),
        max: Int = Short.MAX_VALUE.toInt(),
        mask: Int = 0xffff,
    ) {
        for (value in floatArrayOf(vector.x, vector.y, vector.z)) {
            val count = if (value.isFinite()) (value / unitsPerCount).roundToInt().coerceIn(min, max) else 0
            putShort((count and mask).toShort())
        }
    }
}
