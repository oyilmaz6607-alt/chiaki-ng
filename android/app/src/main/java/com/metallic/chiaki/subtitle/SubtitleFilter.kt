// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.subtitle

import kotlin.math.max
import kotlin.math.min

/**
 * One line of text found by OCR. Coordinates are normalized (0..1) relative to the captured region.
 */
data class OcrLine(
	val text: String,
	val left: Float,
	val top: Float,
	val right: Float,
	val bottom: Float
)
{
	val height get() = bottom - top
	val centerX get() = (left + right) / 2f
}

/**
 * The subtitle picked out of a frame: the joined text and the box it occupies (normalized).
 */
data class SubtitleCandidate(
	val text: String,
	val left: Float,
	val top: Float,
	val right: Float,
	val bottom: Float
)

/**
 * Pure logic (no Android dependencies) that decides which OCR lines are the game's subtitle,
 * and whether two subtitles are "the same" despite OCR jitter.
 */
object SubtitleFilter
{
	/** Subtitles are horizontally centered; HUD text in the corners is ignored. */
	private const val CENTER_MIN = 0.15f
	private const val CENTER_MAX = 0.85f
	private const val MIN_LETTERS = 3
	private const val MIN_LETTER_RATIO = 0.6f

	/** Lines closer than this many line heights are treated as one subtitle block. */
	private const val BLOCK_GAP_FACTOR = 1.2f

	fun isPlausibleLine(line: OcrLine): Boolean
	{
		val text = line.text.trim()
		if(text.isEmpty())
			return false
		val letters = text.count { it.isLetter() }
		val nonSpace = text.count { !it.isWhitespace() }
		if(letters < MIN_LETTERS || nonSpace == 0)
			return false
		if(letters.toFloat() / nonSpace < MIN_LETTER_RATIO)
			return false
		if(line.centerX < CENTER_MIN || line.centerX > CENTER_MAX)
			return false
		return true
	}

	/**
	 * Picks the subtitle block: plausible lines grouped by vertical proximity,
	 * choosing the group lowest on screen (where subtitles normally sit).
	 */
	fun pickSubtitle(lines: List<OcrLine>): SubtitleCandidate?
	{
		val plausible = lines.filter { isPlausibleLine(it) }.sortedBy { it.top }
		if(plausible.isEmpty())
			return null

		val blocks = mutableListOf<MutableList<OcrLine>>()
		for(line in plausible)
		{
			val current = blocks.lastOrNull()
			val prev = current?.lastOrNull()
			if(prev != null)
			{
				val gap = line.top - prev.bottom
				val lineHeight = max(prev.height, line.height)
				if(gap <= lineHeight * BLOCK_GAP_FACTOR)
				{
					current.add(line)
					continue
				}
			}
			blocks.add(mutableListOf(line))
		}

		val block = blocks.maxByOrNull { b -> b.maxOf { it.bottom } } ?: return null
		val text = block.joinToString(" ") { it.text.trim() }
			.replace(Regex("\\s+"), " ")
			.trim()
		if(text.isEmpty())
			return null
		return SubtitleCandidate(
			text = text,
			left = block.minOf { it.left },
			top = block.minOf { it.top },
			right = block.maxOf { it.right },
			bottom = block.maxOf { it.bottom }
		)
	}

	/** Lowercase, letters/digits only, single spaces — used to compare OCR results. */
	fun normalize(text: String): String =
		text.lowercase()
			.map { if(it.isLetterOrDigit()) it else ' ' }
			.joinToString("")
			.replace(Regex("\\s+"), " ")
			.trim()

	/** 1.0 = identical, 0.0 = completely different (normalized Levenshtein). */
	fun similarity(a: String, b: String): Float
	{
		val x = normalize(a)
		val y = normalize(b)
		if(x == y)
			return 1f
		if(x.isEmpty() || y.isEmpty())
			return 0f
		val longest = max(x.length, y.length)
		return 1f - levenshtein(x, y).toFloat() / longest
	}

