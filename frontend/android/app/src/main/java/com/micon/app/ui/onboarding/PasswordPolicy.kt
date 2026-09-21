package com.micon.app.ui.onboarding

internal const val PASSWORD_GUIDE = "영문, 숫자, 특수문자(@$!%*#?&) 포함 8자 이상 · 공백 불가"

private val PASSWORD_PATTERN = Regex(
    pattern = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[@$!%*#?&])[A-Za-z\\d@$!%*#?&]{8,64}$",
)

internal fun isValidPassword(password: String): Boolean =
    PASSWORD_PATTERN.matches(password) && password.toByteArray(Charsets.UTF_8).size <= 72
