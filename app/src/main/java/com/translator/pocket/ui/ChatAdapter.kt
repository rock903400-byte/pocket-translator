package com.translator.pocket.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.translator.pocket.databinding.ItemTranslationMessageBinding
import com.translator.pocket.model.TranslationMessage

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

    companion object {
        /** 只更新文字，不重綁整列，避免可見的閃爍。 */
        private const val PAYLOAD_TEXT = "text"
    }

    private val messages = mutableListOf<TranslationMessage>()

    fun addMessage(message: TranslationMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    /**
     * 依 id 就地更新，找不到才新增。
     * 這是繁體中文「先顯示暫定譯文、稍後以同一個 id 換成正式版本」的基礎。
     */
    fun upsertMessage(message: TranslationMessage) {
        val index = messages.indexOfFirst { it.id == message.id }
        if (index >= 0) {
            messages[index] = message
            notifyItemChanged(index, PAYLOAD_TEXT)
        } else {
            messages.add(message)
            notifyItemInserted(messages.size - 1)
        }
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

    override fun onBindViewHolder(
        holder: MessageViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(PAYLOAD_TEXT)) {
            holder.bindTextOnly(messages[position])
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
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

        fun bindTextOnly(msg: TranslationMessage) {
            binding.tvSourceText.text = msg.originalText
            binding.tvTargetText.text = msg.translatedText
        }
    }
}
