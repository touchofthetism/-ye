package com.example.regression

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.test.core.app.ApplicationProvider
import com.example.domain.BlinkHysteresisDetector
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.AutoSignInGate
import com.example.ui.screens.CREDENTIAL_CLEAR_TIMEOUT_MS
import com.example.ui.screens.attemptAutoSignIn
import com.example.ui.screens.onGoogleSignInClicked
import com.example.ui.screens.signOutUser
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Regression tests for AuthScreen.kt sign-in / sign-out flows:
 * - the Firebase sign-out waits for (a bounded) credential-state clear,
 * - scope cancellation is never reported as a sign-in or sign-out failure,
 * - no auto sign-in request right after an explicit sign-out,
 * - Credential Manager configuration errors show actionable text, not framework strings.
 *
 * Credential Manager is faked by delegation; the coroutines run on a [StandardTestDispatcher] so
 * each step is explicit. Firebase delivers auth-state callbacks on the main looper.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AuthRegressionTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()
    private val dispatcher = StandardTestDispatcher()
    private val scheduler get() = dispatcher.scheduler
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        AutoSignInGate.suppressedAfterSignOut = false
        scope = CoroutineScope(SupervisorJob() + dispatcher)
    }

    @After
    fun tearDown() {
        scope.cancel()
        AutoSignInGate.suppressedAfterSignOut = false
    }

    @Test
    fun signOut_clearsCredentialStateBeforeFirebaseSignOut() {
        val auth = authRegressionFirebaseAuth()
        var authStateEvents = 0
        val listener = FirebaseAuth.AuthStateListener { authStateEvents++ }
        auth.addAuthStateListener(listener)
        try {
            idleMainLooper()
            val baseline = authStateEvents
            val clearGate = CompletableDeferred<Unit>()
            val credentialManager = fakeCredentialManager(onClear = { clearGate.await() })
            var signOutCompleted = false

            signOutUser(context, credentialManager, onSignOutComplete = { signOutCompleted = true }, scope = scope, auth = auth)
            scheduler.runCurrent()
            idleMainLooper()

            assertEquals("the credential-state clear must have started", 1, credentialManager.clearCalls)
            assertEquals(
                "Firebase sign-out must wait for the clear: it tears down the composition that owns the caller's scope",
                baseline,
                authStateEvents
            )
            assertFalse(signOutCompleted)

            clearGate.complete(Unit)
            scheduler.runCurrent()
            idleMainLooper()

            assertTrue("sign-out completes once the clear has finished", signOutCompleted)
            assertEquals("Firebase sign-out runs after the clear", baseline + 1, authStateEvents)
        } finally {
            auth.removeAuthStateListener(listener)
        }
    }

    @Test
    fun signOut_completesWhenCredentialClearNeverReturns() {
        val auth = authRegressionFirebaseAuth()
        val credentialManager = fakeCredentialManager(onClear = { awaitCancellation() })
        var signOutCompleted = false

        signOutUser(context, credentialManager, onSignOutComplete = { signOutCompleted = true }, scope = scope, auth = auth)
        scheduler.runCurrent()
        assertFalse(signOutCompleted)

        scheduler.advanceTimeBy(CREDENTIAL_CLEAR_TIMEOUT_MS + 1)
        scheduler.runCurrent()

        assertTrue("a hung credential clear must not block the sign-out", signOutCompleted)
    }

    @Test
    fun signOut_scopeCancelledMidClear_signsOutWithoutReportingAClearFailure() {
        val auth = authRegressionFirebaseAuth()
        val credentialManager = fakeCredentialManager(onClear = { awaitCancellation() })
        var signOutCompleted = false
        val previousStream = ShadowLog.stream
        val logBuffer = ByteArrayOutputStream()
        ShadowLog.stream = PrintStream(logBuffer, true)
        try {
            signOutUser(context, credentialManager, onSignOutComplete = { signOutCompleted = true }, scope = scope, auth = auth)
            scheduler.runCurrent()

            scope.cancel()
            scheduler.runCurrent()
        } finally {
            ShadowLog.stream = previousStream
        }

        assertTrue("the sign-out still runs when the scope is cancelled", signOutCompleted)
        val logs = logBuffer.toString()
        assertFalse(
            "cancellation must not be logged as a credential-clear failure: $logs",
            logs.contains("E/Auth: Failed to clear credential state")
        )
    }

    @Test
    fun autoSignIn_isNotRequestedRightAfterAnExplicitSignOut() {
        val auth = authRegressionFirebaseAuth()
        val credentialManager = fakeCredentialManager()
        var unauthenticated = 0

        // Cold start: the returning-user auto sign-in still asks Credential Manager.
        attemptAutoSignIn(
            context, credentialManager,
            onAuthSuccess = { fail("no Firebase user and no credential: must not succeed") },
            onUnauthenticated = { unauthenticated++ },
            scope = scope, auth = auth
        )
        scheduler.runCurrent()
        assertEquals(1, credentialManager.getCredentialCalls)
        assertEquals(1, unauthenticated)

        signOutUser(context, credentialManager, onSignOutComplete = {}, scope = scope, auth = auth)
        scheduler.runCurrent()

        // The AuthScreen composed right after the sign-out must not offer or auto-select the same account.
        attemptAutoSignIn(
            context, credentialManager,
            onAuthSuccess = { fail("must not sign back in after an explicit sign-out") },
            onUnauthenticated = { unauthenticated++ },
            scope = scope, auth = auth
        )
        scheduler.runCurrent()
        assertEquals("no credential request after an explicit sign-out", 1, credentialManager.getCredentialCalls)
        assertEquals("the screen is released (loading cleared) instead", 2, unauthenticated)
    }

    @Test
    fun signInFlows_cancelledScope_isNotReportedAsFailure() {
        val auth = authRegressionFirebaseAuth()
        val credentialManager = fakeCredentialManager(onGet = { awaitCancellation() })
        val errors = mutableListOf<String>()
        var autoUnauthenticated = 0

        onGoogleSignInClicked(
            context, credentialManager,
            onAuthSuccess = { fail("must not succeed") },
            onAuthError = { errors += it },
            scope = scope, auth = auth
        )
        attemptAutoSignIn(
            context, credentialManager,
            onAuthSuccess = { fail("must not succeed") },
            onUnauthenticated = { autoUnauthenticated++ },
            scope = scope, auth = auth
        )
        scheduler.runCurrent()
        assertEquals(2, credentialManager.getCredentialCalls)

        // AuthScreen leaves composition (rotation, or the other flow signed in) while both are in flight.
        scope.cancel()
        scheduler.runCurrent()

        assertEquals("scope cancellation is not a sign-in error", emptyList<String>(), errors)
        assertEquals("scope cancellation is not reported by the auto flow", 0, autoUnauthenticated)
    }

    @Test
    fun signIn_credentialManagerConfigurationErrors_showActionableText() {
        val auth = authRegressionFirebaseAuth()
        val rawNoCredential = "No credentials available"
        val rawNoProvider =
            "getCredentialAsync no provider dependencies found - please ensure the desired provider dependencies are added"
        val failures = listOf<GetCredentialException>(
            NoCredentialException(rawNoCredential),
            GetCredentialProviderConfigurationException(rawNoProvider)
        )
        val errors = mutableListOf<String>()

        for (failure in failures) {
            onGoogleSignInClicked(
                context, fakeCredentialManager(onGet = { throw failure }),
                onAuthSuccess = { fail("must not succeed") },
                onAuthError = { errors += it },
                scope = scope, auth = auth
            )
            scheduler.runCurrent()
        }

        assertEquals(2, errors.size)
        assertFalse("raw framework text must not be shown: ${errors[0]}", errors[0].contains(rawNoCredential))
        assertTrue("tell the user to add a Google account: ${errors[0]}", errors[0].contains("Google account"))
        assertFalse("raw framework text must not be shown: ${errors[1]}", errors[1].contains("getCredentialAsync"))
        assertTrue("tell the user Google Play services is needed: ${errors[1]}", errors[1].contains("Google Play services"))
    }

    private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

    private fun fakeCredentialManager(
        onGet: suspend () -> GetCredentialResponse = { throw NoCredentialException() },
        onClear: suspend () -> Unit = {}
    ): FakeCredentialManager = FakeCredentialManager(CredentialManager.create(context), onGet, onClear)

    /** Credential Manager whose get/clear calls are scripted; everything else goes to the real one. */
    private class FakeCredentialManager(
        delegate: CredentialManager,
        private val onGet: suspend () -> GetCredentialResponse,
        private val onClear: suspend () -> Unit
    ) : CredentialManager by delegate {
        var getCredentialCalls = 0
            private set
        var clearCalls = 0
            private set

        override suspend fun getCredential(context: Context, request: GetCredentialRequest): GetCredentialResponse {
            getCredentialCalls++
            return onGet()
        }

        override suspend fun clearCredentialState(request: ClearCredentialStateRequest) {
            clearCalls++
            onClear()
        }
    }
}

