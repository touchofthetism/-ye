package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.CalibrationProfile
import com.example.data.model.DwellSpeedPreset
import com.example.data.model.TrackingSession
import com.example.ui.components.DWELL_DURATION_SLIDER_RANGE_MS
import com.example.ui.viewmodel.LiveGazeHudState
import com.example.ui.viewmodel.UiDataState
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// Shapes reused by every card and every list row.
private val CardShape = RoundedCornerShape(16.dp)
private val ListRowShape = RoundedCornerShape(12.dp)

/** Irreversible actions on this screen; each one runs only after the user confirms it in a dialog. */
private sealed interface PendingDestructiveAction {
    data object DeleteFirestoreOnly : PendingDestructiveAction
    data object WipeCloudAndLocal : PendingDestructiveAction
    data class DeleteProfile(val profileId: String, val profileName: String) : PendingDestructiveAction
    data class DeleteSession(val sessionId: String, val sessionTitle: String) : PendingDestructiveAction
}

/** Slider ticks come back as e.g. 1899.9999f; round instead of truncating so 1900 ms stays 1900 ms. */
internal fun sliderValueToDwellMs(value: Float): Long = value.roundToLong()

/** Same rounding as DwellTriggerSettingsDialog so both screens show the same margin for one setting. */
internal fun formatSensitivityLabel(multiplier: Float): String =
    String.format(Locale.US, "%.2f× (%d dp)", multiplier, (14f * multiplier).roundToInt())

/**
 * Saved error is held-out validation error only when the wizard ran the 4-point validation
 * ([CalibrationProfile.heldOutValidated]); otherwise it is the in-sample fit residual, and the label says so.
 */
internal fun formatProfileErrorLine(profile: CalibrationProfile): String = String.format(
    Locale.US,
    "%s • Calib. error (%s): mean ±%.1f%% (approx. ~%.1fmm) • worst ±%.1f%%",
    profile.deviceModel,
    if (profile.heldOutValidated) "held-out validation" else "in-sample fit (unvalidated)",
    profile.meanErrorPct,
    profile.meanErrorMm,
    profile.maxErrorPct
)

/**
 * A session without gaze samples saves 0/0/0 zones; that is "no zone data", not a 0% share of every zone.
 * Locale.US `%d` is plain `Int.toString()`, so templates give the same text without a Formatter.
 */
internal fun formatZoneSummary(topPct: Int, middlePct: Int, bottomPct: Int): String =
    if (topPct + middlePct + bottomPct <= 0) {
        "Zones n/a"
    } else {
        "Zones T:$topPct% M:$middlePct% B:$bottomPct%"
    }

/** Only the blink rate needs a Formatter (`%.1f`); `%d` and `%s` are plain string conversions. */
internal fun formatSessionSummaryLine(session: TrackingSession): String =
    "${session.durationSeconds}s • ${formatEstimatedWpm(session.effectiveWordsPerMinute)} • " +
        "${String.format(Locale.US, "%.1f", session.blinksPerMinute)} blinks/min • " +
        formatZoneSummary(session.topZonePercentage, session.middleZonePercentage, session.bottomZonePercentage)

/**
 * Index of the single row to mark as the loaded calibration, or -1. Rows are matched by the id of the profile the
 * engine applied ([LiveGazeHudState.activeProfileId], null = default matrix): names are not unique (every wizard
 * run defaults to "<model> Profile"), and unvalidated profiles expose no HUD metrics to tell same-named rows apart.
 */
internal fun indexOfActiveProfile(profiles: List<CalibrationProfile>, activeProfileId: String?): Int =
    if (activeProfileId.isNullOrBlank()) -1 else profiles.indexOfFirst { it.id == activeProfileId }

