# Security Specification — OculaGaze Firestore Rules

## 1. Data Invariants
1. **Strict User Ownership Isolation**: Every document in `/users/{userId}/calibrations/{profileId}` and `/users/{userId}/sessions/{sessionId}` can only be read, created, updated, or deleted when `request.auth != null && request.auth.uid == userId`, and the document's `userId` field must equal `userId` (`request.auth.uid`). A `get` or `delete` of a document that does not exist is allowed on the owner's own path only (`resource == null`), so deletes are idempotent and a missing profile reads as "not found".
2. **Immutable Identity & Creation Timestamp**: On update, `incoming().userId == existing().userId` and `incoming().createdAt == existing().createdAt`.
3. **Server Timestamps Only**: On create, `createdAt == request.time` and `updatedAt == request.time`; on update, `updatedAt == request.time`. Clients must write `FieldValue.serverTimestamp()`; client clocks cannot backdate or postdate records.
4. **Bounded Native Lists, Checked at Every Element (No Unbounded Arrays or JSON Blobs)**:
   - `CalibrationProfile.matrixCoeffs` is a native list of exactly `12` elements, each of which is a number.
   - `TrackingSession.sectionLoads` is a native list of at most `12` elements, each a string of at most `120` characters.
   - `TrackingSession.clusterPoints` is a native list of at most `40` elements, each a string of at most `80` characters.
   - String lists are validated with a join/split round trip on U+001F plus an anchored regex (an unrolled per-index check exceeds the 1,000-expression rules budget). Elements must therefore not contain U+001F; the client never writes it.
5. **Usable Blink Settings**: `closeBlinkThreshold + 0.05 <= openBlinkThreshold` (non-inverted hysteresis) and `150 <= intentionalBlinkMinMs <= 700`, so the command-blink window `intentionalBlinkMinMs..800 ms` is never empty.
6. **Strict Key Allowlisting (`hasAll` & `hasOnly`)**: Ghost fields are rejected on both `create` and `update`.

## 2. The "Dirty Dozen" Adversarial Payloads
Each item is exercised by `firestore.test.js`.
1. **Unauthenticated Read**: `unauthDb.collection("users/alice/calibrations").get()` -> Reject (`request.auth != null`).
2. **Cross-User Access (PII/Biometric Isolation)**: Bob's `get`, `list` (`where userId == alice`), `create`, `update` or `delete` on `users/alice/...`, including a delete of a missing document there -> Reject (`request.auth.uid == userId`).
3. **Identity Spoofing on Create**: Alice writes to `users/alice/calibrations/p1` with `userId: "bob"` -> Reject (`incoming().userId == request.auth.uid`).
4. **Shadow/Ghost Field Injection on Create**: Payload includes `"isAdmin": true` -> Reject (`keys().hasOnly(...)`).
5. **Shadow/Ghost Field Injection on Update**: Update payload adds `"verified": true` -> Reject (`affectedKeys().hasOnly(...)`).
6. **Matrix Dimension and Element Poisoning**: `matrixCoeffs` with 3 or 500 elements, or 12 elements with a string or map at any index -> Reject (`size() == 12` and `is number` for every index).
7. **String Size Exhaustion (Denial of Wallet)**: `name` or `title` with 5,000 characters, or any `sectionLoads`/`clusterPoints` element (not only the first) over its length bound -> Reject.
8. **Invalid Enum Value**: `contentType: "SYSTEM_HACK"` -> Reject (`contentType in ['READING_SECTIONS', 'AAC_CONTROL_DECK']`).
9. **Timestamp Spoofing**: `createdAt` set to year 2099 or backdated to 2020, or an update without a server `updatedAt` -> Reject (`== request.time`).
10. **Immutable Field Mutation on Update**: Updating `createdAt` or `userId` on an existing calibration profile or session -> Reject.
11. **Value Type Poisoning on Update**: Updating `isActive` to `"yes"` (string instead of bool) -> Reject (`isValidCalibrationProfile(incoming())`).
12. **Unbounded or Mistyped Lists**: `clusterPoints` with 100 items, `sectionLoads` with 13 items, or a number/map element at any index -> Reject.

Additional cases: blink settings with `intentionalBlinkMinMs: 1000` or inverted thresholds (`close 0.7`, `open 0.5`) -> Reject; the largest valid session (12 section loads, 40 cluster points) and a batched exclusive activation (one profile on, the others off) -> Accept.
