package io.github.hypercar.diagnostics

import org.junit.Assert.*
import org.junit.Test

class FrameContentTest {
    @Test fun blackAndNormalPixelsAreDistinguishedWithoutClaimingDesktopRecognition() {
        val black=FrameContent.inspect(ByteArray(100){16},ByteArray(25){128.toByte()},ByteArray(25){128.toByte()},false)
        assertTrue(black.nearBlack)
        assertFalse(FrameContent.inspect(ByteArray(100){128.toByte()},ByteArray(25){128.toByte()},ByteArray(25){128.toByte()},false).nearBlack)
        assertTrue(FrameContent.inspect(ByteArray(100),ByteArray(25){128.toByte()},ByteArray(25){128.toByte()},true).nearBlack)
        assertFalse(FrameContent.inspect(ByteArray(100){16},ByteArray(25){20},ByteArray(25){128.toByte()},false).nearBlack)
    }
}
