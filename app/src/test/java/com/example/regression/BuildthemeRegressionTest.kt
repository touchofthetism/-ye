package com.example.regression

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.ResourceFont
import com.example.R
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.OculaDarkColorScheme
import com.example.ui.theme.PlusJakartaSansFamily
import com.example.ui.theme.SpaceGroteskFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for the theme files (Type.kt, Theme.kt, Color.kt).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BuildthemeRegressionTest {

    // --- A133 / B30: the bundled .ttf files are single static Regular (400) faces ---

    @Test
    fun staticRegularFontsAreDeclaredOnlyAtTheirRealWeightSoComposeCanSynthesizeBold() {
        val families: List<Triple<String, FontFamily, Int>> = listOf(
            Triple("SpaceGrotesk", SpaceGroteskFamily, R.font.space_grotesk),
            Triple("PlusJakartaSans", PlusJakartaSansFamily, R.font.plus_jakarta_sans),
            Triple("JetBrainsMono", JetBrainsMonoFamily, R.font.jetbrains_mono)
        )
        for ((name, family, expectedResId) in families) {
            val fonts = (family as FontListFontFamily).fonts
            assertTrue("$name must still use its bundled font file", fonts.isNotEmpty())
            for (font in fonts) {
                assertEquals("$name must still use its bundled font file", expectedResId, (font as ResourceFont).resId)
                // Compose fakes bold only when the matched Font's declared weight is below 600
                // (FontSynthesis.synthesizeTypeface). If every entry declares the file's real Regular weight,
                // any Bold/SemiBold request matches a sub-600 entry and is emboldened. Declaring the Regular
                // file as Bold/SemiBold made those requests render at regular weight.
                assertTrue(
                    "$name declares its static Regular file as ${font.weight}; bold text would render regular",
                    font.weight < FontWeight.SemiBold
                )
            }
        }
    }

    // --- A134: surfaceContainer* roles and outline contrast ---

    @Test
    fun surfaceContainerRolesUseTheSlatePaletteInsteadOfTheM3BaselinePurpleGrey() {
        val scheme = OculaDarkColorScheme
        // M3 1.3.0 dark baseline values that the scheme fell back to when these roles were unset.
        assertNotEquals("AlertDialog container", Color(0xFF2B2930), scheme.surfaceContainerHigh)
        assertNotEquals("unchecked Switch track", Color(0xFF36343B), scheme.surfaceContainerHighest)
        assertNotEquals("surfaceContainer", Color(0xFF211F26), scheme.surfaceContainer)
        val containers = mapOf(
            "surfaceContainerLowest" to scheme.surfaceContainerLowest,
            "surfaceContainerLow" to scheme.surfaceContainerLow,
            "surfaceContainer" to scheme.surfaceContainer,
            "surfaceContainerHigh" to scheme.surfaceContainerHigh,
            "surfaceContainerHighest" to scheme.surfaceContainerHighest
        )
        for ((role, color) in containers) {
            // Slate tones are blue-dominant with green above red; the baseline purple-grey has red above green.
            assertTrue("$role $color is not a slate tone", color.blue > color.red && color.green > color.red)
        }
    }

    @Test
    fun uncheckedSwitchHandleAndBorderMeetThreeToOneNonTextContrast() {
        val scheme = OculaDarkColorScheme
        // Unchecked M3 Switch: handle and border use outline, the track uses surfaceContainerHighest, and the
        // app's switches sit on surface-coloured cards. OutlinedTextField borders in dialogs also use outline.
        val pairs = mapOf(
            "handle on track" to (scheme.outline to scheme.surfaceContainerHighest),
            "border on surface" to (scheme.outline to scheme.surface),
            "border on background" to (scheme.outline to scheme.background),
            "border in dialog" to (scheme.outline to scheme.surfaceContainerHigh)
        )
        for ((label, colors) in pairs) {
            val ratio = contrastRatio(colors.first, colors.second)
            assertTrue("$label contrast $ratio is below 3:1", ratio >= 3.0)
        }
    }

    private fun contrastRatio(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
