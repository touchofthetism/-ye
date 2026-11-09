package com.example.optimization

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.AccessibilityAction
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.NoCredentialException
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.AutoSignInGate
import com.example.ui.screens.attemptAutoSignIn
import com.example.ui.screens.onGoogleSignInClicked
import com.example.ui.screens.signOutUser
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

/**
 * Equivalence tests for the AuthScreen.kt optimization pass:
 * - the shared Google-ID-token exchange and the dropped `context as? Activity ?: context` no-op
 *   leave both sign-in flows' callbacks, logs, Credential Manager requests and gate state
 *   identical to the frozen pre-optimization code, for every scripted Credential Manager outcome
 *   and for seeded random sequences of sign-in / sign-out attempts;
 * - the screen split (skippable intro, hoisted shapes, one shared icon-tile brush) renders the
 *   same semantics tree (text, descriptions, roles, actions, bounds) as the frozen screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AuthOptimizationTest {

    private val appContext: Context
        get() = ApplicationProvider.getApplicationContext()
    private val dispatcher = StandardTestDispatcher()
    private val scheduler get() = dispatcher.scheduler
    private lateinit var auth: FirebaseAuth
    private lateinit var contexts: List<Context>

    @Before
    fun setUp() {
        AutoSignInGate.suppressedAfterSignOut = false
        auth = authOptimizationFirebaseAuth()
        val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        contexts = listOf(appContext, activity)
    }

    @After
    fun tearDown() {
        AutoSignInGate.suppressedAfterSignOut = false
        ShadowLog.reset()
    }

    @Test
    fun `both sign-in flows match the frozen code for every credential outcome, context and gate state`() {
        var runs = 0
        for (outcomeIndex in OUTCOMES.indices) {
            for (contextIndex in contexts.indices) {
                for (gateBefore in listOf(false, true)) {
                    for (step in listOf(Step.Auto(outcomeIndex, contextIndex), Step.Click(outcomeIndex, contextIndex))) {
                        val frozen = runSteps(useFrozen = true, steps = listOf(step), gateBefore = gateBefore)
                        val optimized = runSteps(useFrozen = false, steps = listOf(step), gateBefore = gateBefore)
                        assertEquals("$step gateBefore=$gateBefore", frozen, optimized)
                        runs++
                    }
                }
            }
        }
        assertEquals(OUTCOMES.size * contexts.size * 2 * 2, runs)
    }

    @Test
    fun `each outcome reaches the expected callback in both implementations`() {
        // Guards the scripts themselves: every outcome must exercise the branch it is named for,
        // so the equivalence above covers each catch block and both credential-type branches.
        val expectedAuto = mapOf(
            "no credential, provider message" to "unauthenticated",
            "no credential, null message" to "unauthenticated",
            "cancelled, message" to "unauthenticated",
            "cancelled, null message" to "unauthenticated",
            "provider configuration" to "unauthenticated",
            "interrupted" to "unauthenticated",
            "unknown" to "unauthenticated",
            "runtime" to "unauthenticated",
            "runtime, null message" to "unauthenticated",
            "foreign cancellation while active" to "unauthenticated",
            "password credential" to "unauthenticated",
            "other custom credential" to "unauthenticated",
            "google id token type, unparseable bundle" to "unauthenticated",
            "hangs until the scope is cancelled" to null
        )
        val expectedClick = mapOf(
            "no credential, provider message" to "error:No Google account is available on this device. Add one in system Settings > Accounts, then try again.",
            "no credential, null message" to "error:No Google account is available on this device. Add one in system Settings > Accounts, then try again.",
            "cancelled, message" to "cancelled",
            "cancelled, null message" to "cancelled",
            "provider configuration" to "error:Google sign-in needs an up-to-date version of Google Play services on this device.",
            "interrupted" to "error:Sign-in was interrupted. Please try again.",
            "unknown" to "error:unknown failure",
            "runtime" to "error:boom",
            "runtime, null message" to "error:Sign in failed",
            "foreign cancellation while active" to "error:task cancelled elsewhere",
            "password credential" to "error:Unexpected credential type",
            "other custom credential" to "error:Unexpected credential type",
            "google id token type, unparseable bundle" to "error:*",
            "hangs until the scope is cancelled" to null
        )
        assertEquals(OUTCOMES.map { it.name }.toSet(), expectedAuto.keys)
        assertEquals(OUTCOMES.map { it.name }.toSet(), expectedClick.keys)
        for (useFrozen in listOf(true, false)) {
            OUTCOMES.forEachIndexed { index, outcome ->
                val auto = callbackEvents(runSteps(useFrozen, listOf(Step.Auto(index, 0)), gateBefore = false))
                assertEquals("auto ${outcome.name} frozen=$useFrozen", listOfNotNull(expectedAuto.getValue(outcome.name)), auto)
                val click = callbackEvents(runSteps(useFrozen, listOf(Step.Click(index, 0)), gateBefore = false))
                val expected = expectedClick.getValue(outcome.name)
                if (expected == "error:*") {
                    assertEquals("click ${outcome.name} frozen=$useFrozen: $click", 1, click.size)
                    assertTrue("click ${outcome.name} frozen=$useFrozen: $click", click.single().startsWith("error:"))
                } else {
                    assertEquals("click ${outcome.name} frozen=$useFrozen", listOfNotNull(expected), click)
                }
            }
            // A suppressed gate short-circuits the auto flow before any Credential Manager request.
            val gated = runSteps(useFrozen, listOf(Step.Auto(0, 0)), gateBefore = true)
            assertEquals(listOf("unauthenticated"), callbackEvents(gated))
            assertTrue(gated.toString(), gated.any { it.endsWith("getCredential=0 sameContext=[] clear=0 requests=[]") })
        }
    }

    @Test
    fun `seeded random sign-in and sign-out sequences match the frozen code`() {
        for (seed in 0 until 48) {
            val random = Random(seed)
            val steps = List(1 + random.nextInt(6)) {
                when (random.nextInt(5)) {
                    0, 1 -> Step.Auto(random.nextInt(OUTCOMES.size), random.nextInt(contexts.size))
                    2, 3 -> Step.Click(random.nextInt(OUTCOMES.size), random.nextInt(contexts.size))
                    else -> Step.SignOut(random.nextInt(contexts.size))
                }
            }
            val gateBefore = random.nextBoolean()
            val frozen = runSteps(useFrozen = true, steps = steps, gateBefore = gateBefore)
            val optimized = runSteps(useFrozen = false, steps = steps, gateBefore = gateBefore)
            assertEquals("seed=$seed steps=$steps gateBefore=$gateBefore", frozen, optimized)
        }
    }

    private sealed interface Step {
        data class Auto(val outcome: Int, val context: Int) : Step
        data class Click(val outcome: Int, val context: Int) : Step
        data class SignOut(val context: Int) : Step
    }

    /** One scripted Credential Manager getCredential() result. */
    private class Outcome(
        val name: String,
        val hangs: Boolean = false,
        val produce: suspend () -> GetCredentialResponse
    )

    /**
     * Runs [steps] against the frozen or the optimized implementation and returns everything
     * observable: callbacks, "Auth" log lines (level, message, throwable), the Credential Manager
     * calls with the context passed and each request's options, and the gate state afterwards.
     */
    private fun runSteps(useFrozen: Boolean, steps: List<Step>, gateBefore: Boolean): List<String> {
        AutoSignInGate.suppressedAfterSignOut = gateBefore
        val trace = mutableListOf<String>()
        steps.forEachIndexed { index, step ->
            ShadowLog.reset()
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val events = mutableListOf<String>()
            val context = contexts[step.contextIndex()]
            val outcome = (step as? Step.Auto)?.let { OUTCOMES[it.outcome] }
                ?: (step as? Step.Click)?.let { OUTCOMES[it.outcome] }
            val credentialManager = AuthOptFakeCredentialManager(
                CredentialManager.create(context),
                outcome?.produce ?: NOT_REQUESTED
            )
            when (step) {
                is Step.Auto -> {
                    val onAuthSuccess = { events += "success" }
                    val onUnauthenticated = { events += "unauthenticated" }
                    if (useFrozen) {
                        frozenAuthAttemptAutoSignIn(context, credentialManager, onAuthSuccess, onUnauthenticated, scope, auth)
                    } else {
                        attemptAutoSignIn(context, credentialManager, onAuthSuccess, onUnauthenticated, scope, auth)
                    }
                }
                is Step.Click -> {
                    val onAuthSuccess = { events += "success" }
                    val onAuthError = { message: String -> events += "error:$message" }
                    val onAuthCancelled = { events += "cancelled" }
                    if (useFrozen) {
                        frozenAuthOnGoogleSignInClicked(context, credentialManager, onAuthSuccess, onAuthError, scope, onAuthCancelled, auth)
                    } else {
                        onGoogleSignInClicked(context, credentialManager, onAuthSuccess, onAuthError, scope, onAuthCancelled, auth)
                    }
                }
                // Unchanged by the optimization; it drives the gate between sign-in attempts.
                is Step.SignOut -> signOutUser(context, credentialManager, { events += "signedOut" }, scope, auth)
            }
            scheduler.runCurrent()
            if (outcome?.hangs == true) {
                // The screen leaves composition while the request is in flight.
                scope.cancel()
                scheduler.runCurrent()
            }
            scope.cancel()
            scheduler.runCurrent()

            events.forEach { trace += "#$index event $it" }
            ShadowLog.getLogsForTag("Auth").forEach { item ->
                trace += "#$index log ${item.type} ${item.msg} ${item.throwable?.javaClass?.name} ${item.throwable?.message}"
            }
            trace += "#$index getCredential=${credentialManager.getCredentialCalls} " +
                "sameContext=${credentialManager.contexts.map { it === context }} " +
                "clear=${credentialManager.clearCalls} " +
                "requests=${credentialManager.requests.map(::describeRequest)}"
        }
        trace += "gate=${AutoSignInGate.suppressedAfterSignOut}"
        return trace
    }

    private fun Step.contextIndex(): Int = when (this) {
        is Step.Auto -> context
        is Step.Click -> context
        is Step.SignOut -> context
    }

    private fun callbackEvents(trace: List<String>): List<String> =
        trace.filter { it.contains(" event ") }.map { it.substringAfter(" event ") }

    private fun describeRequest(request: GetCredentialRequest): String =
        request.credentialOptions.joinToString(prefix = "[", postfix = "]") { option ->
            "${option.javaClass.name} type=${option.type} auto=${option.isAutoSelectAllowed} " +
                "system=${option.isSystemProviderRequired} providers=${option.allowedProviders.size} " +
                "priority=${option.typePriorityHint} " +
                "request=${describeBundle(option.requestData)} query=${describeBundle(option.candidateQueryData)}"
        } + " origin=${request.origin}"

    @Suppress("DEPRECATION")
    private fun describeBundle(bundle: Bundle): String =
        bundle.keySet().sorted().joinToString(prefix = "{", postfix = "}") { key -> "$key=${describeValue(bundle.get(key))}" }

    private fun describeValue(value: Any?): String {
        if (value == null) return "null"
        return when (value) {
            is String, is Number, is Boolean, is Char -> value.toString()
            is Bundle -> describeBundle(value)
            is Collection<*> -> value.joinToString(prefix = "[", postfix = "]") { describeValue(it) }
            is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { describeValue(it) }
            is ByteArray -> value.contentToString()
            is IntArray -> value.contentToString()
            is LongArray -> value.contentToString()
            is BooleanArray -> value.contentToString()
            // Anything else may print an identity hash; its type is still compared.
            else -> value.javaClass.name
        }
    }

    /** Credential Manager whose get/clear calls are scripted; everything else goes to the real one. */
    private class AuthOptFakeCredentialManager(
        delegate: CredentialManager,
        private val onGet: suspend () -> GetCredentialResponse
    ) : CredentialManager by delegate {
        var getCredentialCalls = 0
            private set
        var clearCalls = 0
            private set
        val contexts = mutableListOf<Context>()
        val requests = mutableListOf<GetCredentialRequest>()

        override suspend fun getCredential(context: Context, request: GetCredentialRequest): GetCredentialResponse {
            getCredentialCalls++
            contexts += context
            requests += request
            return onGet()
        }

        override suspend fun clearCredentialState(request: ClearCredentialStateRequest) {
            clearCalls++
        }
    }

    private companion object {
        /** Sign-out steps never request a credential; this keeps the fake total anyway. */
        val NOT_REQUESTED: suspend () -> GetCredentialResponse = { throw NoCredentialException() }

        val OUTCOMES = listOf(
            Outcome("no credential, provider message") {
                throw NoCredentialException("10: [28433] Developer console is not set up correctly.")
            },
            Outcome("no credential, null message") { throw NoCredentialException() },
            Outcome("cancelled, message") { throw GetCredentialCancellationException("dismissed by the user") },
            Outcome("cancelled, null message") { throw GetCredentialCancellationException() },
            Outcome("provider configuration") {
                throw GetCredentialProviderConfigurationException("getCredentialAsync no provider dependencies found")
            },
            Outcome("interrupted") { throw GetCredentialInterruptedException("interrupted") },
            Outcome("unknown") { throw GetCredentialUnknownException("unknown failure") },
            Outcome("runtime") { throw RuntimeException("boom") },
            Outcome("runtime, null message") { throw IllegalStateException() },
            // A CancellationException that is not this coroutine's own cancellation is a failure.
            Outcome("foreign cancellation while active") { throw CancellationException("task cancelled elsewhere") },
            Outcome("password credential") { GetCredentialResponse(PasswordCredential("user@example.com", "secret")) },
            Outcome("other custom credential") {
                GetCredentialResponse(CustomCredential("com.example.OTHER_CREDENTIAL", Bundle()))
            },
            // The Google type with no id / token: GoogleIdTokenCredential.createFrom throws before
            // any Firebase call, which both flows must report through their generic handler.
            Outcome("google id token type, unparseable bundle") {
                GetCredentialResponse(CustomCredential(TYPE_GOOGLE_ID_TOKEN_CREDENTIAL, Bundle()))
            },
            Outcome("hangs until the scope is cancelled", hangs = true) { awaitCancellation() }
        )
    }
}

