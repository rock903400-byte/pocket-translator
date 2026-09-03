package com.translator.pocket.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.translator.pocket.databinding.BottomSheetSettingsBinding
import com.translator.pocket.model.AppSettings

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

        binding.etGeminiApiKey.setText(settings.geminiApiKey)
        binding.etGeminiModel.setText(settings.geminiLiveModelName)

        binding.btnGetGeminiKey.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "無法開啟瀏覽器", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSaveSettings.setOnClickListener {
            settings.geminiApiKey = binding.etGeminiApiKey.text.toString().trim()
            settings.geminiLiveModelName = binding.etGeminiModel.text.toString().trim()

            Toast.makeText(requireContext(), "設定已儲存", Toast.LENGTH_SHORT).show()
            onSettingsSaved()
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
