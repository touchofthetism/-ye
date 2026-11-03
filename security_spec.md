# Security Specification — OculaGaze Firestore Rules

## 1. Data Invariants
1. **Strict User Ownership Isolation**: Every document in `/users/{userId}/calibrations/{profileId}` and `/users/{userId}/sessions/{sessionId}` can only be read, created, updated, or deleted when `request.auth != null && request.auth.uid == userId`, and the document's `userId` field must equal `userId` (`request.auth.uid`).
2. **Immutable Identity & Creation Timestamp**: On update, `incoming().userId == existing().userId` and `incoming().createdAt == existing().createdAt`.
3. **Bounded Native Lists (No Unbounded Arrays or JSON Blobs)**:
   - `CalibrationProfile.matrixCoeffs` is a native list of numbers with exact length `12`.
   - `TrackingSession.sectionLoads` is a native list of strings bounded to `size() <= 12`.
   - `TrackingSession.clusterPoints` is a native list of strings bounded to `size() <= 40`.
4. **Strict Key Allowlisting (`hasAll` & `hasOnly`)**: Ghost fields are rejected on both `create` and `update`.

## 2. The "Dirty Dozen" Adversarial Payloads
1. **Unauthenticated Read**: `unauthDb.collection("users/alice/calibrations").get()` -> Reject (`request.auth != null`).
2. **Cross-User Read (PII/Biometric Isolation)**: `bobDb.doc("users/alice/calibrations/p1").get()` -> Reject (`request.auth.uid == userId`).
3. **Identity Spoofing on Create**: Alice writes to `users/alice/calibrations/p1` with `userId: "bob"` -> Reject (`incoming().userId == request.auth.uid`).
4. **Shadow/Ghost Field Injection on Create**: Payload includes `"isAdmin": true` -> Reject (`keys().hasOnly(...)`).
5. **Shadow/Ghost Field Injection on Update**: Update payload adds `"verified": true` -> Reject (`affectedKeys().hasOnly(...)`).
6. **Matrix Dimension Poisoning**: `matrixCoeffs` with 3 elements or 500 elements -> Reject (`matrixCoeffs.size() == 12`).
7. **String Size Exhaustion (Denial of Wallet)**: `name` with 5,000 characters -> Reject (`name.size() <= 80`).
8. **Invalid Enum Value**: `contentType: "SYSTEM_HACK"` -> Reject (`contentType in ['READING_SECTIONS', 'AAC_CONTROL_DECK']`).
9. **Future Timestamp Spoofing**: `createdAt` set to year 2099 -> Reject (`createdAt <= request.time`).
10. **Immutable Field Mutation on Update**: Updating `createdAt` or `userId` on an existing calibration profile -> Reject.
11. **Value Type Poisoning on Update**: Updating `isActive` to `"yes"` (string instead of bool) -> Reject (`isValidCalibrationProfile(incoming())`).
12. **Unbounded Cluster Array**: `clusterPoints` with 100 items -> Reject (`clusterPoints.size() <= 40`).
