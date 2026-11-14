package com.example.ui.developer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.ui.viewmodel.LiveGazeHudState

/** Height of the reserved developer strip region at the bottom of Speak and Read (docs/UI_DESIGN.md 2.16.2). */
const val DEV_HUD_STRIP_HEIGHT_DP = 40

/**
 * The one-line developer strip (docs/UI_DESIGN.md 2.16.2). Shown only with developer mode and its switch on.
 *
 * CONTRACT STUB (UI_DESIGN.md 11.3): the signature is fixed. The developer panel engineer replaces the body.
 * Speak and Read reserve [DEV_HUD_STRIP_HEIGHT_DP] for it; the shell draws it.
 */
@Composable
fun DevHudStrip(hud: LiveGazeHudState, modifier: Modifier = Modifier) {
}
