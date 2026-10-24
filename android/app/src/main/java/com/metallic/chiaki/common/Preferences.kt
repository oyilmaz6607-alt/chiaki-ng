// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import androidx.annotation.StringRes
import androidx.preference.PreferenceManager
import com.metallic.chiaki.R
import com.metallic.chiaki.lib.Codec
import com.metallic.chiaki.lib.ConnectVideoProfile
import com.metallic.chiaki.lib.VideoFPSPreset
import com.metallic.chiaki.lib.VideoResolutionPreset
import com.metallic.chiaki.subtitle.CaptionStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.min

class Preferences(context: Context)
{
	enum class Resolution(val value: String, @StringRes val title: Int, val preset: VideoResolutionPreset)
	{
		RES_360P("360p", R.string.preferences_resolution_title_360p, VideoResolutionPreset.RES_360P),
		RES_540P("540p", R.string.preferences_resolution_title_540p, VideoResolutionPreset.RES_540P),
		RES_720P("720p", R.string.preferences_resolution_title_720p, VideoResolutionPreset.RES_720P),
		RES_1080P("1080p", R.string.preferences_resolution_title_1080p, VideoResolutionPreset.RES_1080P),
	}

	enum class FPS(val value: String, @StringRes val title: Int, val preset: VideoFPSPreset)
	{
		FPS_30("30", R.string.preferences_fps_title_30, VideoFPSPreset.FPS_30),
		FPS_60("60", R.string.preferences_fps_title_60, VideoFPSPreset.FPS_60)
	}

	enum class Codec(val value: String, @StringRes val title: Int, val codec: com.metallic.chiaki.lib.Codec)
	{
		CODEC_H264("h264", R.string.preferences_codec_title_h264, com.metallic.chiaki.lib.Codec.CODEC_H264),
		CODEC_H265("h265", R.string.preferences_codec_title_h265, com.metallic.chiaki.lib.Codec.CODEC_H265)
	}

	companion object
	{
		val resolutionDefault = Resolution.RES_720P
		val resolutionAll = Resolution.values()
		val fpsDefault = FPS.FPS_60
		val fpsAll = FPS.values()
		val codecDefault = Codec.CODEC_H265
		val codecAll = Codec.values()
	}