/**
 * The optimized screen against the frozen pre-optimization screen. The auto sign-in is suppressed
 * through [AutoSignInGate] so the real Credential Manager is never called.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AuthScreenOptimizationTest {

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
    fun `optimized AuthScreen renders the same semantics tree as the frozen screen`() {
        val auth = authOptimizationFirebaseAuth()
        // Variant bit 0: frozen / optimized. Bits 1-2: default, large-font + dense, dark theme.
        var variant by mutableIntStateOf(0)
        composeRule.setContent {
            val base = LocalDensity.current
            val density = if (variant / 2 == 1) Density(base.density * 1.5f, fontScale = 1.6f) else base
            val colors = if (variant / 2 == 2) darkColorScheme() else MaterialTheme.colorScheme
            CompositionLocalProvider(LocalDensity provides density) {
                MaterialTheme(colorScheme = colors) {
                    if (variant % 2 == 0) {
                        FrozenAuthScreen(onAuthSuccess = {}, auth = auth)
                    } else {
                        AuthScreen(onAuthSuccess = {}, auth = auth)
                    }
                }
            }
        }
        for (config in 0 until 3) {
            variant = config * 2
            composeRule.waitForIdle()
            val frozenUnmerged = describeAuthSemanticsTree(composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode())
            val frozenMerged = describeAuthSemanticsTree(composeRule.onRoot().fetchSemanticsNode())

            variant = config * 2 + 1
            composeRule.waitForIdle()
            val optimizedUnmerged = describeAuthSemanticsTree(composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode())
            val optimizedMerged = describeAuthSemanticsTree(composeRule.onRoot().fetchSemanticsNode())

            assertTrue("config $config: the tree must not be empty", frozenUnmerged.size > 10)
            assertTrue(
                "config $config: the frozen screen must have settled (button enabled): $frozenUnmerged",
                frozenUnmerged.none { it.contains("Disabled=") }
            )
            assertEquals("config $config unmerged", frozenUnmerged.joinToString("\n"), optimizedUnmerged.joinToString("\n"))
            assertEquals("config $config merged", frozenMerged.joinToString("\n"), optimizedMerged.joinToString("\n"))
        }
    }
}

/** Depth-indented lines of bounds plus every semantics property, with lambdas reduced to labels. */
private fun describeAuthSemanticsTree(root: SemanticsNode): List<String> {
    val out = mutableListOf<String>()
    fun visit(node: SemanticsNode, depth: Int) {
        val props = node.config.map { (key, value) -> "${key.name}=${describeAuthSemanticsValue(value)}" }.sorted()
        out += "${"  ".repeat(depth)}${node.boundsInRoot} $props"
        node.children.forEach { visit(it, depth + 1) }
    }
    visit(root, 0)
    return out
}

