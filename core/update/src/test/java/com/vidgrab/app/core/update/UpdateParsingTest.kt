package com.vidgrab.app.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateParsingTest {
    @Test
    fun cleansPlainNotes() {
        val body = "## What's improved\n\n- Add video tags\n- Save thumbnails\n\n## New Contributors\n* @dev made their first contribution in https://x"
        assertEquals("• Add video tags\n• Save thumbnails", UpdateParsing.cleanNotes(body))
    }

    @Test
    fun versionCodes() {
        assertEquals(1_000_012L, UpdateParsing.versionCodeOf("v1.0.12"))
        assertEquals(1_001_040L, UpdateParsing.versionCodeOf("1.1.40"))
        assertEquals(1_001_000L, UpdateParsing.versionCodeOf("v1.1.0"))
        assertNull(UpdateParsing.versionCodeOf("nightly"))
        assertNull(UpdateParsing.versionCodeOf("v1.0.12-beta"))
    }

    @Test
    fun parsesRelease() {
        val json = """{"tag_name":"v1.0.7","html_url":"https://github.com/o/r/releases/tag/v1.0.7","draft":false,"prerelease":false,
            "body":"## What's Changed\n* Add update checker by @dev in https://github.com/o/r/pull/3\n\n**Full Changelog**: https://x",
            "assets":[{"name":"VidGrab-v1.0.7.apk","size":123,"browser_download_url":"https://g/a.apk"},
                      {"name":"VidGrab-v1.0.7.apk.sha256","size":90,"browser_download_url":"https://g/a.apk.sha256"}]}"""
        val u = UpdateParsing.parseRelease(json)!!
        assertEquals(1_000_007L, u.versionCode)
        assertEquals("1.0.7", u.versionName)
        assertEquals("https://g/a.apk", u.apkUrl)
        assertEquals("https://g/a.apk.sha256", u.sha256Url)
        assertEquals("• Add update checker", u.notes)
    }

    @Test
    fun picksTheApkForThisPhonesCpu() {
        fun asset(name: String) = """{"name":"$name","size":1,"browser_download_url":"https://g/$name"}"""
        val json = """{"tag_name":"v1.0.9","assets":[
            ${asset("VidGrab-v1.0.9-arm64-v8a.apk")}, ${asset("VidGrab-v1.0.9-arm64-v8a.apk.sha256")},
            ${asset("VidGrab-v1.0.9-armeabi-v7a.apk")}, ${asset("VidGrab-v1.0.9-armeabi-v7a.apk.sha256")},
            ${asset("VidGrab-v1.0.9.apk")}, ${asset("VidGrab-v1.0.9.apk.sha256")}]}"""
        val arm64 = UpdateParsing.parseRelease(json, listOf("arm64-v8a", "armeabi-v7a", "armeabi"))!!
        assertEquals("https://g/VidGrab-v1.0.9-arm64-v8a.apk", arm64.apkUrl)
        assertEquals("https://g/VidGrab-v1.0.9-arm64-v8a.apk.sha256", arm64.sha256Url)
        val arm32 = UpdateParsing.parseRelease(json, listOf("armeabi-v7a", "armeabi"))!!
        assertEquals("https://g/VidGrab-v1.0.9-armeabi-v7a.apk", arm32.apkUrl)
        // Unknown CPU: the universal APK.
        val other = UpdateParsing.parseRelease(json, listOf("x86_64"))!!
        assertEquals("https://g/VidGrab-v1.0.9.apk", other.apkUrl)
        assertEquals("https://g/VidGrab-v1.0.9.apk.sha256", other.sha256Url)
    }

    @Test
    fun ignoresPrereleaseAndMissingApk() {
        assertNull(UpdateParsing.parseRelease("""{"tag_name":"v1.0.8","prerelease":true,"assets":[]}"""))
        assertNull(UpdateParsing.parseRelease("""{"tag_name":"v1.0.8","assets":[{"name":"notes.txt"}]}"""))
    }

    @Test
    fun sha256() {
        val h = "a".repeat(64)
        assertEquals(h, UpdateParsing.parseSha256("$h  VidGrab-v1.0.7.apk\n"))
    }
}
