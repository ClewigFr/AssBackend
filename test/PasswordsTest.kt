package com.ass

import com.ass.dao.checkPassword
import com.ass.dao.hashPassword
import com.ass.dao.isBcryptHash
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PasswordsTest {

    @Test
    fun bcryptHashesAreSaltedAndVerifiable() {
        val a = hashPassword("secret123")
        val b = hashPassword("secret123")
        assertTrue(isBcryptHash(a))
        assertNotEquals(a, b)
        assertTrue(checkPassword("secret123", a))
        assertFalse(checkPassword("wrong", a))
    }

    @Test
    fun legacyHmacHashesStillVerify() {
        // HMAC-SHA1 of "secret123" with the former hardcoded key
        val mac = javax.crypto.Mac.getInstance("HmacSHA1")
        mac.init(javax.crypto.spec.SecretKeySpec(io.ktor.util.hex("6819b57a326945c1968f45236589"), "HmacSHA1"))
        val legacy = io.ktor.util.hex(mac.doFinal("secret123".toByteArray()))
        assertFalse(isBcryptHash(legacy))
        assertTrue(checkPassword("secret123", legacy))
        assertFalse(checkPassword("wrong", legacy))
    }
}
