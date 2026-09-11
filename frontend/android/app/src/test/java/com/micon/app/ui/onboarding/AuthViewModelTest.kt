package com.micon.app.ui.onboarding

import com.micon.app.core.auth.MemoryTokenStore
import com.micon.app.data.backend.AuthRepository
import com.micon.app.data.backend.AuthRequestException
import com.micon.app.data.backend.AuthSessionExpiredException
import com.micon.app.data.preferences.PreferenceArtistPage
import com.micon.app.data.preferences.PreferenceGenre
import com.micon.app.data.preferences.PreferenceRepository
import com.micon.app.data.preferences.PreferenceSessionExpiredException
import com.micon.app.data.preferences.SavePreferencesRequest
import com.micon.app.data.preferences.UserPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `new account with incomplete server preferences always enters survey`() = runTest {
        val tokens = signedIn()
        val auth = FakeAuth()
        val preferences = FakePreferences()
        val model = SessionGateViewModel(auth, preferences, tokens)
        advanceUntilIdle()
        assertEquals(SessionDestination.SURVEY, model.state.value.destination)
        assertEquals(1, auth.restoreCalls)
        assertEquals(1, preferences.loadCalls)
    }

    @Test fun `completed server preferences skip survey after login or relaunch`() = runTest {
        val model = SessionGateViewModel(FakeAuth(), FakePreferences(UserPreferences(onboardingCompleted = true)), signedIn())
        advanceUntilIdle()
        assertEquals(SessionDestination.MAIN, model.state.value.destination)
    }

    @Test fun `logged out launch never calls protected APIs`() = runTest {
        val auth = FakeAuth()
        val preferences = FakePreferences()
        val model = SessionGateViewModel(auth, preferences, MemoryTokenStore())
        advanceUntilIdle()
        assertEquals(SessionDestination.LOGIN, model.state.value.destination)
        assertEquals(0, auth.restoreCalls)
        assertEquals(0, preferences.loadCalls)
    }

    @Test fun `preference outage stays on retry gate and never silently skips onboarding`() = runTest {
        val preferences = FakePreferences().apply { failure = IOException("offline") }
        val model = SessionGateViewModel(FakeAuth(), preferences, signedIn())
        advanceUntilIdle()
        assertNull(model.state.value.destination)
        assertNotNull(model.state.value.error)
        preferences.failure = null
        model.retry()
        advanceUntilIdle()
        assertEquals(SessionDestination.SURVEY, model.state.value.destination)
        assertEquals(2, preferences.loadCalls)
    }

    @Test fun `unauthorized preference response clears account and routes login`() = runTest {
        val tokens = signedIn()
        val preferences = FakePreferences().apply { failure = PreferenceSessionExpiredException() }
        val model = SessionGateViewModel(FakeAuth(), preferences, tokens)
        advanceUntilIdle()
        assertEquals(SessionDestination.LOGIN, model.state.value.destination)
        assertFalse(tokens.snapshot().isLoggedIn)
    }

    @Test fun `identity failure blocks all preference requests`() = runTest {
        val auth = FakeAuth().apply { restoreFailure = AuthSessionExpiredException() }
        val preferences = FakePreferences()
        val model = SessionGateViewModel(auth, preferences, signedIn())
        advanceUntilIdle()
        assertEquals(SessionDestination.LOGIN, model.state.value.destination)
        assertEquals(0, preferences.loadCalls)
    }

    @Test fun `login double tap submits only once while request is running`() = runTest {
        val pending = CompletableDeferred<Unit>()
        val auth = FakeAuth().apply { loginWait = pending }
        val model = AuthViewModel(auth)
        model.submit("user@example.com", "password", false)
        model.submit("user@example.com", "password", false)
        runCurrent()
        assertTrue(model.state.value.submitting)
        assertEquals(1, auth.loginCalls)
        pending.complete(Unit)
        advanceUntilIdle()
        assertTrue(model.state.value.authenticated)
    }

    @Test fun `credential rejection shows message and allows retry without success event`() = runTest {
        val auth = FakeAuth().apply { loginFailure = AuthRequestException(401, "이메일 또는 비밀번호가 올바르지 않아요.") }
        val model = AuthViewModel(auth)
        model.submit("user@example.com", "wrong", false)
        advanceUntilIdle()
        assertFalse(model.state.value.authenticated)
        assertFalse(model.state.value.submitting)
        assertNotNull(model.state.value.error)
        auth.loginFailure = null
        model.submit("user@example.com", "right", false)
        advanceUntilIdle()
        assertTrue(model.state.value.authenticated)
        assertEquals(2, auth.loginCalls)
    }

    @Test fun `signup completion does not authenticate before explicit login`() = runTest {
        val auth = FakeAuth()
        val model = AuthViewModel(auth)

        model.submit("user@example.com", "password", true)
        advanceUntilIdle()

        assertTrue(model.state.value.signupCompleted)
        assertFalse(model.state.value.authenticated)
        assertEquals(1, auth.signupCalls)
        assertEquals(0, auth.loginCalls)
    }

    private fun signedIn() = MemoryTokenStore().apply { saveSession(42, "access", "refresh") }

    private class FakeAuth : AuthRepository {
        var restoreCalls = 0
        var loginCalls = 0
        var signupCalls = 0
        var restoreFailure: Exception? = null
        var loginFailure: Exception? = null
        var loginWait: CompletableDeferred<Unit>? = null
        override suspend fun login(email: String, password: String) {
            loginCalls++
            loginWait?.await()
            loginFailure?.let { throw it }
        }
        override suspend fun signup(email: String, password: String) { signupCalls++ }
        override suspend fun restoreIdentity() { restoreCalls++; restoreFailure?.let { throw it } }
        override suspend fun logout() = Unit
    }

    private class FakePreferences(var saved: UserPreferences = UserPreferences()) : PreferenceRepository {
        var loadCalls = 0
        var failure: Exception? = null
        override suspend fun getPreferences(): UserPreferences { loadCalls++; failure?.let { throw it }; return saved }
        override suspend fun getGenres(): List<PreferenceGenre> = emptyList()
        override suspend fun getArtists(query: String, page: Int) = PreferenceArtistPage()
        override suspend fun savePreferences(request: SavePreferencesRequest) = saved
    }
}
