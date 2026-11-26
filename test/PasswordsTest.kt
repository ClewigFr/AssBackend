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
    fun legacyHmacHashesAreRefused() {
        // HMAC-SHA1 of "secret123" with the former hardcoded key
        val legacy = "0f3a1c6f9a0c2b4d5e6f708192a3b4c5d6e7f809"
        assertFalse(isBcryptHash(legacy))
        assertFalse(checkPassword("secret123", legacy))
    }
}