private fun describeAuthSemanticsValue(value: Any?): String {
    if (value == null) return "null"
    return when (value) {
        is AccessibilityAction<*> -> "action(${value.label})"
        is Function<*> -> "function"
        is List<*> -> value.joinToString(prefix = "[", postfix = "]") { describeAuthSemanticsValue(it) }
        else -> {
            val text = value.toString()
            val className = value.javaClass.name
            // A default Object.toString carries an identity hash that differs between the two screens.
            if (text == "$className@${Integer.toHexString(value.hashCode())}") className else text
        }
    }
}

private const val AUTH_OPTIMIZATION_APP_NAME = "auth-optimization"

/** A FirebaseAuth with no signed-in user, on its own FirebaseApp so other tests are unaffected. */
private fun authOptimizationFirebaseAuth(): FirebaseAuth {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val app = FirebaseApp.getApps(context).firstOrNull { it.name == AUTH_OPTIMIZATION_APP_NAME }
        ?: FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setApplicationId("com.aistudio.oculagaze.rxkmpq")
                .setProjectId("demo-auth-optimization")
                .setApiKey("fake-api-key-for-unit-tests")
                .build(),
            AUTH_OPTIMIZATION_APP_NAME
        )
    return FirebaseAuth.getInstance(app)
}

// ---------------------------------------------------------------------------------------------
// Frozen pre-optimization implementation (AuthScreen.kt at HEAD), copied verbatim apart from names.
// ---------------------------------------------------------------------------------------------