@Composable
fun CloudPrivacyScreen(
    hudState: LiveGazeHudState,
    currentUserId: String = "",
    currentUserEmail: String = "",
    currentUserName: String = "",
    firestoreDatabaseId: String = "",
    profilesState: UiDataState<List<CalibrationProfile>>,
    sessionsState: UiDataState<List<TrackingSession>>,
    onToggleCloudSync: (Boolean) -> Unit,
    onActivateProfile: (CalibrationProfile) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onSelectSessionReplay: (TrackingSession) -> Unit,
    onDeleteSession: (String) -> Unit,
    onDeleteFirestoreOnly: () -> Unit = {},
    onEraseAllTelemetry: () -> Unit,
    onSignOut: () -> Unit,
    onChangeDwellDurationMs: (Long) -> Unit = {},
    onChangeDwellSensitivityMultiplier: (Float) -> Unit = {},
    onSelectDwellPreset: (DwellSpeedPreset) -> Unit = {},
    isAttentionRecording: Boolean = false
) {
    // hudState changes on every camera frame. The screen shows only the seven fields passed below, so its content
    // (scroll container, cards and dialog) is skipped unless one of them or another argument changes.
    CloudPrivacyScreenContent(
        dwellTriggerMs = hudState.dwellTriggerMs,
        dwellSensitivityRadiusMultiplier = hudState.dwellSensitivityRadiusMultiplier,
        activeDwellPreset = hudState.activeDwellPreset,
        cloudSyncEnabled = hudState.cloudSyncEnabled,
        isDeletingCloudData = hudState.isDeletingCloudData,
        lastCloudDeletionSummary = hudState.lastCloudDeletionSummary,
        activeProfileId = hudState.activeProfileId,
        currentUserId = currentUserId,
        currentUserEmail = currentUserEmail,
        currentUserName = currentUserName,
        firestoreDatabaseId = firestoreDatabaseId,
        profilesState = profilesState,
        sessionsState = sessionsState,
        onToggleCloudSync = onToggleCloudSync,
        onActivateProfile = onActivateProfile,
        onDeleteProfile = onDeleteProfile,
        onSelectSessionReplay = onSelectSessionReplay,
        onDeleteSession = onDeleteSession,
        onDeleteFirestoreOnly = onDeleteFirestoreOnly,
        onEraseAllTelemetry = onEraseAllTelemetry,
        onSignOut = onSignOut,
        onChangeDwellDurationMs = onChangeDwellDurationMs,
        onChangeDwellSensitivityMultiplier = onChangeDwellSensitivityMultiplier,
        onSelectDwellPreset = onSelectDwellPreset,
        isAttentionRecording = isAttentionRecording
    )
}

@Composable
private fun CloudPrivacyScreenContent(
    dwellTriggerMs: Long,
    dwellSensitivityRadiusMultiplier: Float,
    activeDwellPreset: DwellSpeedPreset,
    cloudSyncEnabled: Boolean,
    isDeletingCloudData: Boolean,
    lastCloudDeletionSummary: String?,
    activeProfileId: String?,
    currentUserId: String,
    currentUserEmail: String,
    currentUserName: String,
    firestoreDatabaseId: String,
    profilesState: UiDataState<List<CalibrationProfile>>,
    sessionsState: UiDataState<List<TrackingSession>>,
    onToggleCloudSync: (Boolean) -> Unit,
    onActivateProfile: (CalibrationProfile) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onSelectSessionReplay: (TrackingSession) -> Unit,
    onDeleteSession: (String) -> Unit,
    onDeleteFirestoreOnly: () -> Unit,
    onEraseAllTelemetry: () -> Unit,
    onSignOut: () -> Unit,
    onChangeDwellDurationMs: (Long) -> Unit,
    onChangeDwellSensitivityMultiplier: (Float) -> Unit,
    onSelectDwellPreset: (DwellSpeedPreset) -> Unit,
    isAttentionRecording: Boolean
) {
    var pendingDestructive by remember { mutableStateOf<PendingDestructiveAction?>(null) }

    // Each card takes only the fields it shows, so a change in one card's fields recomposes only that card.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        DwellSettingsCard(
            dwellTriggerMs = dwellTriggerMs,
            dwellSensitivityRadiusMultiplier = dwellSensitivityRadiusMultiplier,
            activeDwellPreset = activeDwellPreset,
            onChangeDwellDurationMs = onChangeDwellDurationMs,
            onChangeDwellSensitivityMultiplier = onChangeDwellSensitivityMultiplier,
            onSelectDwellPreset = onSelectDwellPreset
        )

        PrivacySettingsCard(
            cloudSyncEnabled = cloudSyncEnabled,
            isDeletingCloudData = isDeletingCloudData,
            lastCloudDeletionSummary = lastCloudDeletionSummary,
            currentUserId = currentUserId,
            currentUserEmail = currentUserEmail,
            currentUserName = currentUserName,
            firestoreDatabaseId = firestoreDatabaseId,
            onToggleCloudSync = onToggleCloudSync,
            onRequestDeleteFirestoreOnly = { pendingDestructive = PendingDestructiveAction.DeleteFirestoreOnly },
            onRequestWipeCloudAndLocal = { pendingDestructive = PendingDestructiveAction.WipeCloudAndLocal },
            onSignOut = onSignOut
        )

        SavedProfilesCard(
            profilesState = profilesState,
            activeProfileId = activeProfileId,
            onActivateProfile = onActivateProfile,
            onRequestDeleteProfile = { profile ->
                pendingDestructive = PendingDestructiveAction.DeleteProfile(profile.id, profile.name)
            }
        )

        SessionsCard(
            sessionsState = sessionsState,
            isAttentionRecording = isAttentionRecording,
            onSelectSessionReplay = onSelectSessionReplay,
            onRequestDeleteSession = { session ->
                pendingDestructive = PendingDestructiveAction.DeleteSession(session.id, session.title)
            }
        )
    }

    pendingDestructive?.let { action ->
        DestructiveActionConfirmDialog(
            action = action,
            currentUserId = currentUserId,
            isDeletingCloudData = isDeletingCloudData,
            onConfirm = {
                pendingDestructive = null
                when (action) {
                    PendingDestructiveAction.DeleteFirestoreOnly -> onDeleteFirestoreOnly()
                    PendingDestructiveAction.WipeCloudAndLocal -> onEraseAllTelemetry()
                    is PendingDestructiveAction.DeleteProfile -> onDeleteProfile(action.profileId)
                    is PendingDestructiveAction.DeleteSession -> onDeleteSession(action.sessionId)
                }
            },
            onDismiss = { pendingDestructive = null }
        )
    }
}

