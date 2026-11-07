package com.example.ui.screens

import android.content.Context
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import com.example.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/** Upper bound on the credential-state clear that runs before the Firebase sign-out. */
internal const val CREDENTIAL_CLEAR_TIMEOUT_MS = 3_000L

/**
 * Suppresses the returning-user auto sign-in for the rest of the process after an explicit
 * sign-out, so the AuthScreen shown next does not immediately offer (or auto-select) the account
 * the user just left. A cold start resets it; a successful explicit sign-in clears it.
 */
internal object AutoSignInGate {
    @Volatile
    var suppressedAfterSignOut: Boolean = false
}

/**
 * Rethrows when this coroutine itself was cancelled (its scope went away), so structured
 * cancellation is never reported as a sign-in or sign-out failure. A [CancellationException] from
 * elsewhere (for example a cancelled Task) while the coroutine is still active falls through and is
 * handled as an ordinary failure, so the caller's loading state still clears.
 */
private fun CoroutineScope.rethrowIfCancelled(e: Exception) {
    if (e is CancellationException) ensureActive()
}

/**
 * Signs in to Firebase with a Credential Manager Google ID token and returns true once that has
 * completed. Returns false, without touching [auth], for any other credential. Failures (a token
 * that does not parse, a rejected Firebase sign-in) propagate to the caller's handlers unchanged.
 */
private suspend fun signInWithGoogleIdToken(credential: Credential, auth: FirebaseAuth): Boolean {
    if (credential !is CustomCredential || credential.type != TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) return false
    val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
    auth.signInWithCredential(GoogleAuthProvider.getCredential(googleIdToken, null)).await()
    return true
}

fun attemptAutoSignIn(
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
            val credential = credentialManager.getCredential(context, request).credential
            if (signInWithGoogleIdToken(credential, auth)) onAuthSuccess() else onUnauthenticated()
        } catch (e: NoCredentialException) {
            // Keep the provider's message: a misconfigured OAuth client (unregistered signing
            // SHA-1, DEVELOPER_ERROR / code 10) can also arrive here instead of failing loudly.
            Log.w("Auth", "Auto sign-in: no authorized Google account on this device${e.message?.let { " ($it)" }.orEmpty()}", e)
            onUnauthenticated()
        } catch (e: GetCredentialCancellationException) {
            Log.d("Auth", "Auto sign-in dismissed")
            onUnauthenticated()
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            Log.w("Auth", "Auto sign-in did not complete", e)
            onUnauthenticated()
        }
    }
}

fun onGoogleSignInClicked(
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
            val credential = credentialManager.getCredential(context, request).credential
            if (signInWithGoogleIdToken(credential, auth)) {
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
            rethrowIfCancelled(e)
            Log.e("Auth", "Google Sign-In failed", e)
            onAuthError(e.localizedMessage ?: "Sign in failed")
        }
    }
}

fun signOutUser(
    context: Context,
    credentialManager: CredentialManager,
    onSignOutComplete: () -> Unit,
    scope: CoroutineScope,
    auth: FirebaseAuth = Firebase.auth
) {
    AutoSignInGate.suppressedAfterSignOut = true
    // UNDISPATCHED: the body starts right here, so the finally block below (the Firebase sign-out)
    // runs even when [scope] is cancelled before a dispatch would have started it.
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            // Clear first: it turns off Google auto-select for the next sign-in. The Firebase
            // sign-out waits for it because it removes the composition that may own [scope].
            val cleared = withTimeoutOrNull(CREDENTIAL_CLEAR_TIMEOUT_MS) {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            }
            if (cleared == null) {
                Log.w("Auth", "Clearing credential state timed out after $CREDENTIAL_CLEAR_TIMEOUT_MS ms")
            }
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            Log.e("Auth", "Failed to clear credential state", e)
        } finally {
            // Sign out even on failure, timeout or cancellation.
            auth.signOut()
            onSignOutComplete()
        }
    }
}

// Shapes are immutable: one instance each instead of a fresh allocation per use and recomposition.
private val PillShape = RoundedCornerShape(50)
private val LargeCardShape = RoundedCornerShape(16.dp)
private val ErrorCardShape = RoundedCornerShape(12.dp)
private val SignInButtonShape = RoundedCornerShape(14.dp)
private val IconTileShape = RoundedCornerShape(10.dp)

@Composable
fun AuthScreen(
    onAuthSuccess: () -> Unit,
    auth: FirebaseAuth = Firebase.auth
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(auth) {
        // Disable the button while the auto attempt runs so a tap cannot start a second,
        // overlapping sign-in. Every path of attemptAutoSignIn ends in one of the callbacks.
        isLoading = true
        attemptAutoSignIn(
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
                AuthIntro()

                val error = errorMessage
                if (error != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.16f)
                        ),
                        shape = ErrorCardShape
                    ) {
                        Text(
                            text = error,
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
                        onGoogleSignInClicked(
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
                    shape = SignInButtonShape,
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

/**
 * The static badge, title, hero image and feature card. It has no parameters, so it is skipped
 * when [AuthScreen] recomposes for a loading or error change; only a theme, locale or context
 * change recomposes it.
 */
@Composable
private fun AuthIntro() {
    val context = LocalContext.current
    val firestoreDbId = remember {
        try {
            context.getString(R.string.firestore_database_id)
        } catch (_: Exception) {
            "firestore-named-db"
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(PillShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, PillShape)
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
            .clip(LargeCardShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, LargeCardShape)
    )

    Spacer(modifier = Modifier.height(20.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = LargeCardShape
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // All three icon tiles draw the same gradient: build it once, not once per row.
            val iconTileBrush = Brush.linearGradient(
                listOf(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f)
                )
            )
            FeatureRow(
                icon = Icons.Outlined.CenterFocusStrong,
                iconTileBrush = iconTileBrush,
                title = "5-Point Fit + 4-Point Held-Out Validation",
                desc = "Standardized 6D feature vector with per-eye glare confidence fusion & worst-quadrant error reporting."
            )
            Spacer(modifier = Modifier.height(12.dp))
            FeatureRow(
                icon = Icons.Outlined.Analytics,
                iconTileBrush = iconTileBrush,
                title = "Schmitt-Trigger Blink & Section Dwell Lab",
                desc = "Disambiguates 80–349ms natural blinks from 350–800ms command blinks and tracks paragraph dwell."
            )
            Spacer(modifier = Modifier.height(12.dp))
            FeatureRow(
                icon = Icons.Default.Lock,
                iconTileBrush = iconTileBrush,
                title = stringResource(R.string.privacy_notice_title),
                desc = "${stringResource(R.string.privacy_notice_body)} User-scoped Firestore collections (/users/{uid}/calibrations & /users/{uid}/sessions on DB $firestoreDbId)."
            )
        }
    }
}

@Composable
private fun FeatureRow(
    icon: ImageVector,
    iconTileBrush: Brush,
    title: String,
    desc: String
) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(IconTileShape)
                .background(iconTileBrush),
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
