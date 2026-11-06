package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.CalibrationProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Round-trip tests: what the repository writes must read back identically through Firestore's
 * reflection-based class mapper. Each test signs in a fresh user so leftover emulator data from
 * earlier runs cannot change the outcome.
 */
class EyeTrackingRepositoryRoundTripTest : FirestoreEmulatorTestBase() {

    private fun freshEmail() = "roundtrip_${UUID.randomUUID().toString().take(8)}@oculagaze.test"

    @Test
    fun inactiveCalibrationProfile_readsBackInactive() = runBlocking {
        val uid = signInTestUser(freshEmail())
        val repository = EyeTrackingRepository(firestore, auth)
        val profileId = "cal_${UUID.randomUUID().toString().replace("-", "").take(12)}"

        val created = withTimeout(TIMEOUT_MS) {
            repository.createCalibrationProfile(
                CalibrationProfile(id = profileId, userId = uid, name = "Inactive Profile", isActive = false)
            )
        }
        assertTrue("create failed: ${created.exceptionOrNull()}", created.isSuccess)

        val fetched = withTimeout(TIMEOUT_MS) { repository.getCalibrationProfileById(uid, profileId).getOrThrow() }
        assertNotNull(fetched)
        assertFalse("isActive=false must survive a Firestore round trip", fetched!!.isActive)

        val observed = withTimeout(TIMEOUT_MS) {
            repository.observeCalibrationProfiles(uid).first { list -> list.any { it.id == profileId } }
        }
        assertFalse(observed.first { it.id == profileId }.isActive)
    }

    @Test
    fun toggledActiveState_readsBackToggled() = runBlocking {
        val uid = signInTestUser(freshEmail())
        val repository = EyeTrackingRepository(firestore, auth)
        val profileId = "cal_${UUID.randomUUID().toString().replace("-", "").take(12)}"

        withTimeout(TIMEOUT_MS) {
            repository.createCalibrationProfile(
                CalibrationProfile(id = profileId, userId = uid, name = "Toggle Profile", isActive = true)
            ).getOrThrow()
            repository.updateCalibrationActiveState(profileId, false).getOrThrow()
        }
        val fetched = withTimeout(TIMEOUT_MS) { repository.getCalibrationProfileById(uid, profileId).getOrThrow() }
        assertEquals(false, fetched?.isActive)
    }

    private companion object {
        const val TIMEOUT_MS = 5000L
    }
}