@Composable
private fun DwellSettingsCard(
    dwellTriggerMs: Long,
    dwellSensitivityRadiusMultiplier: Float,
    activeDwellPreset: DwellSpeedPreset,
    onChangeDwellDurationMs: (Long) -> Unit,
    onChangeDwellSensitivityMultiplier: (Float) -> Unit,
    onSelectDwellPreset: (DwellSpeedPreset) -> Unit
) {
    // Dwell Trigger & Eye-Tracking Speed Settings Menu Card
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("dwell_settings_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = CardShape
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.PhoneAndroid,
                        contentDescription = "Dwell Trigger Settings",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Dwell Sensitivity & Speed Settings",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = "$dwellTriggerMs ms",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Text(
                text = "Configure fixation duration and boundary sensitivity tolerance to match personal eye movement speed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Eye-Tracking Speed Presets
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DwellSpeedPreset.entries.forEach { preset ->
                    val isSelected = activeDwellPreset == preset && dwellTriggerMs == preset.durationMs
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectDwellPreset(preset) },
                        label = {
                            Text(
                                text = preset.title.take(12),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        modifier = Modifier.testTag("speed_preset_chip_${preset.name.lowercase()}")
                    )
                }
            }

            // Slider 1: Dwell Duration Threshold
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Duration Threshold (Hold Time):",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "$dwellTriggerMs ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = dwellTriggerMs.toFloat(),
                    onValueChange = { onChangeDwellDurationMs(sliderValueToDwellMs(it)) },
                    valueRange = DWELL_DURATION_SLIDER_RANGE_MS,
                    steps = 17,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("privacy_dwell_duration_slider")
                )
            }

            // Slider 2: Sensitivity Radius Multiplier (Boundary Margin)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Lock Sensitivity (Boundary Margin):",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = formatSensitivityLabel(dwellSensitivityRadiusMultiplier),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = dwellSensitivityRadiusMultiplier,
                    onValueChange = onChangeDwellSensitivityMultiplier,
                    valueRange = 0.6f..2.0f,
                    steps = 13,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("privacy_dwell_sensitivity_slider")
                )
            }
        }
    }
}

