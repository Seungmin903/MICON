package com.micon.app

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CiConfigurationTest {
    @Test
    fun apiBaseUrlIsAbsoluteAndHasTrailingSlash() {
        val uri = URI(BuildConfig.API_BASE_URL)
        assertTrue(uri.scheme == "http" || uri.scheme == "https")
        assertTrue(!uri.host.isNullOrBlank())
        assertTrue(BuildConfig.API_BASE_URL.endsWith("/"))
    }

    @Test
    fun ciBuildUsesExplicitEnvironmentApiBaseUrl() {
        if (System.getenv("MICON_CI") != "true") return
        val expected = System.getenv("API_BASE_URL")
        assertTrue(!expected.isNullOrBlank())
        assertEquals(expected, BuildConfig.API_BASE_URL)
        assertEquals("https", URI(BuildConfig.API_BASE_URL).scheme)
    }
}
