package com.metallic.chiaki.subtitle

import org.junit.Assert.*
import org.junit.Test

class LumaCropTest
{
	@Test
	fun targetSizeIsEvenAndBounded()
	{
		assertEquals(960 to 188, LumaCrop.targetSize(1280, 252, 960))
		assertEquals(640 to 120, LumaCrop.targetSize(640, 121, 960))
	}

	@Test
	fun copiesWithStrideAndFillsChroma()
	{
		val stride = 8
		val src = ByteArray(2 + stride * 2) { it.toByte() }
		val out = ByteArray(LumaCrop.nv21Size(4, 2))
		LumaCrop.toGrayNv21(src, 2, stride, 4, 2, 4, 2, out)
		assertTrue(byteArrayOf(2, 3, 4, 5, 10, 11, 12, 13).contentEquals(out.copyOfRange(0, 8)))
		assertTrue(out.copyOfRange(8, 12).all { it == 128.toByte() })
	}

	@Test
	fun downscaleAveragesBoxes()
	{
		// 4x2 -> 2x1: each output pixel averages a 2x2 box
		val src = byteArrayOf(0, 100, 200.toByte(), 250.toByte(), 100, 200.toByte(), 250.toByte(), 250.toByte())
		val out = ByteArray(LumaCrop.nv21Size(2, 1))
		LumaCrop.toGrayNv21(src, 0, 4, 4, 2, 2, 1, out)
		assertEquals(100, out[0].toInt() and 0xFF)
		assertEquals(237, out[1].toInt() and 0xFF)
	}
}
