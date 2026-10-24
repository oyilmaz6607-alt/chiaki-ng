// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.subtitle

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.metallic.chiaki.R
import com.metallic.chiaki.common.Preferences
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Türkçe altyazının görünümü (yazı tipi, boyut, renkler, arka plan, gölge, konum).
 *
 * Hem HDMI üstü katman (CaptionService) hem uygulama içi akış (StreamActivity) hem de
 * ayarlardaki canlı önizleme aynı [applyTo] fonksiyonunu kullanır, böylece üçü birebir aynı görünür.
 *
 * Kontur notu: servis düz TextView oluşturduğu için gerçek bir "stroke" çizen özel TextView
 * kullanılamıyor. "outline" seçeneği bunun yerine kaydırmasız (dx = dy = 0), tam opak ve
 * yazı boyutuyla ölçeklenen güçlü bir gölge kullanır; harflerin çevresinde kontur benzeri
 * koyu bir hale oluşturur ve şeffaf arka planda bile okunur kalır.
 */
data class CaptionStyle(
	/** [FONT_INTER], [FONT_ATKINSON], [FONT_LEXEND], [FONT_NOTO_SANS] veya [FONT_SYSTEM] */
	val font: String = DEFAULT_FONT,
	val bold: Boolean = DEFAULT_BOLD,
	val textSizeSp: Int = DEFAULT_TEXT_SIZE_SP,
	val textColor: Int = DEFAULT_TEXT_COLOR,
	/** Arka plan rengi (alfa yok sayılır, opaklık [backgroundOpacityPercent] ile belirlenir). */
	val backgroundColor: Int = DEFAULT_BACKGROUND_COLOR,
	val backgroundOpacityPercent: Int = DEFAULT_BACKGROUND_OPACITY,
	val cornerRadiusDp: Int = DEFAULT_CORNER_RADIUS_DP,
	/** [SHADOW_NONE], [SHADOW_SOFT] veya [SHADOW_OUTLINE] */
	val shadow: String = DEFAULT_SHADOW,
	val lineSpacingMultiplier: Float = DEFAULT_LINE_SPACING,
	/** [POSITION_COVER] | [POSITION_BOTTOM] | [POSITION_TOP] */
	val position: String = DEFAULT_POSITION,
	/** Sabit bantlarda (alt/üst) altyazının kaplayabileceği en büyük ekran genişliği oranı, ör. 0.8f */
	val maxWidthFraction: Float = DEFAULT_MAX_WIDTH_PERCENT / 100f
)
{
	companion object
	{
		private const val TAG = "CaptionStyle"

		const val FONT_SYSTEM = "system"
		const val FONT_INTER = "inter"
		const val FONT_ATKINSON = "atkinson"
		const val FONT_LEXEND = "lexend"
		const val FONT_NOTO_SANS = "noto_sans"
		val FONTS = listOf(FONT_INTER, FONT_ATKINSON, FONT_LEXEND, FONT_NOTO_SANS, FONT_SYSTEM)

		const val SHADOW_NONE = "none"
		const val SHADOW_SOFT = "soft"
		const val SHADOW_OUTLINE = "outline"
		val SHADOWS = listOf(SHADOW_NONE, SHADOW_SOFT, SHADOW_OUTLINE)

		const val POSITION_COVER = "cover"
		const val POSITION_BOTTOM = "bottom"
		const val POSITION_TOP = "top"
		val POSITIONS = listOf(POSITION_COVER, POSITION_BOTTOM, POSITION_TOP)

		// Varsayılanlar: res/xml/preferences_caption_appearance.xml içindeki defaultValue'larla aynı olmalı.
		const val DEFAULT_FONT = FONT_INTER
		const val DEFAULT_BOLD = true
		const val DEFAULT_TEXT_SIZE_SP = 30
		const val MIN_TEXT_SIZE_SP = 14
		const val MAX_TEXT_SIZE_SP = 56
		const val DEFAULT_TEXT_COLOR_HEX = "#FFFFFF"
		val DEFAULT_TEXT_COLOR = Color.WHITE
		const val DEFAULT_BACKGROUND_COLOR_HEX = "#000000"
		val DEFAULT_BACKGROUND_COLOR = Color.BLACK
		const val DEFAULT_BACKGROUND_OPACITY = 75
		const val DEFAULT_CORNER_RADIUS_DP = 8
		const val MAX_CORNER_RADIUS_DP = 24
		const val DEFAULT_SHADOW = SHADOW_SOFT
		const val DEFAULT_LINE_SPACING_VALUE = "1.1"
		const val DEFAULT_LINE_SPACING = 1.1f
		const val DEFAULT_POSITION = POSITION_COVER
		const val DEFAULT_MAX_WIDTH_PERCENT = 80
		const val MIN_MAX_WIDTH_PERCENT = 50

		fun load(context: Context): CaptionStyle
		{
			val prefs = Preferences(context)
			return CaptionStyle(
				font = prefs.subtitleCaptionFont,
				bold = prefs.subtitleCaptionBold,
				textSizeSp = prefs.subtitleTextSize,
				textColor = prefs.subtitleCaptionTextColor,
				backgroundColor = prefs.subtitleCaptionBackgroundColor,
				backgroundOpacityPercent = prefs.subtitleCaptionBackgroundOpacity,
				cornerRadiusDp = prefs.subtitleCaptionCornerRadius,
				shadow = prefs.subtitleCaptionShadow,
				lineSpacingMultiplier = prefs.subtitleCaptionLineSpacing,
				position = prefs.subtitleCaptionPosition,
				maxWidthFraction = prefs.subtitleCaptionMaxWidthPercent / 100f
			)
		}

		private fun fontRes(font: String, bold: Boolean): Int? = when(font)
		{
			FONT_INTER -> if(bold) R.font.inter_semibold else R.font.inter_regular
			FONT_ATKINSON -> if(bold) R.font.atkinson_next_semibold else R.font.atkinson_next_regular
			FONT_LEXEND -> if(bold) R.font.lexend_semibold else R.font.lexend_regular
			FONT_NOTO_SANS -> if(bold) R.font.noto_sans_semibold else R.font.noto_sans_regular
			else -> null
		}

		private fun systemTypeface(bold: Boolean): Typeface =
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
				Typeface.create(Typeface.SANS_SERIF, if(bold) 600 else 400, false)
			else if(bold)
				Typeface.DEFAULT_BOLD
			else
				Typeface.DEFAULT
	}

	val isBundledFont get() = fontRes(font, bold) != null

	/** Yazı tipini döndürür; paketlenmiş yazı tipi yüklenemezse sistem yazı tipine düşer. */
	fun typeface(context: Context): Typeface
	{
		val res = fontRes(font, bold) ?: return systemTypeface(bold)
		return try
		{
			ResourcesCompat.getFont(context, res) ?: systemTypeface(bold)
		}
		catch(e: Exception)
		{
			Log.w(TAG, "Font $font could not be loaded, using system font", e)
			systemTypeface(bold)
		}
	}

	/** Arka plan rengi, opaklık uygulanmış hâliyle. */
	val backgroundArgb: Int get() = Color.argb(
		(backgroundOpacityPercent.coerceIn(0, 100) * 255f / 100f).roundToInt(),
		Color.red(backgroundColor), Color.green(backgroundColor), Color.blue(backgroundColor))

	/**
	 * Yazı tipi, boyut (sp), yazı rengi, arka plan (renk + opaklık + köşe yuvarlaklığı),
	 * gölge/kontur, iç boşluk, satır aralığı ve ortalı hizalamayı uygular.
	 * maxWidth ve ekrandaki konum çağıran tarafa bırakılır ([maxWidthFraction], [position]).
	 */
	fun applyTo(view: TextView)
	{
		val context = view.context
		val metrics = view.resources.displayMetrics
		val size = textSizeSp.coerceIn(MIN_TEXT_SIZE_SP, MAX_TEXT_SIZE_SP).toFloat()
		val textPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, metrics)

		view.typeface = typeface(context)
		view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
		view.setTextColor(Color.rgb(Color.red(textColor), Color.green(textColor), Color.blue(textColor)))
		view.gravity = Gravity.CENTER
		view.setLineSpacing(0f, lineSpacingMultiplier.coerceIn(0.9f, 1.6f))

		// İç boşluk yazı boyutuyla ölçeklenir (30sp'de yaklaşık 16dp x 6dp).
		val padH = (textPx * 0.55f).roundToInt()
		val padV = (textPx * 0.22f).roundToInt()
		view.setPadding(padH, padV, padH, padV)

		view.background = GradientDrawable().apply {
			shape = GradientDrawable.RECTANGLE
			setColor(backgroundArgb)
			cornerRadius = cornerRadiusDp.coerceIn(0, MAX_CORNER_RADIUS_DP) * metrics.density
		}

		when(shadow)
		{
			SHADOW_NONE -> view.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
			SHADOW_OUTLINE -> view.setShadowLayer(max(2.5f, textPx * 0.075f), 0f, 0f, Color.BLACK)
			else ->
			{
				val offset = max(1f, textPx * 0.035f)
				view.setShadowLayer(max(2f, textPx * 0.08f), offset, offset, Color.argb(220, 0, 0, 0))
			}
		}
	}
}