private fun CoroutineScope.frozenAuthRethrowIfCancelled(e: Exception) {
    if (e is CancellationException) ensureActive()
}

private fun frozenAuthAttemptAutoSignIn(
    context: Context,
    credentialManager: CredentialManager,
    onAuthSuccess: () -> Unit,
    onUnauthenticated: () -> Unit,
    scope: CoroutineScope,
    auth: FirebaseAuth = Firebase.auth
) {
    if (auth.currentUser != null) {
        onAuthSuccess()
        return
    }
    if (AutoSignInGate.suppressedAfterSignOut) {
        onUnauthenticated()
        return
    }
    val clientId = try {
        context.getString(R.string.default_web_client_id)
    } catch (e: Exception) {
        Log.w("Auth", "Auto sign-in skipped: default_web_client_id is missing", e)
        onUnauthenticated()
        return
    }

    val googleIdOption = GetGoogleIdOption.Builder()
        .setFilterByAuthorizedAccounts(true)
        .setServerClientId(clientId)
        .setAutoSelectEnabled(true)
        .build()

    val request = GetCredentialRequest.Builder().addCredentialOption(googleIdOption).build()

    scope.launch {
        try {
            val activityContext = context as? Activity ?: context
            val result = credentialManager.getCredential(activityContext, request)
            val credential = result.credential
            if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                auth.signInWithCredential(authCredential).await()
                onAuthSuccess()
            } else {
                onUnauthenticated()
            }
        } catch (e: NoCredentialException) {
            Log.w("Auth", "Auto sign-in: no authorized Google account on this device${e.message?.let { " ($it)" }.orEmpty()}", e)
            onUnauthenticated()
        } catch (e: GetCredentialCancellationException) {
            Log.d("Auth", "Auto sign-in dismissed")
            onUnauthenticated()
        } catch (e: Exception) {
            frozenAuthRethrowIfCancelled(e)
            Log.w("Auth", "Auto sign-in did not complete", e)
            onUnauthenticated()
        }
    }
}