@Composable
private fun PrivacySettingsCard(
    cloudSyncEnabled: Boolean,
    isDeletingCloudData: Boolean,
    lastCloudDeletionSummary: String?,
    currentUserId: String,
    currentUserEmail: String,
    currentUserName: String,
    firestoreDatabaseId: String,
    onToggleCloudSync: (Boolean) -> Unit,
    onRequestDeleteFirestoreOnly: () -> Unit,
    onRequestWipeCloudAndLocal: () -> Unit,
    onSignOut: () -> Unit
) {
    // Biometric Privacy, Explicit Cloud Telemetry Opt-In/Out Toggle & Firestore Data Deletion Card
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("privacy_settings_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = CardShape
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            val cloudOptInTitle = stringResource(R.string.cloud_telemetry_opt_in_title)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (cloudSyncEnabled) Icons.Default.CloudDone else Icons.Default.CloudOff,
                        contentDescription = "Cloud Telemetry Sync Status",
                        tint = if (cloudSyncEnabled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = cloudOptInTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (cloudSyncEnabled) {
                                stringResource(R.string.cloud_telemetry_status_opted_in)
                            } else {
                                stringResource(R.string.cloud_telemetry_status_opted_out)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (cloudSyncEnabled) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
                Switch(
                    checked = cloudSyncEnabled,
                    onCheckedChange = onToggleCloudSync,
                    modifier = Modifier
                        .testTag("cloud_sync_toggle")
                        .semantics { contentDescription = cloudOptInTitle }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                val accountHeader = buildString {
                    if (currentUserName.isNotBlank()) append("$currentUserName • ")
                    if (currentUserEmail.isNotBlank()) append(currentUserEmail) else append("Authenticated Google Account")
                }
                Text(
                    text = accountHeader,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = "FirebaseAuth UID: ${currentUserId.ifBlank { "active-session" }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Scoped Paths: /users/${currentUserId.take(10)}…/calibrations & /sessions",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (firestoreDatabaseId.isNotBlank()) {
                    Text(
                        text = "Firestore DB: $firestoreDatabaseId • Privacy Mode: ${if (cloudSyncEnabled) "OPTED-IN (Cloud + Local)" else "OPTED-OUT (Local Only)"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!lastCloudDeletionSummary.isNullOrBlank()) {
                    Text(
                        text = lastCloudDeletionSummary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("cloud_deletion_status_text")
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Primary Firestore Cloud Data Deletion Button (deletes Firestore documents, preserves local calibration).
            // Opens a confirmation dialog; nothing is deleted on the first tap.
            Button(
                onClick = onRequestDeleteFirestoreOnly,
                enabled = !isDeletingCloudData,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("delete_firestore_data_button")
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteForever,
                    contentDescription = stringResource(R.string.delete_firestore_telemetry_button)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isDeletingCloudData) {
                        stringResource(R.string.deleting_firestore_telemetry_label)
                    } else {
                        stringResource(R.string.delete_firestore_telemetry_button)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Full-width row of its own, away from Sign Out, and confirmed in a dialog before anything is wiped.
            OutlinedButton(
                onClick = onRequestWipeCloudAndLocal,
                enabled = !isDeletingCloudData,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("erase_all_cloud_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Wipe Cloud and Local Vault",
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Wipe Cloud & Local Vault",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Disabled while a cloud deletion is in flight: signing out mid-deletion would leave a partial wipe.
            OutlinedButton(
                onClick = onSignOut,
                enabled = !isDeletingCloudData,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("sign_out_button")
            ) {
                Icon(imageVector = Icons.AutoMirrored.Filled.Logout, contentDescription = "Sign Out")
                Spacer(modifier = Modifier.width(6.dp))
                Text("Sign Out", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun SavedProfilesCard(
    profilesState: UiDataState<List<CalibrationProfile>>,
    activeProfileId: String?,
    onActivateProfile: (CalibrationProfile) -> Unit,
    onRequestDeleteProfile: (CalibrationProfile) -> Unit
) {
    // Saved Calibration Profiles Section
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = CardShape
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Saved Calibration Profiles (Device-Tagged)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            when (profilesState) {
                is UiDataState.Loading -> {
                    Text(
                        text = "Loading calibration profiles...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is UiDataState.Error -> {
                    Text(
                        text = profilesState.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                is UiDataState.Success -> {
                    if (profilesState.data.isEmpty()) {
                        Text(
                            text = "No saved profiles yet. Run the calibration wizard (5-point fit, optional 4-point held-out validation) in the Calibrate tab.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val activeIndex = indexOfActiveProfile(profilesState.data, activeProfileId)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            profilesState.data.forEachIndexed { index, profile ->
                                // Keyed by id: deleting or adding a profile moves the other rows (and their
                                // remembered error lines) instead of re-binding every row after it.
                                key(profile.id) {
                                    val isCurrent = index == activeIndex
                                    val errorLine = remember(profile) { formatProfileErrorLine(profile) }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(ListRowShape)
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                            .border(
                                                width = if (isCurrent) 1.5.dp else 1.dp,
                                                color = if (isCurrent) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                                                shape = ListRowShape
                                            )
                                            .clickable { onActivateProfile(profile) }
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Default.PhoneAndroid,
                                                    contentDescription = "Device Model",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = profile.name,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = errorLine,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        IconButton(
                                            onClick = { onRequestDeleteProfile(profile) },
                                            modifier = Modifier.testTag("delete_profile_${profile.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Delete Profile",
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionsCard(
    sessionsState: UiDataState<List<TrackingSession>>,
    isAttentionRecording: Boolean,
    onSelectSessionReplay: (TrackingSession) -> Unit,
    onRequestDeleteSession: (TrackingSession) -> Unit
) {
    // Historical Attention Sessions Section
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = CardShape
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Recorded Section Attention Sessions (Tap to Replay)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (isAttentionRecording) {
                // Opening a replay ends the live recording without saving it, so replays are locked meanwhile.
                Text(
                    text = "A recording is in progress. Stop & Save it in the Attention Lab before opening a replay.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.testTag("replay_locked_while_recording_text")
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            when (sessionsState) {
                is UiDataState.Loading -> {
                    Text(
                        text = "Loading tracking sessions...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is UiDataState.Error -> {
                    Text(
                        text = sessionsState.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                is UiDataState.Success -> {
                    if (sessionsState.data.isEmpty()) {
                        Text(
                            text = "No recorded sessions yet. Record a reading pass in the Attention Lab tab.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            sessionsState.data.forEach { session ->
                                // Keyed by id: a newly saved session is inserted at the top, which would
                                // otherwise re-bind every row and re-format every summary line below it.
                                key(session.id) {
                                    val summaryLine = remember(session) { formatSessionSummaryLine(session) }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(ListRowShape)
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                            .border(1.dp, MaterialTheme.colorScheme.outline, ListRowShape)
                                            .clickable(enabled = !isAttentionRecording) { onSelectSessionReplay(session) }
                                            .testTag("session_row_${session.id}")
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = session.title,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = summaryLine,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        IconButton(
                                            onClick = { onRequestDeleteSession(session) },
                                            modifier = Modifier.testTag("delete_session_${session.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Delete Session",
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DestructiveActionConfirmDialog(
    action: PendingDestructiveAction,
    currentUserId: String,
    isDeletingCloudData: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val uidLabel = currentUserId.ifBlank { "{uid}" }
    val (title, body, confirmLabel) = when (action) {
        PendingDestructiveAction.DeleteFirestoreOnly -> Triple(
            stringResource(R.string.confirm_delete_cloud_dialog_title),
            stringResource(R.string.confirm_delete_cloud_dialog_body, uidLabel),
            stringResource(R.string.confirm_delete_cloud_action)
        )
        PendingDestructiveAction.WipeCloudAndLocal -> Triple(
            "Wipe Cloud & Local Vault?",
            "This permanently deletes every calibration profile and attention session stored on this device, " +
                "plus all calibration and session documents under /users/$uidLabel in Cloud Firestore. " +
                "This cannot be undone; you will need to run the calibration wizard again.",
            "Wipe Everything"
        )
        is PendingDestructiveAction.DeleteProfile -> Triple(
            "Delete calibration profile?",
            "\"${action.profileName}\" will be removed from the on-device vault and, if it was synced, from " +
                "Cloud Firestore. This cannot be undone.",
            "Delete Profile"
        )
        is PendingDestructiveAction.DeleteSession -> Triple(
            "Delete attention session?",
            "\"${action.sessionTitle}\" will be removed from the on-device vault and, if it was synced, from " +
                "Cloud Firestore. This cannot be undone.",
            "Delete Session"
        )
    }
    val confirmEnabled = when (action) {
        PendingDestructiveAction.DeleteFirestoreOnly, PendingDestructiveAction.WipeCloudAndLocal -> !isDeletingCloudData
        else -> true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = { Text(text = body) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = confirmEnabled,
                modifier = Modifier.testTag("confirm_destructive_action_button")
            ) {
                Text(
                    text = confirmLabel,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("cancel_destructive_action_button")
            ) {
                Text(text = "Cancel")
            }
        },
        modifier = Modifier.testTag("destructive_action_confirm_dialog")
    )
}
