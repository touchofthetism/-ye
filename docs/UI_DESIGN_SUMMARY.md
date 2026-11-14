# Æye redesign: one-page summary for the product owner

Status: approved on 14 Nov 2026; the product owner handed the open decisions to engineering. Being built now.
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
test run, account identifiers and storage paths, and the technical message log. Developer mode is, for now, a plain switch on the About
page (on by default in test builds).

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

## Decisions taken (14 Nov 2026)

The eight open questions were decided by engineering at the owner's request (spec section 11):

1. Reading is opened, started and stopped by touch.
2. The proposed tile phrases and reading texts are used as written.
3. The recalibrate suggestion opens the Calibrate page.
4. "Erase everything" returns to first-time setup.
5. Developer tools are **not hidden for now**: a plain switch on the About page, on by default in test builds.
6. Quick calibration stays as a secondary button.
7. Google sign-in stays required.
8. English only for now.

New: **Share diagnostics** (About page and Developer page). It creates a file of tracking and calibration numbers and
opens the phone's share sheet. No pictures, no account details. It is only made when the button is pressed.
