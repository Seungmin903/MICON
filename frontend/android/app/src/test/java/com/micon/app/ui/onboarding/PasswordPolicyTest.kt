package com.micon.app.ui.onboarding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordPolicyTest {

    @Test
    fun acceptsPasswordWithEnglishNumberAndAllowedSpecialCharacter() {
        assertTrue(isValidPassword("Password123!"))
        assertTrue(isValidPassword("abc12345@"))
    }

    @Test
    fun rejectsMissingRequiredCharacterGroupsAndInvalidCharacters() {
        assertFalse(isValidPassword("12345678!"))
        assertFalse(isValidPassword("Password!"))
        assertFalse(isValidPassword("Password123"))
        assertFalse(isValidPassword("Password123_"))
    }

    @Test
    fun rejectsWhitespaceAndInvalidLength() {
        assertFalse(isValidPassword("Password 123!"))
        assertFalse(isValidPassword("Pass1!"))
        assertFalse(isValidPassword("Password123!" + "a".repeat(53)))
    }
}
