// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.metallic.chiaki.R
import com.metallic.chiaki.databinding.ItemHomeConsoleBinding

/** AltyazıTR: ana ekrandaki yatay konsol sırası. */
class HomeConsoleAdapter(
	private val clickCallback: (HomeConsole) -> Unit,
	private val longClickCallback: (HomeConsole) -> Unit
): ListAdapter<HomeConsole, HomeConsoleAdapter.ViewHolder>(DIFF)
{
	companion object
	{
		private const val PAYLOAD_SELECTION = "selection"

		private val DIFF = object: DiffUtil.ItemCallback<HomeConsole>()
		{
			override fun areItemsTheSame(oldItem: HomeConsole, newItem: HomeConsole) = oldItem.key == newItem.key
			override fun areContentsTheSame(oldItem: HomeConsole, newItem: HomeConsole) =
				oldItem.name == newItem.name
					&& oldItem.host == newItem.host
					&& oldItem.state == newItem.state
					&& oldItem.isRegistered == newItem.isRegistered
					&& oldItem.isManual == newItem.isManual
		}
	}

	class ViewHolder(val binding: ItemHomeConsoleBinding): RecyclerView.ViewHolder(binding.root)

	var selectedKey: String? = null
		set(value)
		{
			if(field == value)
				return
			field = value
			if(itemCount > 0)
				notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
		}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder
	{
		val holder = ViewHolder(ItemHomeConsoleBinding.inflate(LayoutInflater.from(parent.context), parent, false))
		HomeFocus.applyScale(holder.binding.root, 1.06f)
		return holder
	}

	override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>)
	{
		if(payloads.isNotEmpty() && payloads.all { it == PAYLOAD_SELECTION })
		{
			bindSelection(holder, getItem(position))
			return
		}
		super.onBindViewHolder(holder, position, payloads)
	}

	override fun onBindViewHolder(holder: ViewHolder, position: Int)
	{
		val console = getItem(position)
		val context = holder.itemView.context
		holder.binding.nameTextView.text = console.name ?: context.getString(R.string.home_console_unnamed)
		val stateText = context.getString(HomeUi.stateLabel(console))
		holder.binding.stateTextView.text = if(console.host.isNotEmpty()) "$stateText · ${console.host}" else stateText
		ImageViewCompat.setImageTintList(holder.binding.stateDotImageView,
			ColorStateList.valueOf(ContextCompat.getColor(context, HomeUi.stateColor(console))))
		holder.binding.root.contentDescription = "${holder.binding.nameTextView.text}, $stateText"
		holder.binding.root.setOnClickListener { clickCallback(console) }
		holder.binding.root.setOnLongClickListener {
			longClickCallback(console)
			true
		}
		bindSelection(holder, console)
	}

	private fun bindSelection(holder: ViewHolder, console: HomeConsole)
	{
		val selected = console.key == selectedKey
		holder.binding.root.isSelected = selected
		ImageViewCompat.setImageTintList(holder.binding.iconImageView,
			ColorStateList.valueOf(ContextCompat.getColor(holder.itemView.context,
				if(selected) R.color.home_accent else R.color.home_text_muted)))
	}
}
