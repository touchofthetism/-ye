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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.CalibrationProfile
import com.example.data.model.TrackingSession
import com.example.ui.viewmodel.LiveGazeHudState
import com.example.ui.viewmodel.UiDataState
import java.util.Locale

import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Security
import androidx.compose.ui.res.stringResource
import com.example.R

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
    onSelectDwellPreset: (com.example.data.model.DwellSpeedPreset) -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Dwell Trigger & Eye-Tracking Speed Settings Menu Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("dwell_settings_card"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp)
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
                        text = "${hudState.dwellTriggerMs} ms",
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
                    com.example.data.model.DwellSpeedPreset.entries.forEach { preset ->
                        val isSelected = hudState.activeDwellPreset == preset && hudState.dwellTriggerMs == preset.durationMs
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
                            text = "${hudState.dwellTriggerMs} ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    androidx.compose.material3.Slider(
                        value = hudState.dwellTriggerMs.toFloat(),
                        onValueChange = { onChangeDwellDurationMs(it.toLong()) },
                        valueRange = 400f..2200f,
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
                            text = String.format(Locale.US, "%.2f× (%d dp)", hudState.dwellSensitivityRadiusMultiplier, (14f * hudState.dwellSensitivityRadiusMultiplier).toInt()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    androidx.compose.material3.Slider(
                        value = hudState.dwellSensitivityRadiusMultiplier,
                        onValueChange = { onChangeDwellSensitivityMultiplier(it) },
                        valueRange = 0.6f..2.0f,
                        steps = 13,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("privacy_dwell_sensitivity_slider")
                    )
                }
            }
        }

        // Biometric Privacy, Explicit Cloud Telemetry Opt-In/Out Toggle & Firestore Data Deletion Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("privacy_settings_card"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
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
                            imageVector = if (hudState.cloudSyncEnabled) Icons.Default.CloudDone else Icons.Default.CloudOff,
                            contentDescription = "Cloud Telemetry Sync Status",
                            tint = if (hudState.cloudSyncEnabled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.cloud_telemetry_opt_in_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (hudState.cloudSyncEnabled) {
                                    stringResource(R.string.cloud_telemetry_status_opted_in)
                                } else {
                                    stringResource(R.string.cloud_telemetry_status_opted_out)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (hudState.cloudSyncEnabled) {
                                    MaterialTheme.colorScheme.secondary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                    Switch(
                        checked = hudState.cloudSyncEnabled,
                        onCheckedChange = onToggleCloudSync,
                        modifier = Modifier.testTag("cloud_sync_toggle")
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
                            text = "Firestore DB: $firestoreDatabaseId • Privacy Mode: ${if (hudState.cloudSyncEnabled) "OPTED-IN (Cloud + Local)" else "OPTED-OUT (Local Only)"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!hudState.lastCloudDeletionSummary.isNullOrBlank()) {
                        Text(
                            text = hudState.lastCloudDeletionSummary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("cloud_deletion_status_text")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Primary Firestore Cloud Data Deletion Button (deletes Firestore documents, preserves local calibration)
                Button(
                    onClick = onDeleteFirestoreOnly,
                    enabled = !hudState.isDeletingCloudData,
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
                        text = if (hudState.isDeletingCloudData) {
                            stringResource(R.string.deleting_firestore_telemetry_label)
                        } else {
                            stringResource(R.string.delete_firestore_telemetry_button)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onEraseAllTelemetry,
                        enabled = !hudState.isDeletingCloudData,
                        modifier = Modifier
                            .weight(1.4f)
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

                    OutlinedButton(
                        onClick = onSignOut,
                        modifier = Modifier
                            .weight(1f)
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

        // Saved Calibration Profiles Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp)
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
                                text = "No saved profiles yet. Run the 5-Point Fit + 4-Point Validation wizard in the Calibrate tab.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                profilesState.data.forEach { profile ->
                                    val isCurrent = profile.name == hudState.activeProfileName
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                            .border(
                                                width = if (isCurrent) 1.5.dp else 1.dp,
                                                color = if (isCurrent) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                                                shape = RoundedCornerShape(12.dp)
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
                                                text = String.format(
                                                    Locale.US,
                                                    "%s • Mean ±%.1f%% (approx. ~%.1fmm) • Worst ±%.1f%%",
                                                    profile.deviceModel,
                                                    profile.meanErrorPct,
                                                    profile.meanErrorMm,
                                                    profile.maxErrorPct
                                                ),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        IconButton(
                                            onClick = { onDeleteProfile(profile.id) },
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

        // Historical Attention Sessions Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Recorded Section Attention Sessions (Tap to Replay)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))

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
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                                            .clickable { onSelectSessionReplay(session) }
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
                                                text = String.format(
                                                    Locale.US,
                                                    "%ds • %d eff. WPM • %.1f blinks/min • Zones T:%d%% M:%d%% B:%d%%",
                                                    session.durationSeconds,
                                                    session.effectiveWordsPerMinute,
                                                    session.blinksPerMinute,
                                                    session.topZonePercentage,
                                                    session.middleZonePercentage,
                                                    session.bottomZonePercentage
                                                ),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        IconButton(
                                            onClick = { onDeleteSession(session.id) },
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
