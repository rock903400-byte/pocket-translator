package com.translator.pocket

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.translator.pocket.databinding.ActivityMainBinding
import com.translator.pocket.model.AppSettings
import com.translator.pocket.model.AudioOutputTarget
import com.translator.pocket.model.EngineType
import com.translator.pocket.model.TranslationMode
import com.translator.pocket.service.LiveTranslationService
import com.translator.pocket.ui.ChatAdapter
import com.translator.pocket.ui.SettingsBottomSheet
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: AppSettings
    private lateinit var chatAdapter: ChatAdapter

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            checkHeadphonesDirectly()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            checkHeadphonesDirectly()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (!recordAudioGranted) {
            Toast.makeText(this, "需開啟麥克風錄音權限才能進行即時口譯", Toast.LENGTH_LONG).show()
        } else {
            checkBatteryOptimization()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = AppSettings(this)

        // 動態偵測系統狀態列與導航列安全邊界，徹底防止被電池/訊號/挖孔遮擋
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.topBar.setPadding(
                binding.topBar.paddingLeft,
                statusBars.top + 8,
                binding.topBar.paddingRight,
                binding.topBar.paddingBottom
            )
            binding.layoutBottom.setPadding(
                binding.layoutBottom.paddingLeft,
                binding.layoutBottom.paddingTop,
                binding.layoutBottom.paddingRight,
                navBars.bottom + 16
            )
            insets
        }

        setupRecyclerView()
        setupLanguageSpinners()
        setupModeToggle()
        setupAudioOutputToggle()
        setupButtons()
        setupStealthMode()
        observeServiceFlows()
        checkPermissions()

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        checkHeadphonesDirectly()
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter()
        binding.rvMessages.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
        }
    }

    private fun setupLanguageSpinners() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            settings.supportedLanguages
        )
        binding.spnSourceLang.adapter = adapter
        binding.spnTargetLang.adapter = adapter

        binding.spnSourceLang.setSelection(settings.sourceLangIndex.coerceIn(0, settings.supportedLanguages.size - 1))
        binding.spnTargetLang.setSelection(settings.targetLangIndex.coerceIn(0, settings.supportedLanguages.size - 1))

        binding.spnSourceLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                settings.sourceLangIndex = position
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.spnTargetLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                settings.targetLangIndex = position
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 語言交換按鈕
        binding.btnSwapLang.setOnClickListener {
            val src = binding.spnSourceLang.selectedItemPosition
            val tgt = binding.spnTargetLang.selectedItemPosition
            binding.spnSourceLang.setSelection(tgt)
            binding.spnTargetLang.setSelection(src)
        }
    }

    private fun setupModeToggle() {
        when (settings.translationMode) {
            TranslationMode.ONE_WAY -> binding.toggleMode.check(R.id.btnModeOneWay)
            TranslationMode.TWO_WAY -> binding.toggleMode.check(R.id.btnModeTwoWay)
        }

        binding.toggleMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                if (checkedId == R.id.btnModeOneWay) {
                    settings.translationMode = TranslationMode.ONE_WAY
                } else {
                    settings.translationMode = TranslationMode.TWO_WAY
                }
            }
        }
    }

    private fun setupAudioOutputToggle() {
        when (settings.audioOutputPreference) {
            AudioOutputTarget.EARPIECE -> binding.toggleAudioOutput.check(R.id.btnOutputEarpiece)
            AudioOutputTarget.SPEAKER -> binding.toggleAudioOutput.check(R.id.btnOutputSpeaker)
            AudioOutputTarget.MUTE -> binding.toggleAudioOutput.check(R.id.btnOutputMute)
            else -> binding.toggleAudioOutput.check(R.id.btnOutputEarpiece)
        }

        binding.toggleAudioOutput.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val target = when (checkedId) {
                    R.id.btnOutputSpeaker -> AudioOutputTarget.SPEAKER
                    R.id.btnOutputMute -> AudioOutputTarget.MUTE
                    else -> AudioOutputTarget.EARPIECE
                }
                settings.audioOutputPreference = target
                LiveTranslationService.audioRouterInstance?.setUserPreference(target)
            }
        }
    }

    private fun checkHeadphonesDirectly() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val hasHeadphones = devices.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        runOnUiThread {
            updateHeadphonesUi(hasHeadphones)
        }
    }

    private fun updateHeadphonesUi(hasHeadphones: Boolean) {
        if (hasHeadphones) {
            binding.layoutHeadphonesActive.visibility = View.VISIBLE
            binding.layoutNoHeadphones.visibility = View.GONE
        } else {
            binding.layoutHeadphonesActive.visibility = View.GONE
            binding.layoutNoHeadphones.visibility = View.VISIBLE
        }
    }

    private fun setupButtons() {
        // 開始 / 停止切換
        binding.btnToggleTranslation.setOnClickListener {
            if (LiveTranslationService.isRunningFlow.value) {
                stopTranslationService()
            } else {
                startTranslationService()
            }
        }

        // 清空紀錄
        binding.btnClear.setOnClickListener {
            chatAdapter.clearMessages()
            binding.layoutEmptyState.visibility = View.VISIBLE
        }

        // 快捷設定 API Key (大按鈕)
        binding.btnQuickApiKey.setOnClickListener {
            val sheet = SettingsBottomSheet {
                // 設定變更後重新讀取
            }
            sheet.show(supportFragmentManager, "SettingsBottomSheet")
        }

        // 設定選單
        binding.btnSettings.setOnClickListener {
            val sheet = SettingsBottomSheet {
                // 設定變更後重新讀取
            }
            sheet.show(supportFragmentManager, "SettingsBottomSheet")
        }
    }

    private fun setupStealthMode() {
        // 進入 AMOLED 全黑省電防誤觸模式
        binding.btnStealthMode.setOnClickListener {
            binding.stealthOverlay.visibility = View.VISIBLE
        }

        // 雙擊全黑遮罩退出省電模式
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                binding.stealthOverlay.visibility = View.GONE
                return true
            }
        })

        binding.stealthOverlay.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun startTranslationService() {
        // 檢查金鑰設定，若未填寫則彈出導引對話框，絕不默默卡死
        if (settings.engineType == EngineType.GEMINI_LIVE && settings.geminiApiKey.isBlank()) {
            showMissingKeyDialog("Gemini Live 真人同步口譯")
            return
        }
        if (settings.engineType == EngineType.CLOUD_AI && settings.groqApiKey.isBlank() && settings.geminiApiKey.isBlank()) {
            showMissingKeyDialog("極速 AI 同聲傳譯")
            return
        }

        val intent = Intent(this, LiveTranslationService::class.java).apply {
            action = LiveTranslationService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun showMissingKeyDialog(engineName: String) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("💡 尚未填入 API Key")
            .setMessage("您目前選擇了「$engineName」，需要 API Key 才能連線雲端 AI 模型。\n\n• 若您有 Key：請前往設定貼上\n• 若您無 Key：可一鍵切換為免費免金鑰模式")
            .setPositiveButton("前往設定填寫") { _, _ ->
                val sheet = SettingsBottomSheet {}
                sheet.show(supportFragmentManager, "SettingsBottomSheet")
            }
            .setNeutralButton("一鍵切換免金鑰模式") { _, _ ->
                settings.engineType = EngineType.BUILTIN
                Toast.makeText(this, "已切換為免費免金鑰模式！", Toast.LENGTH_SHORT).show()
                startTranslationService()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun stopTranslationService() {
        val intent = Intent(this, LiveTranslationService::class.java).apply {
            action = LiveTranslationService.ACTION_STOP
        }
        startService(intent)
    }

    private fun observeServiceFlows() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    LiveTranslationService.isRunningFlow.collect { isRunning ->
                        updateRunningUi(isRunning)
                    }
                }
                launch {
                    LiveTranslationService.statusTextFlow.collect { status ->
                        binding.tvStatus.text = status
                    }
                }
                launch {
                    LiveTranslationService.messageFlow.collect { msg ->
                        chatAdapter.addMessage(msg)
                        binding.layoutEmptyState.visibility = View.GONE
                        binding.rvMessages.smoothScrollToPosition(chatAdapter.itemCount - 1)
                    }
                }
            }
        }
    }

    private fun updateRunningUi(isRunning: Boolean) {
        if (isRunning) {
            binding.btnToggleTranslation.text = getString(R.string.btn_stop_translation)
            binding.btnToggleTranslation.setBackgroundResource(R.drawable.bg_button_stop)
            binding.spnSourceLang.isEnabled = false
            binding.spnTargetLang.isEnabled = false
            binding.btnSwapLang.isEnabled = false
            binding.btnModeOneWay.isEnabled = false
            binding.btnModeTwoWay.isEnabled = false
        } else {
            binding.btnToggleTranslation.text = getString(R.string.btn_start_translation)
            binding.btnToggleTranslation.setBackgroundResource(R.drawable.bg_button_primary)
            binding.spnSourceLang.isEnabled = true
            binding.spnTargetLang.isEnabled = true
            binding.btnSwapLang.isEnabled = true
            binding.btnModeOneWay.isEnabled = true
            binding.btnModeTwoWay.isEnabled = true
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        val neededPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (neededPermissions.isNotEmpty()) {
            permissionLauncher.launch(neededPermissions.toTypedArray())
        } else {
            checkBatteryOptimization()
        }
    }

    /**
     * 檢查是否已將 App 加入電池最佳化白名單（確保鎖屏放口袋完全不被休眠砍掉）
     */
    @SuppressLint("BatteryLife")
    private fun checkBatteryOptimization() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val packageName = packageName

        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
                .setTitle("💡 建議允許「不受限制」電池用量")
                .setMessage("為確保手機按下電源鍵【關閉螢幕】放入口袋時，Android 系統不會中斷麥克風收音與口譯，建議將本程式設定為「不最佳化 / 無限制」。")
                .setPositiveButton("前往設定") { _, _ ->
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        startActivity(intent)
                    }
                }
                .setNegativeButton("稍後再說", null)
                .show()
        }
    }

    override fun onDestroy() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        super.onDestroy()
    }
}
