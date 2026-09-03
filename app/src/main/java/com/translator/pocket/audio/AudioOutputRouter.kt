package com.translator.pocket.audio

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.translator.pocket.model.AudioOutputTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class AudioOutputRouter(
    private val context: Context
) : SensorEventListener {

    companion object {
        private const val TAG = "AudioOutputRouter"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val proximitySensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var proximityWakeLock: PowerManager.WakeLock? = null

    private val _isHeadphonesConnectedFlow = MutableStateFlow(false)
    val isHeadphonesConnectedFlow = _isHeadphonesConnectedFlow.asStateFlow()

    private val _activeTargetFlow = MutableStateFlow(AudioOutputTarget.AUTO_HEADPHONES)
    val activeTargetFlow = _activeTargetFlow.asStateFlow()

    private var userPreferenceNoHeadphones: AudioOutputTarget = AudioOutputTarget.EARPIECE

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            checkConnectedDevices()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            checkConnectedDevices()
        }
    }

    init {
        // 建立通話貼耳滅屏 WakeLock
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                if (powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                    proximityWakeLock = powerManager.newWakeLock(
                        PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                        "PocketTranslator::ProximityWakeLock"
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "距離感測滅屏不受支援", e)
            }
        }

        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        checkConnectedDevices()
    }

    fun setUserPreference(target: AudioOutputTarget) {
        if (target != AudioOutputTarget.AUTO_HEADPHONES) {
            userPreferenceNoHeadphones = target
        }
        applyRouting()
    }

    private fun checkConnectedDevices() {
        var hasHeadphones = false
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET -> {
                    hasHeadphones = true
                    break
                }
            }
        }

        _isHeadphonesConnectedFlow.value = hasHeadphones
        applyRouting()
    }

    private fun applyRouting() {
        if (_isHeadphonesConnectedFlow.value) {
            // 耳機優先
            _activeTargetFlow.value = AudioOutputTarget.AUTO_HEADPHONES
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
            stopProximitySensor()
            Log.d(TAG, "音訊路由：耳機優先 (藍牙/有線)")
        } else {
            // 無耳機：採用使用者自選模式
            _activeTargetFlow.value = userPreferenceNoHeadphones
            when (userPreferenceNoHeadphones) {
                AudioOutputTarget.EARPIECE -> {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    audioManager.isSpeakerphoneOn = false
                    startProximitySensor()
                    Log.d(TAG, "音訊路由：📞 貼耳聽筒私密模式")
                }
                AudioOutputTarget.SPEAKER -> {
                    audioManager.mode = AudioManager.MODE_NORMAL
                    audioManager.isSpeakerphoneOn = true
                    stopProximitySensor()
                    Log.d(TAG, "音訊路由：📢 外放揚聲器模式")
                }
                AudioOutputTarget.MUTE -> {
                    audioManager.mode = AudioManager.MODE_NORMAL
                    stopProximitySensor()
                    Log.d(TAG, "音訊路由：🔕 靜音純字幕模式")
                }
                else -> {}
            }
        }
    }

    private fun startProximitySensor() {
        proximitySensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun stopProximitySensor() {
        sensorManager?.unregisterListener(this)
        releaseProximityWakeLock()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_PROXIMITY) {
            val distance = event.values[0]
            val maxRange = proximitySensor?.maximumRange ?: 5f
            val isNear = distance < maxRange

            if (isNear) {
                // 靠近耳朵：螢幕黑屏熄滅防誤觸
                acquireProximityWakeLock()
            } else {
                // 移開耳朵：螢幕點亮
                releaseProximityWakeLock()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun acquireProximityWakeLock() {
        if (proximityWakeLock?.isHeld == false) {
            try {
                proximityWakeLock?.acquire(10 * 60 * 1000L)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun releaseProximityWakeLock() {
        if (proximityWakeLock?.isHeld == true) {
            try {
                proximityWakeLock?.release()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    fun release() {
        stopProximitySensor()
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
    }
}
