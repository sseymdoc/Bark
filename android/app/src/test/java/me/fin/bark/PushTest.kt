package me.fin.bark

import me.fin.bark.data.Message
import me.fin.bark.data.MessageJson
import me.fin.bark.data.TimeRange
import me.fin.bark.push.Crypto
import me.fin.bark.push.CryptoException
import me.fin.bark.push.CryptoFields
import me.fin.bark.push.Level
import me.fin.bark.push.Push
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class PushTest {
    private val plain = """{"body":"test","sound":"birdsong"}"""

    // Vectors produced independently by `openssl enc` (CBC/ECB) and Node.js crypto (GCM, tag appended).
    @Test fun decryptsOpensslCbc() {
        val f = CryptoFields("AES128", "CBC", "pkcs7", "1234567890123456")
        assertEquals(plain, Crypto.decrypt(f, "PyyK7dW6sTXP2TzjVOYOC+JApqNGkWH9Sj3+tnBs2feSO0etk2Qw1A+6SfdZ5KZ1", "1111111111111111"))
    }

    @Test fun decryptsOpensslEcb() {
        val f = CryptoFields("AES256", "ECB", "pkcs7", "12345678901234567890123456789012")
        assertEquals(plain, Crypto.decrypt(f, "EAxDvaSjW3ZusGaSc3omzoydDtBtqva9k7LRgsO8gn6VoA5XzTfZzrBst5S/Vm/Q", null))
    }

    @Test fun decryptsNodeGcm() {
        val f = CryptoFields("AES192", "GCM", "noPadding", "123456789012345678901234")
        assertEquals(plain, Crypto.decrypt(f, "TuUqmweetnW3yUwriA2yUwWyez0TyHq/PWIFEefsKTesTMohUkZeGM0T2OMAJPDcYoQ=", "abcdefabcdef"))
    }

    @Test fun base64IgnoresStrayCharacters() {
        val f = CryptoFields("AES128", "CBC", "pkcs7", "1234567890123456")
        // e.g. line breaks from `base64` without -w 0
        assertEquals(plain, Crypto.decrypt(f, "PyyK7dW6sTXP2TzjVOYOC+JApqNGkWH9Sj3+\ntnBs2feSO0etk2Qw1A+6SfdZ5KZ1\n", "1111111111111111"))
    }

    @Test(expected = CryptoException::class) fun wrongKeyLengthFails() {
        Crypto.decrypt(CryptoFields("AES256", "CBC", "pkcs7", "short"), "AAAA", "1111111111111111")
    }

    @Test fun decryptedJsonReplacesAllParameters() {
        val f = CryptoFields("AES128", "CBC", "pkcs7", "1234567890123456")
        val json = """{"Title":"t","isArchive":true,"badge":3,"volume":10.0,"ratio":1.5,"empty":null}"""
        val iv = "2222222222222222"
        val push = Push.of(mapOf("ciphertext" to Crypto.encrypt(f, json, iv), "iv" to iv, "url" to "https://lost.example", "level" to "critical"))
        val out = Push.decrypt(push, f)
        assertEquals(mapOf("title" to "t", "isarchive" to "1", "badge" to "3", "volume" to "10", "ratio" to "1.5"), out.data)
        assertNull(out.url)
        assertEquals(Level.active, out.level)
    }

    @Test fun encryptedDeleteIsSeenAfterDecryption() {
        val f = CryptoFields("AES128", "CBC", "pkcs7", "1234567890123456")
        val iv = "2222222222222222"
        val push = Push.of(mapOf("ciphertext" to Crypto.encrypt(f, """{"id":"saved-id","delete":1}""", iv), "iv" to iv))
        assertFalse("the outer parameters only carry the ciphertext", push.isDelete)
        val out = Push.decrypt(push, f)
        assertTrue(out.isDelete)
        assertEquals("saved-id", out.id)
    }

    @Test(expected = CryptoException::class) fun ciphertextWithoutKeyFails() {
        Push.decrypt(Push.of(mapOf("ciphertext" to "AAAA")), null)
    }

    @Test fun plainPushPassesThrough() {
        val push = Push.of(mapOf("Title" to "hi"))
        assertTrue(Push.decrypt(push, null) === push)
        assertEquals("hi", push.title)
    }

    @Test fun levels() {
        fun level(v: String?) = Push.of(listOfNotNull(v?.let { "level" to it }).toMap()).level
        assertEquals(Level.timeSensitive, level("timeSensitive"))
        assertEquals(Level.timeSensitive, level("timesensitive"))
        assertEquals(Level.passive, level("passive"))
        assertEquals(Level.critical, level("critical"))
        assertEquals(Level.active, level("whatever"))
        assertEquals(Level.active, level(null))
    }

    @Test fun archiveRules() {
        assertTrue(Push.of(emptyMap()).shouldArchive(true))
        assertFalse(Push.of(emptyMap()).shouldArchive(false))
        assertTrue(Push.of(mapOf("isArchive" to "1")).shouldArchive(false))
        assertFalse(Push.of(mapOf("isArchive" to "0")).shouldArchive(true))
        assertFalse(Push.of(mapOf("isArchive" to "true")).shouldArchive(true)) // only "1" archives
        assertFalse(Push.of(mapOf("ttl" to "0", "isArchive" to "1")).shouldArchive(true))
        assertTrue(Push.of(mapOf("ttl" to "60")).shouldArchive(true))
        assertEquals(1_000L + 1_500L, Push.of(mapOf("ttl" to "1.5")).expireDate(1_000L))
        assertNull(Push.of(mapOf("ttl" to "-1")).ttlMillis)
    }

    @Test fun soundAndVolume() {
        assertEquals("minuet", Push.of(mapOf("sound" to "minuet.caf")).sound)
        assertEquals(0.5f, Push.of(emptyMap()).volume)
        assertEquals(1f, Push.of(mapOf("volume" to "20")).volume)
        assertEquals(0.3f, Push.of(mapOf("volume" to "3")).volume, 0.0001f)
        assertTrue(Push.of(mapOf("automaticallyCopy" to "1")).autoCopy)
        assertNull(Push.of(mapOf("id" to "")).id) // empty counts as missing
    }

    @Test fun exportImportRoundTripMatchesIosFormat() {
        val m = Message(id = "a", title = "t", body = "**b**", bodyType = "markdown", group = "g", createDate = 1_700_000_000_000, expireDate = 1_700_000_600_000)
        val json = MessageJson.encode(listOf(m, Message(id = "b", createDate = 1_000)))
        assertTrue(json.contains("\"createDate\": 1700000000"))
        assertEquals(listOf(m, Message(id = "b", createDate = 1_000)), MessageJson.decode(json))
        // iOS export: nulls, integer seconds; entries without id/createDate are skipped
        val ios = """[{"id":"x","title":null,"createDate":5,"expireDate":null},{"title":"no id","createDate":1},{"id":"y"}]"""
        assertEquals(listOf(Message(id = "x", createDate = 5_000)), MessageJson.decode(ios))
    }

    @Test fun timeRanges() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.MARCH, 10, 15, 30, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val startOfToday = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
        assertEquals(now - 3_600_000 to now, TimeRange.LastHour.bounds(now))
        assertEquals(startOfToday to now, TimeRange.Today.bounds(now))
        assertEquals(startOfToday - 86_400_000 to now, TimeRange.TodayAndYesterday.bounds(now))
        assertEquals(0L to startOfToday, TimeRange.BeforeToday.bounds(now))
        assertEquals(0L to now, TimeRange.AllTime.bounds(now))
    }
}
