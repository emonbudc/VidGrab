package com.vidgrab.app.core.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultPinTest {
    @Test
    fun hashesAndVerifies() {
        val stored = VaultPin.hash("1234")
        assertTrue(VaultPin.isHashed(stored))
        assertTrue(VaultPin.verify("1234", stored))
        assertFalse(VaultPin.verify("4321", stored))
    }

    @Test
    fun saltsEveryHash() {
        assertNotEquals(VaultPin.hash("1234"), VaultPin.hash("1234"))
    }

    @Test
    fun acceptsOldPlaintextPins() {
        assertTrue(VaultPin.verify("1234", "1234"))
        assertFalse(VaultPin.verify("4321", "1234"))
    }

    @Test
    fun rejectsGarbage() {
        assertFalse(VaultPin.verify("1234", null))
        assertFalse(VaultPin.verify("1234", "loading"))
        assertFalse(VaultPin.verify("1234", "pbkdf2\$nope\$00\$00"))
        assertFalse(VaultPin.verify("1234", "pbkdf2\$120000\$zz\$00"))
    }
}
