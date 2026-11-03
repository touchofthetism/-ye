const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validCalibrationPayload(userId, now) {
  return {
    userId,
    name: "Pixel 10 Pro XL Desk Portrait",
    deviceModel: "Pixel 10 Pro XL",
    matrixCoeffs: [0.5, 0.28, 0.01, -0.22, 0.01, 0.0, 0.5, -0.01, 0.30, 0.01, -0.25, 0.0],
    baselineEyeDistancePx: 148.0,
    meanErrorMm: 13.2,
    meanErrorPct: 10.8,
    maxErrorPct: 14.1,
    closeBlinkThreshold: 0.25,
    openBlinkThreshold: 0.55,
    intentionalBlinkMinMs: 350,
    isActive: true,
    createdAt: now,
    updatedAt: now,
  };
}

function validSessionPayload(userId, now) {
  return {
    userId,
    title: "Cognitive Load Passage Analysis",
    deviceModel: "Pixel 10 Pro XL",
    contentType: "READING_SECTIONS",
    durationSeconds: 45,
    naturalBlinkCount: 11,
    intentionalBlinkCount: 2,
    blinksPerMinute: 14.6,
    effectiveWordsPerMinute: 185,
    fixationClusterCount: 8,
    topZonePercentage: 38,
    middleZonePercentage: 42,
    bottomZonePercentage: 20,
    sectionLoads: ["sec_1:Abstract & Hypothesis:38", "sec_2:Experimental Method:42", "sec_3:Findings:20"],
    clusterPoints: ["0.35,0.22,1200", "0.62,0.48,1650", "0.48,0.78,900"],
    createdAt: now,
    updatedAt: now,
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user: cannot read or create calibrations or sessions", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("calibrations").get()
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("sessions").get()
  );
});

test("Authenticated owner: can create, read, query, update, and delete valid calibration profile", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date();
  const docRef = aliceDb.collection("users").doc(ALICE_UID).collection("calibrations").doc("cal_1");

  await assertSucceeds(docRef.set(validCalibrationPayload(ALICE_UID, now)));
  await assertSucceeds(docRef.get());
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("calibrations")
      .where("userId", "==", ALICE_UID)
      .get()
  );
  await assertSucceeds(
    docRef.update({
      isActive: false,
      name: "Updated Desk Profile",
      updatedAt: new Date(),
    })
  );
  await assertSucceeds(docRef.delete());
});

test("Authenticated user: cannot read or modify another user's calibration or session", async () => {
  const now = new Date();
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const adminDb = context.firestore();
    await adminDb.collection("users").doc(ALICE_UID).collection("calibrations").doc("cal_1")
      .set(validCalibrationPayload(ALICE_UID, now));
    await adminDb.collection("users").doc(ALICE_UID).collection("sessions").doc("sess_1")
      .set(validSessionPayload(ALICE_UID, now));
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("calibrations").doc("cal_1").get()
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("sessions").doc("sess_1").get()
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("calibrations").doc("cal_1").delete()
  );
});

test("Shadow update & malformed matrix coefficients are rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date();
  const docRef = aliceDb.collection("users").doc(ALICE_UID).collection("calibrations").doc("cal_1");
  await assertSucceeds(docRef.set(validCalibrationPayload(ALICE_UID, now)));

  await assertFails(
    docRef.update({
      isActive: false,
      ghostField: "hacked",
      updatedAt: new Date(),
    })
  );

  const badMatrix = validCalibrationPayload(ALICE_UID, now);
  badMatrix.matrixCoeffs = [1.0, 2.0, 3.0];
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("calibrations").doc("cal_bad").set(badMatrix)
  );
});

test("Authenticated owner: can create, query, and delete tracking session", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date();
  const sessRef = aliceDb.collection("users").doc(ALICE_UID).collection("sessions").doc("sess_1");

  await assertSucceeds(sessRef.set(validSessionPayload(ALICE_UID, now)));
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("sessions")
      .where("userId", "==", ALICE_UID)
      .get()
  );
  await assertSucceeds(sessRef.delete());
});
