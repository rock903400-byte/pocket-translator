package com.translator.pocket.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.translator.pocket.databinding.ItemTranslationMessageBinding
import com.translator.pocket.model.TranslationMessage

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

    private val messages = mutableListOf<TranslationMessage>()

    fun addMessage(message: TranslationMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun clearMessages() {
        messages.clear()
        notifyDataSetChanged()
    }

    fun getItemsCount(): Int = messages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val binding = ItemTranslationMessageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    override fun getItemCount(): Int = messages.size

    class MessageViewHolder(private val binding: ItemTranslationMessageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(msg: TranslationMessage) {
            binding.tvSourceLang.text = "原文 (${msg.sourceLangName})"
            binding.tvSourceText.text = msg.originalText

            binding.tvTargetLang.text = "翻譯 (${msg.targetLangName})"
            binding.tvTargetText.text = msg.translatedText
        }
    }
}
