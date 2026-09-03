package com.translator.pocket.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.translator.pocket.R
import com.translator.pocket.databinding.BottomSheetSettingsBinding
import com.translator.pocket.model.AppSettings
import com.translator.pocket.model.EngineType

class SettingsBottomSheet(
    private val onSettingsSaved: () -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var settings: AppSettings

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = AppSettings(requireContext())

        // 載入當前設定
        when (settings.engineType) {
            EngineType.GEMINI_LIVE -> binding.rbGeminiLive.isChecked = true
            EngineType.CLOUD_AI -> binding.rbCloudEngine.isChecked = true
            EngineType.BUILTIN -> binding.rbBuiltinEngine.isChecked = true
        }

        binding.etGeminiApiKey.setText(settings.geminiApiKey)
        binding.etGeminiModel.setText(settings.geminiLiveModelName)
        binding.etGroqApiKey.setText(settings.groqApiKey)

        // 初始可見性
        updateInputVisibility(binding.rgEngine.checkedRadioButtonId)

        // 語速 SeekBar (0 ~ 10 代表 1.0x ~ 1.5x)
        val progress = ((settings.ttsSpeed - 1.0f) / 0.05f).toInt().coerceIn(0, 10)
        binding.sbTtsSpeed.progress = progress
        binding.tvTtsSpeedValue.text = String.format("%.2fx", settings.ttsSpeed)

        binding.sbTtsSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val speed = 1.0f + (progress * 0.05f)
                binding.tvTtsSpeedValue.text = String.format("%.2fx", speed)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnGetGeminiKey.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))
            startActivity(intent)
        }

        binding.btnGetGroqKey.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys"))
            startActivity(intent)
        }

        binding.rgEngine.setOnCheckedChangeListener { _, checkedId ->
            updateInputVisibility(checkedId)
        }

        binding.btnSaveSettings.setOnClickListener {
            val selectedEngine = when {
                binding.rbGeminiLive.isChecked -> EngineType.GEMINI_LIVE
                binding.rbCloudEngine.isChecked -> EngineType.CLOUD_AI
                else -> EngineType.BUILTIN
            }
            settings.engineType = selectedEngine
            settings.geminiApiKey = binding.etGeminiApiKey.text.toString().trim()
            // 這個欄位只在 Gemini Live 模式顯示，對應 Live API 專用模型
            settings.geminiLiveModelName = binding.etGeminiModel.text.toString().trim()
            settings.groqApiKey = binding.etGroqApiKey.text.toString().trim()

            val speed = 1.0f + (binding.sbTtsSpeed.progress * 0.05f)
            settings.ttsSpeed = speed

            Toast.makeText(requireContext(), "設定已儲存", Toast.LENGTH_SHORT).show()
            onSettingsSaved()
            dismiss()
        }
    }

    private fun updateInputVisibility(checkedId: Int) {
        binding.layoutGeminiKey.visibility =
            if (checkedId == R.id.rbGeminiLive) View.VISIBLE else View.GONE
        binding.layoutGroqKey.visibility =
            if (checkedId == R.id.rbCloudEngine) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