	fun isSameSubtitle(a: String?, b: String?, threshold: Float = 0.85f): Boolean
	{
		if(a == null || b == null)
			return false
		return similarity(a, b) >= threshold
	}

	private fun levenshtein(a: String, b: String): Int
	{
		var prev = IntArray(b.length + 1) { it }
		var cur = IntArray(b.length + 1)
		for(i in 1..a.length)
		{
			cur[0] = i
			for(j in 1..b.length)
			{
				val cost = if(a[i - 1] == b[j - 1]) 0 else 1
				cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
			}
			val tmp = prev
			prev = cur
			cur = tmp
		}
		return prev[b.length]
	}
}

/**
 * Tracks OCR results over time and decides when to show, update or hide the translation.
 *
 * Low-latency defaults: a subtitle is shown on its first confident detection ([stableFrames] = 1).
 * If a later frame shows different text, or the same text that has grown by at least one word
 * (typewriter reveal), it is shown again so the caller re-translates it.
 *
 * Hiding: a frame without subtitle hides immediately when the caller confirms that the subtitle
 * region has visibly changed since the subtitle was shown (`regionChanged = true`, from
 * [FrameChangeDetector]); otherwise (a possible OCR flake on an unchanged picture) it takes
 * [emptyFramesToHide] consecutive empty frames.
 */
class SubtitleTracker(
	private val stableFrames: Int = 1,
	private val emptyFramesToHide: Int = 2
)
{
	sealed class Action
	{
		/**
		 * A new subtitle is ready to be translated and shown. [grown] = the same line revealed further
		 * (typewriter), so the previous translation may stay visible until the new one is ready.
		 */
		data class Show(val candidate: SubtitleCandidate, val grown: Boolean = false): Action()
		/** The current subtitle is still on screen; only its position may have moved. */
		data class Keep(val candidate: SubtitleCandidate): Action()
		/** The subtitle disappeared. */
		object Hide: Action()
		/** Nothing to do this frame. */
		object None: Action()
	}

	private var pending: SubtitleCandidate? = null
	private var pendingCount = 0
	private var shownText: String? = null
	private var emptyCount = 0

	val isShowing get() = shownText != null

	fun onFrame(candidate: SubtitleCandidate?, regionChanged: Boolean = false): Action
	{
		if(candidate == null)
		{
			pending = null
			pendingCount = 0
			if(shownText == null)
				return Action.None
			emptyCount++
			if(regionChanged || emptyCount >= emptyFramesToHide)
			{
				shownText = null
				emptyCount = 0
				return Action.Hide
			}
			return Action.None
		}
		emptyCount = 0

		val shown = shownText
		if(shown != null && SubtitleFilter.isSameSubtitle(candidate.text, shown))
		{
			if(!isGrowth(shown, candidate.text))
				return Action.Keep(candidate)
			// Typewriter: the same line got more words -> update right away.
			shownText = candidate.text
			pending = null
			pendingCount = 0
			return Action.Show(candidate, grown = true)
		}

		if(SubtitleFilter.isSameSubtitle(candidate.text, pending?.text))
			pendingCount++
		else
		{
			pending = candidate
			pendingCount = 1
		}

		if(pendingCount >= stableFrames)
		{
			shownText = candidate.text
			pending = null
			pendingCount = 0
			return Action.Show(candidate)
		}
		return Action.None
	}

	fun reset()
	{
		pending = null
		pendingCount = 0
		shownText = null
		emptyCount = 0
	}

	companion object
	{
		/** Letters/digits only; apostrophes, spaces and punctuation jitter in OCR, so they are ignored. */
		private fun alnumCount(text: String): Int = text.count { it.isLetterOrDigit() }

		/**
		 * true when [now] is the same line revealed further (at least [GROWTH_MIN_CHARS] more letters),
		 * not just OCR jitter on a finished line.
		 */
		fun isGrowth(before: String, now: String): Boolean = alnumCount(now) >= alnumCount(before) + GROWTH_MIN_CHARS

		private const val GROWTH_MIN_CHARS = 3
	}
}