private fun frozenAuthOnGoogleSignInClicked(
    context: Context,
    credentialManager: CredentialManager,
    onAuthSuccess: () -> Unit,
    onAuthError: (String) -> Unit,
    scope: CoroutineScope,
    onAuthCancelled: () -> Unit = {},
    auth: FirebaseAuth = Firebase.auth
) {
    val clientId = try {
        context.getString(R.string.default_web_client_id)
    } catch (e: Exception) {
        onAuthError("Google Sign-In configuration missing: default_web_client_id not found")
        return
    }

    val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
    val request = GetCredentialRequest.Builder().addCredentialOption(signInOption).build()

    scope.launch {
        try {
            val activityContext = context as? Activity ?: context
            val result = credentialManager.getCredential(activityContext, request)
            val credential = result.credential
            if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                auth.signInWithCredential(authCredential).await()
                AutoSignInGate.suppressedAfterSignOut = false
                onAuthSuccess()
            } else {
                onAuthError("Unexpected credential type")
            }
        } catch (e: GetCredentialCancellationException) {
            Log.w("Auth", "Google Sign-In cancelled or dismissed: ${e.message}", e)
            onAuthCancelled()
        } catch (e: NoCredentialException) {
            Log.w("Auth", "Google Sign-In found no usable credential", e)
            onAuthError("No Google account is available on this device. Add one in system Settings > Accounts, then try again.")
        } catch (e: GetCredentialProviderConfigurationException) {
            Log.w("Auth", "Google Sign-In credential provider unavailable", e)
            onAuthError("Google sign-in needs an up-to-date version of Google Play services on this device.")
        } catch (e: GetCredentialInterruptedException) {
            Log.w("Auth", "Google Sign-In interrupted", e)
            onAuthError("Sign-in was interrupted. Please try again.")
        } catch (e: Exception) {
            frozenAuthRethrowIfCancelled(e)
            Log.e("Auth", "Google Sign-In failed", e)
            onAuthError(e.localizedMessage ?: "Sign in failed")
        }
    }
}

