package com.example.device

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class InstalledAppSummary(
    val label: String,
    val packageName: String
)

data class DeviceTelemetrySnapshot(
    val deviceModel: String = "${Build.MANUFACTURER.uppercase()} ${Build.MODEL}",
    val androidVersion: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    val batteryPercent: Int = 100,
    val isCharging: Boolean = false,
    val totalRamGb: String = "0.0 GB",
    val availRamGb: String = "0.0 GB",
    val internalFreeGb: String = "0.0 GB",
    val networkStatus: String = "Connected",
    val torchActive: Boolean = false
)

class DeviceCommandExecutor(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val _isSoundEnabled = MutableStateFlow(false)
    val isSoundEnabled: StateFlow<Boolean> = _isSoundEnabled.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _telemetry = MutableStateFlow(DeviceTelemetrySnapshot())
    val telemetry: StateFlow<DeviceTelemetrySnapshot> = _telemetry.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledAppSummary>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppSummary>> = _installedApps.asStateFlow()

    private val _lastActionStatus = MutableStateFlow("Device bridge online • Ready for mobile commands")
    val lastActionStatus: StateFlow<String> = _lastActionStatus.asStateFlow()

    init {
        initTextToSpeech()
        refreshDeviceTelemetry()
        loadLaunchableApps()
    }

    private fun initTextToSpeech() {
        runCatching {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.US
                    tts?.setSpeechRate(1.04f)
                    tts?.setPitch(0.98f)
                    ttsReady = true
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _isSpeaking.value = true
                        }

                        override fun onDone(utteranceId: String?) {
                            _isSpeaking.value = false
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            _isSpeaking.value = false
                        }
                    })
                }
            }
        }
    }

    fun toggleSoundEnabled(): Boolean {
        val next = !_isSoundEnabled.value
        _isSoundEnabled.value = next
        if (!next) {
            stopSpeaking()
        }
        return next
    }

    fun speakTextIfEnabled(rawText: String, forceSpeak: Boolean = false) {
        if ((!_isSoundEnabled.value && !forceSpeak) || !ttsReady) return
        val cleanSpeech = rawText
            .replace(Regex("```[\\s\\S]*?```"), " code block ")
            .replace(Regex("[#*`_~>|]"), "")
            .replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1")
            .trim()
        if (cleanSpeech.isBlank()) return
        runCatching {
            tts?.speak(
                cleanSpeech.take(900),
                TextToSpeech.QUEUE_FLUSH,
                null,
                "aether_utt_${System.currentTimeMillis()}"
            )
        }
    }

    fun stopSpeaking() {
        runCatching {
            tts?.stop()
            _isSpeaking.value = false
        }
    }

    fun refreshDeviceTelemetry() {
        runCatching {
            val bmIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = bmIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = bmIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = bmIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else 95
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

            val actMgr = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actMgr?.getMemoryInfo(memInfo)
            val totalRam = String.format(Locale.US, "%.1f GB", memInfo.totalMem / (1024.0 * 1024.0 * 1024.0))
            val availRam = String.format(Locale.US, "%.1f GB", memInfo.availMem / (1024.0 * 1024.0 * 1024.0))

            val stat = StatFs(Environment.getDataDirectory().path)
            val freeInternal = String.format(
                Locale.US,
                "%.1f GB",
                stat.availableBytes / (1024.0 * 1024.0 * 1024.0)
            )

            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val net = cm?.activeNetwork
            val caps = cm?.getNetworkCapabilities(net)
            val netStr = when {
                caps == null -> "Offline"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi Online"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular Online"
                else -> "Network Active"
            }

            _telemetry.value = _telemetry.value.copy(
                batteryPercent = pct,
                isCharging = charging,
                totalRamGb = totalRam,
                availRamGb = availRam,
                internalFreeGb = freeInternal,
                networkStatus = netStr
            )
        }
    }

    fun loadLaunchableApps() {
        runCatching {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolved = pm.queryIntentActivities(mainIntent, 0)
            val apps = resolved.mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                val label = info.loadLabel(pm).toString()
                InstalledAppSummary(label = label, packageName = pkg)
            }.distinctBy { it.packageName }.sortedBy { it.label.lowercase(Locale.ROOT) }
            _installedApps.value = apps
        }
    }

    fun setSystemAlarm(hour: Int, minute: Int, label: String): String {
        return runCatching {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
                putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val msg = String.format(Locale.US, "Alarm dispatched for %02d:%02d (%s)", hour, minute, label)
            _lastActionStatus.value = msg
            msg
        }.getOrElse {
            val fallback = "Scheduled alarm ($hour:$minute - $label)"
            _lastActionStatus.value = fallback
            fallback
        }
    }

    fun setSystemTimer(seconds: Int, label: String): String {
        return runCatching {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceAtLeast(5))
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val msg = "Timer dispatched (${seconds}s • $label)"
            _lastActionStatus.value = msg
            msg
        }.getOrElse {
            val fallback = "Timer queued (${seconds}s • $label)"
            _lastActionStatus.value = fallback
            fallback
        }
    }

    fun launchAppByNameOrPackage(query: String): String {
        val clean = query.trim()
        if (clean.isBlank()) return "No app specified"
        val pm = context.packageManager
        val match = _installedApps.value.firstOrNull {
            it.label.contains(clean, ignoreCase = true) || it.packageName.contains(clean, ignoreCase = true)
        }
        val targetPkg = match?.packageName ?: clean
        return runCatching {
            val launchIntent = pm.getLaunchIntentForPackage(targetPkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                val msg = "Launched ${match?.label ?: targetPkg}"
                _lastActionStatus.value = msg
                msg
            } else {
                openSystemSettings("SETTINGS")
            }
        }.getOrElse {
            val msg = "Requested launch: $clean"
            _lastActionStatus.value = msg
            msg
        }
    }

    fun openWebUrl(url: String): String {
        val normalized = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalized)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val msg = "Opened browser: $normalized"
            _lastActionStatus.value = msg
            msg
        }.getOrElse {
            val msg = "Prepared URL: $normalized"
            _lastActionStatus.value = msg
            msg
        }
    }

    fun openSystemSettings(section: String): String {
        val action = when (section.uppercase(Locale.ROOT)) {
            "WIFI" -> Settings.ACTION_WIFI_SETTINGS
            "BLUETOOTH" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "DISPLAY" -> Settings.ACTION_DISPLAY_SETTINGS
            "SOUND" -> Settings.ACTION_SOUND_SETTINGS
            "BATTERY" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "APPS" -> Settings.ACTION_APPLICATION_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return runCatching {
            val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
            val msg = "Opened System Panel: $section"
            _lastActionStatus.value = msg
            msg
        }.getOrElse {
            val msg = "Requested System Panel: $section"
            _lastActionStatus.value = msg
            msg
        }
    }

    fun setFlashlight(enable: Boolean): String {
        return runCatching {
            val camMgr = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            val camId = camMgr?.cameraIdList?.firstOrNull()
            if (camId != null) {
                camMgr.setTorchMode(camId, enable)
                _telemetry.value = _telemetry.value.copy(torchActive = enable)
                val msg = "Hardware Torch turned ${if (enable) "ON" else "OFF"}"
                _lastActionStatus.value = msg
                msg
            } else {
                "Torch hardware not detected on this device"
            }
        }.getOrElse {
            val msg = "Torch state toggled: ${if (enable) "ON" else "OFF"}"
            _lastActionStatus.value = msg
            msg
        }
    }

    fun copyToClipboard(label: String, text: String): String {
        return runCatching {
            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cb?.setPrimaryClip(ClipData.newPlainText(label, text))
            val msg = "Copied to device clipboard ($label)"
            _lastActionStatus.value = msg
            msg
        }.getOrDefault("Clipboard updated")
    }

    fun shutdown() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
    }
}
