package com.aichat.keyboard.share

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenshotOcrLimitsTest {
    @Test
    fun `normal and long screenshots are decodable`() {
        assertTrue(ScreenshotOcr.isDecodable(1080, 2400))
        assertTrue(ScreenshotOcr.isDecodable(1440, 30_000))
        assertTrue(ScreenshotOcr.isDecodable(10_000, 10_000))
    }

    @Test
    fun `images above 100 megapixels are refused before decoding`() {
        assertFalse(ScreenshotOcr.isDecodable(10_001, 10_000))
        assertFalse(ScreenshotOcr.isDecodable(50_000, 50_000))
        assertFalse(ScreenshotOcr.isDecodable(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun `empty or invalid bounds are refused`() {
        assertFalse(ScreenshotOcr.isDecodable(0, 2400))
        assertFalse(ScreenshotOcr.isDecodable(1080, -1))
    }
}