@Composable
private fun FrozenAuthScreen(
    onAuthSuccess: () -> Unit,
    auth: FirebaseAuth = Firebase.auth
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }
    val firestoreDbId = remember {
        try {
            context.getString(R.string.firestore_database_id)
        } catch (_: Exception) {
            "firestore-named-db"
        }
    }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(auth) {
        isLoading = true
        frozenAuthAttemptAutoSignIn(
            context = context,
            credentialManager = credentialManager,
            onAuthSuccess = {
                isLoading = false
                onAuthSuccess()
            },
            onUnauthenticated = { isLoading = false },
            scope = coroutineScope,
            auth = auth
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.RemoveRedEye,
                        contentDescription = "Optical Engine Badge",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "PIXEL 10 PRO XL • OPTICAL GAZE WORKBENCH",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.auth_title),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.auth_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(20.dp))

                Image(
                    painter = painterResource(id = R.drawable.ic_optics_hero_illustration),
                    contentDescription = "Optical eye tracking calibration illustration",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(155.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                )

                Spacer(modifier = Modifier.height(20.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        FrozenAuthFeatureRow(
                            icon = Icons.Outlined.CenterFocusStrong,
                            title = "5-Point Fit + 4-Point Held-Out Validation",
                            desc = "Standardized 6D feature vector with per-eye glare confidence fusion & worst-quadrant error reporting."
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        FrozenAuthFeatureRow(
                            icon = Icons.Outlined.Analytics,
                            title = "Schmitt-Trigger Blink & Section Dwell Lab",
                            desc = "Disambiguates 80–349ms natural blinks from 350–800ms command blinks and tracks paragraph dwell."
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        FrozenAuthFeatureRow(
                            icon = Icons.Default.Lock,
                            title = stringResource(R.string.privacy_notice_title),
                            desc = "${stringResource(R.string.privacy_notice_body)} User-scoped Firestore collections (/users/{uid}/calibrations & /users/{uid}/sessions on DB $firestoreDbId)."
                        )
                    }
                }

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.16f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = errorMessage!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        errorMessage = null
                        isLoading = true
                        frozenAuthOnGoogleSignInClicked(
                            context = context,
                            credentialManager = credentialManager,
                            onAuthSuccess = {
                                isLoading = false
                                onAuthSuccess()
                            },
                            onAuthError = { msg ->
                                isLoading = false
                                errorMessage = msg
                            },
                            scope = coroutineScope,
                            onAuthCancelled = {
                                isLoading = false
                            },
                            auth = auth
                        )
                    },
                    enabled = !isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("google_sign_in_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.5.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.VerifiedUser,
                            contentDescription = "Google Sign-In",
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.sign_in_google),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FrozenAuthFeatureRow(
    icon: ImageVector,
    title: String,
    desc: String
) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = desc,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
