package com.bedtime.dreamtales.service

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Window
import android.view.WindowManager
import com.bedtime.dreamtales.core.constants.AppConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

class FlipSensorManager(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val proximitySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private val _isFaceDown = MutableStateFlow(false)
    val isFaceDown: StateFlow<Boolean> = _isFaceDown.asStateFlow()

    @Volatile
    private var lastAccelX = 0f
    @Volatile
    private var lastAccelY = 0f
    @Volatile
    private var lastAccelZ = 9.8f
    @Volatile
    private var lastDistance = Float.MAX_VALUE

    private var faceDownConditionStartTime = 0L
    private var faceUpConditionStartTime = 0L

    private val scope = CoroutineScope(Dispatchers.Default)
    private var debounceJob: Job? = null

    fun startListening() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        proximitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        debounceJob?.cancel()
        debounceJob = scope.launch {
            while (isActive) {
                checkStateTransition()
                delay(50L)
            }
        }
    }

    fun stopListening() {
        debounceJob?.cancel()
        debounceJob = null
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                lastAccelX = event.values[0]
                lastAccelY = event.values[1]
                lastAccelZ = event.values[2]
            }
            Sensor.TYPE_PROXIMITY -> {
                lastDistance = event.values[0]
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun checkStateTransition() {
        val now = SystemClock.uptimeMillis()

        // 规范判定条件：
        // 1. Z < -8.5 m/s² 且 |X| < 3.0 m/s² 且 |Y| < 3.0 m/s²
        val isAccelFaceDown = lastAccelZ < AppConstants.ACCEL_Z_FACE_DOWN_THRESHOLD &&
                abs(lastAccelX) < AppConstants.ACCEL_XY_FLAT_THRESHOLD &&
                abs(lastAccelY) < AppConstants.ACCEL_XY_FLAT_THRESHOLD

        // 2. 距离传感器报告遮挡（满足规范 distance == 0.0 或接近 0）
        val isProximityCovered = lastDistance <= AppConstants.PROXIMITY_COVER_DISTANCE ||
                (proximitySensor != null && lastDistance < (proximitySensor.maximumRange.coerceAtMost(5f) / 2f))

        val instantFaceDown = isAccelFaceDown && isProximityCovered

        val currentState = _isFaceDown.value

        if (instantFaceDown) {
            faceUpConditionStartTime = 0L
            if (!currentState) {
                if (faceDownConditionStartTime == 0L) {
                    faceDownConditionStartTime = now
                } else if (now - faceDownConditionStartTime >= AppConstants.FACE_DOWN_HOLD_MILLIS) {
                    _isFaceDown.value = true
                    triggerHapticPulse()
                }
            }
        } else {
            faceDownConditionStartTime = 0L
            if (currentState) {
                if (faceUpConditionStartTime == 0L) {
                    faceUpConditionStartTime = now
                } else if (now - faceUpConditionStartTime >= AppConstants.FACE_UP_HOLD_MILLIS) {
                    _isFaceDown.value = false
                }
            }
        }
    }

    private fun triggerHapticPulse() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(45L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(45L)
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    companion object {
        /**
         * 动态控制窗口亮度：
         * isFaceDown == true 扣屏时：screenBrightness = 0.0f（彻底黑屏并拦截误触）
         * isFaceDown == false 拿起时：恢复系统自然亮度，确保所有按钮、文字清晰明亮
         */
        fun applyWindowBrightness(activity: Activity?, faceDown: Boolean) {
            val window = activity?.window ?: return
            val layoutParams = window.attributes
            val targetBrightness = if (faceDown) {
                AppConstants.BRIGHTNESS_FACE_DOWN
            } else {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            if (layoutParams.screenBrightness != targetBrightness) {
                layoutParams.screenBrightness = targetBrightness
                window.attributes = layoutParams
            }
        }
    }
}
