package com.example.ui.viewmodel

/*
 * UI contract types of the redesign (docs/UI_DESIGN.md, section 7.1). Shared by the view model and every screen.
 * Pure Kotlin: no Android or Compose imports.
 */

/** Where the gaze input comes from right now, as one value instead of four separate checks. */
enum class GazeInputState { NO_CAMERA_PERMISSION, NO_FRAMES, SIMULATED, NO_FACE, EYES_CLOSED, TRACKING }

/** The live detector windows, so on-screen copy states what the detectors really use. */
data class SelectionTimings(
    val naturalBlinkMinMs: Long = 80,
    val commandBlinkMinMs: Long = 350,
    val commandBlinkMaxMs: Long = 800,
    val browHoldMs: Long = 400
)

/** How a tile was fired. */
enum class SpokenMethod { HELD, BLINK, EYEBROWS, TAPPED, HELPER }

/** The last tile that was fired (set beside the legacy `lastTriggeredSpeech` string). */
data class LastSpoken(
    val tileId: String,
    val phrase: String,
    val method: SpokenMethod,
    val atMs: Long,
    val isRecenter: Boolean
)

/**
 * One constant per key of UI_DESIGN.md 3.7. [DEV_LOG] means the message has no end-user notice: it is only
 * kept in the technical message log.
 */
enum class NoticeKey {
    DEV_LOG,
    BROW_NO_FACE,
    BROW_RELAXED_RECORDED,
    BROW_RAISE_TOO_SMALL,
    BROW_RAISED_RECORDED,
    CALIBRATION_SAVED,
    CALIBRATION_SAVED_BACKED_UP,
    CALIBRATION_SAVED_SIMULATED,
    CALIBRATION_BACKUP_FAILED,
    PROFILE_IN_USE,
    PROFILE_DELETED,
    PROFILE_DELETE_CLOUD_FAILED,
    READING_SAVED,
    READING_SAVED_BACKED_UP,
    READING_SAVED_SIMULATED,
    READING_DELETED,
    RECENTER_DONE,
    RECENTER_FAILED,
    SCAN_DONE,
    SCAN_FAILED,
    SCAN_CANCELLED,
    SCAN_NO_RESULT,
    SCAN_REMOVED,
    SCAN_STOPPED_FOR_CALIBRATION,
    TILE_UPDATED,
    TILES_RESET,
    CLOUD_DELETE_DONE,
    CLOUD_DELETE_FAILED,
    ERASE_DONE,
    ERASE_CLOUD_FAILED
}

/** A view-model message with its key, its arguments for the plain copy, and the unchanged technical sentence. */
data class UserNotice(
    val key: NoticeKey,
    val args: List<String>,
    val technicalText: String,
    val atMs: Long
)

/** Name prefix of calibrations and readings made from simulated frames. They are never synced. */
const val SIMULATED_RECORD_PREFIX = "[SIMULATED] "
