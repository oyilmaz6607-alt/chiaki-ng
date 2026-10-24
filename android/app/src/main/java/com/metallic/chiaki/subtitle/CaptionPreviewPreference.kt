// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.subtitle

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.metallic.chiaki.R
import kotlin.math.roundToInt

/**
 * Altyazı görünümünün canlı önizlemesi: koyu, oyun sahnesini andıran bir arka planın üstüne
 * örnek Türkçe cümleyi [CaptionStyle.applyTo] ile çizer.
 * Ayar değiştiğinde [Preference.notifyChanged] (ya da [refresh]) çağrılarak yeniden çizilir.
 */
class CaptionPreviewPreference @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0
): Preference(context, attrs, defStyleAttr)
{
	init
	{
		layoutResource = R.layout.preference_caption_preview
		isSelectable = false
		isPersistent = false
	}

	override fun onBindViewHolder(holder: PreferenceViewHolder)
	{
		super.onBindViewHolder(holder)
		holder.isDividerAllowedAbove = false
		holder.isDividerAllowedBelow = false
		holder.itemView.isFocusable = false
		CaptionPreview.bind(holder.itemView)
	}

	fun refresh() = notifyChanged()
}

/** preference_caption_preview.xml düzenini güncel [CaptionStyle] ile çizer (tercih satırı ve sabit başlık için ortak). */
object CaptionPreview
{
	fun bind(root: View, style: CaptionStyle = CaptionStyle.load(root.context))
	{
		val text = root.findViewById<TextView>(R.id.captionPreviewText) ?: return
		val label = root.findViewById<TextView>(R.id.captionPreviewLabel)
		style.applyTo(text)

		// Gerçek katmandaki gibi: sabit bantlarda ekran genişliğinin belirli bir oranı.
		val screenWidth = root.resources.displayMetrics.widthPixels
		text.maxWidth = (screenWidth * style.maxWidthFraction.coerceIn(0.3f, 1f)).roundToInt()

		val top = style.position == CaptionStyle.POSITION_TOP
		(text.layoutParams as? FrameLayout.LayoutParams)?.let {
			it.gravity = (if(top) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
			text.layoutParams = it
		}
		(label?.layoutParams as? FrameLayout.LayoutParams)?.let {
			it.gravity = (if(top) Gravity.BOTTOM else Gravity.TOP) or Gravity.START
			label.layoutParams = it
		}
	}
}