	val sharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
	private val sharedPreferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
		when(key)
		{
			resolutionKey -> _bitrateAutoFlow.value = bitrateAuto
		}
	}.also { sharedPreferences.registerOnSharedPreferenceChangeListener(it) }

	private val resources = context.resources

	val discoveryEnabledKey get() = resources.getString(R.string.preferences_discovery_enabled_key)
	var discoveryEnabled
		get() = sharedPreferences.getBoolean(discoveryEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(discoveryEnabledKey, value).apply() }

	val onScreenControlsEnabledKey get() = resources.getString(R.string.preferences_on_screen_controls_enabled_key)
	var onScreenControlsEnabled
		get() = sharedPreferences.getBoolean(onScreenControlsEnabledKey, false)
		set(value) { sharedPreferences.edit().putBoolean(onScreenControlsEnabledKey, value).apply() }

	val touchpadOnlyEnabledKey get() = resources.getString(R.string.preferences_touchpad_only_enabled_key)
	var touchpadOnlyEnabled
		get() = sharedPreferences.getBoolean(touchpadOnlyEnabledKey, false)
		set(value) { sharedPreferences.edit().putBoolean(touchpadOnlyEnabledKey, value).apply() }

	val subtitleEnabledKey get() = resources.getString(R.string.preferences_subtitle_enabled_key)
	var subtitleEnabled
		get() = sharedPreferences.getBoolean(subtitleEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(subtitleEnabledKey, value).apply() }

	/** Eski anahtar; artık [subtitleCaptionPosition] üzerinden türetilir ("cover" ise true). */
	val subtitleCoverOriginal
		get() = subtitleCaptionPosition == CaptionStyle.POSITION_COVER

	val subtitleCapturePercent
		get() = sharedPreferences.getInt(resources.getString(R.string.preferences_subtitle_area_key), 35).coerceIn(10, 100)

	val subtitleIntervalMs
		get() = sharedPreferences.getInt(resources.getString(R.string.preferences_subtitle_speed_key), 300).coerceIn(150, 1500)

	val subtitleTextSize
		get() = sharedPreferences.getInt(resources.getString(R.string.preferences_subtitle_size_key), CaptionStyle.DEFAULT_TEXT_SIZE_SP)
			.coerceIn(CaptionStyle.MIN_TEXT_SIZE_SP, CaptionStyle.MAX_TEXT_SIZE_SP)

	// ---- Altyazı görünümü (CaptionStyle.load bunları okur)

	val subtitleCaptionFontKey get() = resources.getString(R.string.preferences_caption_font_key)
	val subtitleCaptionFont: String
		get() = sharedPreferences.getString(subtitleCaptionFontKey, null)
			?.takeIf { it in CaptionStyle.FONTS } ?: CaptionStyle.DEFAULT_FONT

	val subtitleCaptionBold
		get() = sharedPreferences.getBoolean(resources.getString(R.string.preferences_caption_bold_key), CaptionStyle.DEFAULT_BOLD)

	val subtitleCaptionTextColor: Int
		get() = parseColor(sharedPreferences.getString(resources.getString(R.string.preferences_caption_text_color_key), null), CaptionStyle.DEFAULT_TEXT_COLOR)

	val subtitleCaptionBackgroundColor: Int
		get() = parseColor(sharedPreferences.getString(resources.getString(R.string.preferences_caption_bg_color_key), null), CaptionStyle.DEFAULT_BACKGROUND_COLOR)

	val subtitleCaptionBackgroundOpacity
		get() = sharedPreferences.getInt(resources.getString(R.string.preferences_caption_bg_opacity_key), CaptionStyle.DEFAULT_BACKGROUND_OPACITY).coerceIn(0, 100)

	val subtitleCaptionCornerRadius
		get() = sharedPreferences.getInt(resources.getString(R.string.preferences_caption_corner_radius_key), CaptionStyle.DEFAULT_CORNER_RADIUS_DP)
			.coerceIn(0, CaptionStyle.MAX_CORNER_RADIUS_DP)

	val subtitleCaptionShadow: String
		get() = sharedPreferences.getString(resources.getString(R.string.preferences_caption_shadow_key), null)
			?.takeIf { it in CaptionStyle.SHADOWS } ?: CaptionStyle.DEFAULT_SHADOW

	val subtitleCaptionLineSpacing: Float
		get() = sharedPreferences.getString(resources.getString(R.string.preferences_caption_line_spacing_key), null)
			?.toFloatOrNull()?.coerceIn(0.9f, 1.6f) ?: CaptionStyle.DEFAULT_LINE_SPACING

	/**
	 * "cover" | "bottom" | "top". Yeni anahtar hiç yazılmamışsa eski subtitle_cover_original
	 * anahtarından türetilir (true → cover, false → bottom).
	 */
	val subtitleCaptionPositionKey get() = resources.getString(R.string.preferences_caption_position_key)
	val subtitleCaptionPosition: String
		get() = sharedPreferences.getString(subtitleCaptionPositionKey, null)
			?.takeIf { it in CaptionStyle.POSITIONS }
			?: if(sharedPreferences.getBoolean(resources.getString(R.string.preferences_subtitle_cover_key), true))
				CaptionStyle.POSITION_COVER
			else
				CaptionStyle.POSITION_BOTTOM

	val subtitleCaptionMaxWidthPercent
		get() = sharedPreferences.getInt(resources.getString(R.string.preferences_caption_max_width_key), CaptionStyle.DEFAULT_MAX_WIDTH_PERCENT)
			.coerceIn(CaptionStyle.MIN_MAX_WIDTH_PERCENT, 100)

	/** Bu anahtarlardan biri değişince altyazı görünümü yeniden çizilmeli. */
	val subtitleCaptionStyleKeys: Set<String> get() = setOf(
		R.string.preferences_subtitle_size_key,
		R.string.preferences_caption_font_key,
		R.string.preferences_caption_bold_key,
		R.string.preferences_caption_text_color_key,
		R.string.preferences_caption_bg_color_key,
		R.string.preferences_caption_bg_opacity_key,
		R.string.preferences_caption_corner_radius_key,
		R.string.preferences_caption_shadow_key,
		R.string.preferences_caption_line_spacing_key,
		R.string.preferences_caption_position_key,
		R.string.preferences_caption_max_width_key
	).map { resources.getString(it) }.toSet()

	private fun parseColor(value: String?, default: Int): Int =
		try
		{
			if(value.isNullOrBlank()) default else Color.parseColor(value)
		}
		catch(e: IllegalArgumentException)
		{
			default
		}

	val rumbleEnabledKey get() = resources.getString(R.string.preferences_rumble_enabled_key)
	var rumbleEnabled
		get() = sharedPreferences.getBoolean(rumbleEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(rumbleEnabledKey, value).apply() }

	val motionEnabledKey get() = resources.getString(R.string.preferences_motion_enabled_key)
	var motionEnabled
		get() = sharedPreferences.getBoolean(motionEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(motionEnabledKey, value).apply() }

	val buttonHapticEnabledKey get() = resources.getString(R.string.preferences_button_haptic_enabled_key)
	var buttonHapticEnabled
		get() = sharedPreferences.getBoolean(buttonHapticEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(buttonHapticEnabledKey, value).apply() }

	val logVerboseKey get() = resources.getString(R.string.preferences_log_verbose_key)
	var logVerbose
		get() = sharedPreferences.getBoolean(logVerboseKey, false)
		set(value) { sharedPreferences.edit().putBoolean(logVerboseKey, value).apply() }

	val swapCrossMoonKey get() = resources.getString(R.string.preferences_swap_cross_moon_key)
	var swapCrossMoon
		get() = sharedPreferences.getBoolean(swapCrossMoonKey, false)
		set(value) { sharedPreferences.edit().putBoolean(swapCrossMoonKey, value).apply() }

	val resolutionKey get() = resources.getString(R.string.preferences_resolution_key)
	var resolution
		get() = sharedPreferences.getString(resolutionKey, resolutionDefault.value)?.let { value ->
			Resolution.values().firstOrNull { it.value == value }
		} ?: resolutionDefault
		set(value) { sharedPreferences.edit().putString(resolutionKey, value.value).apply() }

	val fpsKey get() = resources.getString(R.string.preferences_fps_key)
	var fps
		get() = sharedPreferences.getString(fpsKey, fpsDefault.value)?.let { value ->
			FPS.values().firstOrNull { it.value == value }
		}  ?: fpsDefault
		set(value) { sharedPreferences.edit().putString(fpsKey, value.value).apply() }

	fun validateBitrate(bitrate: Int) = max(2000, min(50000, bitrate))
	val bitrateKey get() = resources.getString(R.string.preferences_bitrate_key)
	var bitrate
		get() = sharedPreferences.getInt(bitrateKey, 0).let { if(it == 0) null else validateBitrate(it) }
		set(value) { sharedPreferences.edit().putInt(bitrateKey, if(value != null) validateBitrate(value) else 0).apply() }
	val bitrateAuto get() = videoProfileDefaultBitrate.bitrate
	private val _bitrateAutoFlow by lazy { MutableStateFlow(bitrateAuto) }
	val bitrateAutoFlow: StateFlow<Int> get() = _bitrateAutoFlow.asStateFlow()

	val codecKey get() = resources.getString(R.string.preferences_codec_key)
	var codec
		get() = sharedPreferences.getString(codecKey, codecDefault.value)?.let { value ->
			Codec.values().firstOrNull { it.value == value }
		}  ?: codecDefault
		set(value) { sharedPreferences.edit().putString(codecKey, value.value).apply() }

	private val videoProfileDefaultBitrate get() = ConnectVideoProfile.preset(resolution.preset, fps.preset, codec.codec)
	val videoProfile get() = videoProfileDefaultBitrate.let {
		val bitrate = bitrate
		if(bitrate == null)
			it
		else
			it.copy(bitrate = bitrate)
	}
}