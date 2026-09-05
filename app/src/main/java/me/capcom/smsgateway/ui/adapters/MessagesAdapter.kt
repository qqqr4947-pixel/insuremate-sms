package me.capcom.smsgateway.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import me.capcom.smsgateway.data.entities.Message
import me.capcom.smsgateway.databinding.ItemMessageBinding
import androidx.core.content.ContextCompat
import me.capcom.smsgateway.ui.styles.bgRes
import me.capcom.smsgateway.ui.styles.color
import me.capcom.smsgateway.ui.styles.fgRes
import me.capcom.smsgateway.ui.styles.labelRes
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.Date

class MessagesAdapter(
    private val onItemClickListener: OnItemClickListener<Message>
) :
    ListAdapter<Message, MessagesAdapter.MessageViewHolder>(MessageDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        return MessageViewHolder.create(parent).also { holder ->
            holder.itemView.setOnClickListener {
                val message = getItem(holder.adapterPosition)
                onItemClickListener.onItemClick(message)
            }
        }
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = getItem(position)

        holder.bind(message)
    }

    class MessageViewHolder(private val binding: ItemMessageBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(message: Message) {
            val ctx = binding.root.context
            // 표 번호(UUID) 대신 «보낸 내용» — 사람은 자기 문자를 번호로 기억하지 않는다.
            //   내용이 비어 있는 종류(자료 문자 등)면 그때만 번호를 보여 준다.
            val body = message.content.trim()
            binding.textViewId.text = if (body.isNotEmpty()) body else message.id
            binding.textViewDate.text = formatWhen(message.createdAt)
            binding.textViewState.text = ctx.getString(message.state.labelRes)
            binding.textViewState.setTextColor(ContextCompat.getColor(ctx, message.state.fgRes))
            binding.textViewState.backgroundTintList =
                ContextCompat.getColorStateList(ctx, message.state.bgRes)
            binding.imageViewState.setColorFilter(message.state.color(ctx))
        }

        // 오늘·어제는 그렇게 부른다 — 「2026. 9. 6. 오전 9:12」 보다 빨리 읽힌다
        private fun formatWhen(at: Long): String {
            val now = Calendar.getInstance()
            val then = Calendar.getInstance().apply { timeInMillis = at }
            val hm = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
            val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
            val dayDiff = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)
            return when {
                sameYear && dayDiff == 0 -> "오늘 $hm"
                sameYear && dayDiff == 1 -> "어제 $hm"
                sameYear -> SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(Date(at))
                else -> DateFormat.getDateTimeInstance().format(Date(at))
            }
        }

        companion object {
            fun create(parent: ViewGroup): MessageViewHolder {
                return MessageViewHolder(
                    ItemMessageBinding.inflate(
                        LayoutInflater.from(parent.context), parent, false
                    )
                )
            }
        }
    }

    class MessageDiffCallback : androidx.recyclerview.widget.DiffUtil.ItemCallback<Message>() {
        override fun areItemsTheSame(oldItem: Message, newItem: Message): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Message, newItem: Message): Boolean {
            return oldItem == newItem
        }
    }

    interface OnItemClickListener<T> {
        fun onItemClick(item: T)
    }
}