/**
 * The sign-in screen's feature copy. The auto sign-in is suppressed through [AutoSignInGate] so the
 * real Credential Manager is never called.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AuthScreenCopyRegressionTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun suppressAutoSignIn() {
        AutoSignInGate.suppressedAfterSignOut = true
    }

    @After
    fun resetGate() {
        AutoSignInGate.suppressedAfterSignOut = false
    }

    @Test
    fun blinkCopy_advertisesTheDetectorsRealCommandWindow() {
        val detector = BlinkHysteresisDetector()
        val auth = authRegressionFirebaseAuth()

        composeRule.setContent { AuthScreen(onAuthSuccess = {}, auth = auth) }

        // Closures longer than maxCommandMs are EXTENDED_REST and never trigger.
        composeRule
            .onNodeWithText("${detector.intentionalMinMs}–${detector.maxCommandMs}ms command blinks", substring = true)
            .assertExists()
    }
}

private const val AUTH_REGRESSION_APP_NAME = "auth-regression"

/** A FirebaseAuth with no signed-in user, on its own FirebaseApp so other tests are unaffected. */
private fun authRegressionFirebaseAuth(): FirebaseAuth {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val app = FirebaseApp.getApps(context).firstOrNull { it.name == AUTH_REGRESSION_APP_NAME }
        ?: FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setApplicationId("com.aistudio.oculagaze.rxkmpq")
                .setProjectId("demo-auth-regression")
                .setApiKey("fake-api-key-for-unit-tests")
                .build(),
            AUTH_REGRESSION_APP_NAME
        )
    return FirebaseAuth.getInstance(app)
}
