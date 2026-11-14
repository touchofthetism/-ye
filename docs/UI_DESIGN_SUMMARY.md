# Æye redesign: one-page summary for the product owner

Status: proposed design, waiting for your approval. Nothing has been built or changed in the app yet.
Full specification: `docs/UI_DESIGN.md` (section 10 is written for you; the rest is for the engineers).

## What changes

- **The app opens on the Speak screen.** It shows only the tiles, the last thing that was said, a small status
  ("Tracking", "No face", "Camera off") and one Menu button. Tiles are much larger: four to begin with, nine once a
  full calibration has measured good accuracy.
- **A guided first run with a helper.** Six short steps: agree to the privacy page, turn on the camera (with a
  picture to line up the face), 15-second face scan, one-minute calibration, optional tile phrases, backup choice.
  Steps can be skipped and done later. People who already use Æye see this once, with "Keep and continue".
- **A calmer pointer.** A thin ring, only where gaze does something. No numbers, no trail, never on top of text.
- **One message at a time, in plain words.** Messages go away by themselves and never move the tiles.
- **Honest wording.** "Not calibrated", "Not checked", "Accuracy limited", "Not measured", "Simulator". No number is
  shown that was not measured. No device-name claims.
- **Same behaviour underneath.** How selection works (holding a look, a long blink, raised eyebrows), all safety
  rules, the calibration method and everything already saved stay as they are.

## The screens

| Screen | Who uses it | What it is for |
|---|---|---|
| Sign in | helper | Sign in with Google |
| First-time setup (6 steps) | helper with the user | Privacy, camera, face scan, calibration, phrases, backup |
| **Speak** (home) | user | Look at a tile to say its phrase |
| Menu | helper | The way to everything below |
| Read, Past readings, Reading result | user reads; helper starts and stops | Read a short text; see time, pace and where the user looked |
| Calibrate | helper with the user | Full calibration, quick recalibration, saved calibrations |
| Recenter | helper with the user | A quick fix when tiles feel shifted |
| Face scan | helper with the user | Teach Æye this person's blinks and eyebrows; remove the scan |
| Tiles | helper | Change what each tile says |
| Settings, Eyebrow setup | helper | How a tile is selected, how fast, voice test |
| Privacy & account | helper | Backup on or off, delete cloud copies, erase everything, sign out |
| About | helper | Version, how selection works, privacy note |
| Developer (hidden) | engineers | Live numbers, camera picture, simulator, test tools |

## What moves to developer mode

Frame rate and camera resolution, the camera picture with face markings, eye, head and brightness numbers, the
pointer's coordinates and heat trail, the simulator and its controls, the posture and filter choices, the built-in
test run, account identifiers and storage paths, and the technical message log. Developer mode is opened from About
by tapping the version line seven times and confirming.

## What is removed, and why

| Removed | Why |
|---|---|
| The four tabs at the bottom | Replaced by the Speak home screen and a Menu. They used technical names and took space from the tiles. |
| The bar of numbers on top of every screen | Engineering information. It left the tiles about 40 % of the screen. Now in developer mode. |
| The cloud bar on top of three screens | A copy of what is on the Privacy page. |
| Two of the three copies of the speed settings | They disagreed with each other. One copy remains, in Settings. |
| The settings button and slider on the tile screen | Risk of stray touches next to the tiles. |
| Device-name badges and technical feature lists | Claims about a specific phone, and jargon. |
| The small map during calibration | It repeated the row of dots and was mistaken for a target. |

Nothing the app can do is lost. Every one of the 147 existing functions has a new place (section 6 of the spec).

## Questions for you

1. **Reading needs a helper.** Read is opened from the Menu by touch, and a reading is started and stopped by
   touch. A user cannot open it by gaze. Is that acceptable, or should the user be able to open Read by looking?
2. **Wording.** The nine default tile phrases and the three reading texts are our proposals (spec 2.10 and 2.5).
   Do you approve them, or will you supply your own?
3. **Recalibrate suggestion.** Today, tapping it starts calibration at once. In the design it opens the Calibrate
   page, so the helper can get the user into position first. OK?
4. **Erase everything** now also returns the app to first-time setup, including the privacy page. OK?
5. **Developer mode** is unlocked by seven taps on the version line, applies to the whole phone and survives
   "Erase everything". Is that enough protection, or should it need a code?
6. **Quick calibration** (5 dots, accuracy not checked, never unlocks nine tiles) stays as a secondary button for
   helpers. Keep it or remove it?
7. **Sign-in.** A Google account stays required before first use, as today. Please confirm.
8. **Languages.** English only at first; all texts are prepared for translation. Which languages are needed next?
