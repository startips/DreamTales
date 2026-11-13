package com.bedtime.dreamtales

import com.bedtime.dreamtales.core.share.ShareBundleProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class Version234Test {

    // ---------------- 局域网互传配对码 ----------------

    @Test
    fun testPairCodeFormatAndValidation() {
        repeat(200) {
            val code = ShareBundleProtocol.generatePairCode(Random(it.toLong()))
            assertEquals(4, code.length)
            assertTrue("配对码必须是 4 位数字: $code", code.all { ch -> ch.isDigit() })
            assertTrue(ShareBundleProtocol.isValidPairCode(code))
        }

        assertFalse(ShareBundleProtocol.isValidPairCode(null))
        assertFalse(ShareBundleProtocol.isValidPairCode(""))
        assertFalse(ShareBundleProtocol.isValidPairCode("123"))
        assertFalse(ShareBundleProtocol.isValidPairCode("12345"))
        assertFalse(ShareBundleProtocol.isValidPairCode("12a4"))
        assertTrue(ShareBundleProtocol.isValidPairCode("0001"))
    }

    @Test
    fun testDownloadUrlCarriesPairCode() {
        assertEquals(
            "http://192.168.1.8:8080/bundle?code=4821",
            ShareBundleProtocol.downloadUrl("192.168.1.8", 8080, "4821")
        )
        // 无配对码时保持兼容（内部调试用）
        assertEquals(
            "http://192.168.1.8:8080/bundle",
            ShareBundleProtocol.downloadUrl("192.168.1.8", 8080)
        )
        // IPv6 需要加中括号
        assertEquals(
            "http://[fe80::1]:8080/bundle?code=1234",
            ShareBundleProtocol.downloadUrl("fe80::1", 8080, "1234")
        )
    }
}