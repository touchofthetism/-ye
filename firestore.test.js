const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const firebase = require("firebase/compat/app");
require("firebase/compat/firestore");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

// The rules require createdAt/updatedAt == request.time, i.e. server timestamps, on every write.
const serverNow = () => firebase.firestore.FieldValue.serverTimestamp();

function validCalibrationPayload(userId, now = serverNow()) {
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

function validSessionPayload(userId, now = serverNow()) {
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

function calibrations(db, uid) {
  return db.collection("users").doc(uid).collection("calibrations");
}

function sessions(db, uid) {
  return db.collection("users").doc(uid).collection("sessions");
}

async function seedAliceDocs() {
  const now = new Date();
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const adminDb = context.firestore();
    await calibrations(adminDb, ALICE_UID).doc("cal_1").set(validCalibrationPayload(ALICE_UID, now));
    await sessions(adminDb, ALICE_UID).doc("sess_1").set(validSessionPayload(ALICE_UID, now));
  });
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
  await assertFails(calibrations(unauthDb, ALICE_UID).get());
  await assertFails(sessions(unauthDb, ALICE_UID).get());
  await assertFails(calibrations(unauthDb, ALICE_UID).doc("cal_1").set(validCalibrationPayload(ALICE_UID)));
  await assertFails(sessions(unauthDb, ALICE_UID).doc("sess_1").set(validSessionPayload(ALICE_UID)));
});

test("Authenticated owner: can create, read, query, update, and delete valid calibration profile", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const docRef = calibrations(aliceDb, ALICE_UID).doc("cal_1");

  await assertSucceeds(docRef.set(validCalibrationPayload(ALICE_UID)));
  await assertSucceeds(docRef.get());
  await assertSucceeds(calibrations(aliceDb, ALICE_UID).where("userId", "==", ALICE_UID).get());
  await assertSucceeds(
    docRef.update({
      isActive: false,
      name: "Updated Desk Profile",
      updatedAt: serverNow(),
    })
  );
  await assertSucceeds(docRef.delete());
});

test("Authenticated owner: largest valid session (12 section loads, 40 cluster points) is accepted", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const payload = validSessionPayload(ALICE_UID);
  payload.sectionLoads = Array.from({ length: 12 }, (_, i) => `sec_${i + 1}:${"S".repeat(100)}:8`);
  payload.clusterPoints = Array.from({ length: 40 }, (_, i) => `0.${i},0.5,${100 + i}`);
  await assertSucceeds(sessions(aliceDb, ALICE_UID).doc("sess_max").set(payload));
});

test("Spec #2: another user cannot get, list, create, update, or delete the owner's documents", async () => {
  await seedAliceDocs();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertFails(calibrations(bobDb, ALICE_UID).doc("cal_1").get());
  await assertFails(sessions(bobDb, ALICE_UID).doc("sess_1").get());
  await assertFails(calibrations(bobDb, ALICE_UID).where("userId", "==", ALICE_UID).get());
  await assertFails(sessions(bobDb, ALICE_UID).where("userId", "==", ALICE_UID).get());
  await assertFails(calibrations(bobDb, ALICE_UID).doc("cal_bob").set(validCalibrationPayload(BOB_UID)));
  await assertFails(calibrations(bobDb, ALICE_UID).doc("cal_bob2").set(validCalibrationPayload(ALICE_UID)));
  await assertFails(calibrations(bobDb, ALICE_UID).doc("cal_1").update({ isActive: false, updatedAt: serverNow() }));
  await assertFails(sessions(bobDb, ALICE_UID).doc("sess_1").update({ title: "Hijacked", updatedAt: serverNow() }));
  await assertFails(calibrations(bobDb, ALICE_UID).doc("cal_1").delete());
  await assertFails(sessions(bobDb, ALICE_UID).doc("sess_1").delete());
  // Absent-document allowances never extend to another user's path.
  await assertFails(calibrations(bobDb, ALICE_UID).doc("missing").delete());
  await assertFails(calibrations(bobDb, ALICE_UID).doc("missing").get());
});

test("Spec #3: identity spoofing on create is rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(calibrations(aliceDb, ALICE_UID).doc("cal_spoof").set(validCalibrationPayload(BOB_UID)));
  await assertFails(sessions(aliceDb, ALICE_UID).doc("sess_spoof").set(validSessionPayload(BOB_UID)));
});

test("Spec #4 and #5: ghost fields are rejected on create and update", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ghostCreate = { ...validCalibrationPayload(ALICE_UID), isAdmin: true };
  await assertFails(calibrations(aliceDb, ALICE_UID).doc("cal_ghost").set(ghostCreate));
  const ghostSession = { ...validSessionPayload(ALICE_UID), isAdmin: true };
  await assertFails(sessions(aliceDb, ALICE_UID).doc("sess_ghost").set(ghostSession));

  const docRef = calibrations(aliceDb, ALICE_UID).doc("cal_1");
  await assertSucceeds(docRef.set(validCalibrationPayload(ALICE_UID)));
  await assertFails(docRef.update({ isActive: false, ghostField: "hacked", updatedAt: serverNow() }));
  await assertFails(docRef.update({ verified: true, updatedAt: serverNow() }));
});

test("Spec #6: matrixCoeffs must be exactly 12 numbers, checked at every index", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const col = calibrations(aliceDb, ALICE_UID);

  const tooShort = validCalibrationPayload(ALICE_UID);
  tooShort.matrixCoeffs = [1.0, 2.0, 3.0];
  await assertFails(col.doc("cal_short").set(tooShort));

  const tooLong = validCalibrationPayload(ALICE_UID);
  tooLong.matrixCoeffs = Array.from({ length: 500 }, () => 0.1);
  await assertFails(col.doc("cal_long").set(tooLong));

  for (const index of [1, 6, 11]) {
    const mixed = validCalibrationPayload(ALICE_UID);
    mixed.matrixCoeffs = mixed.matrixCoeffs.slice();
    mixed.matrixCoeffs[index] = "evil";
    await assertFails(col.doc(`cal_mixed_${index}`).set(mixed));
  }

  const nested = validCalibrationPayload(ALICE_UID);
  nested.matrixCoeffs = nested.matrixCoeffs.slice();
  nested.matrixCoeffs[5] = { x: 1 };
  await assertFails(col.doc("cal_nested").set(nested));
});

test("Spec #7: oversized strings are rejected, including list elements past index 0", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const longName = validCalibrationPayload(ALICE_UID);
  longName.name = "x".repeat(5000);
  await assertFails(calibrations(aliceDb, ALICE_UID).doc("cal_long_name").set(longName));

  const longTitle = validSessionPayload(ALICE_UID);
  longTitle.title = "x".repeat(5000);
  await assertFails(sessions(aliceDb, ALICE_UID).doc("sess_long_title").set(longTitle));

  const longSection = validSessionPayload(ALICE_UID);
  longSection.sectionLoads = ["sec_1:ok:50", "x".repeat(121)];
  await assertFails(sessions(aliceDb, ALICE_UID).doc("sess_long_section").set(longSection));

  const longCluster = validSessionPayload(ALICE_UID);
  longCluster.clusterPoints = ["0.1,0.2,300", "0.2,0.3,400", "x".repeat(81)];
  await assertFails(sessions(aliceDb, ALICE_UID).doc("sess_long_cluster").set(longCluster));
});

test("List elements must be strings at every index", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const col = sessions(aliceDb, ALICE_UID);

  const numberCluster = validSessionPayload(ALICE_UID);
  numberCluster.clusterPoints = ["0.1,0.2,300", 42];
  await assertFails(col.doc("sess_num_cluster").set(numberCluster));

  const lastCluster = validSessionPayload(ALICE_UID);
  lastCluster.clusterPoints = Array.from({ length: 40 }, (_, i) => (i === 39 ? 7 : `0.1,0.2,${i}`));
  await assertFails(col.doc("sess_last_cluster").set(lastCluster));

  const mapSection = validSessionPayload(ALICE_UID);
  mapSection.sectionLoads = ["sec_1:ok:50", { x: [1, 2, 3] }];
  await assertFails(col.doc("sess_map_section").set(mapSection));
});

test("Spec #8: invalid contentType enum is rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const payload = validSessionPayload(ALICE_UID);
  payload.contentType = "SYSTEM_HACK";
  await assertFails(sessions(aliceDb, ALICE_UID).doc("sess_enum").set(payload));
});

test("Spec #9: client-supplied timestamps are rejected (future or backdated)", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const future = new Date("2099-01-01T00:00:00Z");
  await assertFails(calibrations(aliceDb, ALICE_UID).doc("cal_future").set(validCalibrationPayload(ALICE_UID, future)));
  await assertFails(sessions(aliceDb, ALICE_UID).doc("sess_future").set(validSessionPayload(ALICE_UID, future)));

  const past = new Date("2020-01-01T00:00:00Z");
  await assertFails(calibrations(aliceDb, ALICE_UID).doc("cal_past").set(validCalibrationPayload(ALICE_UID, past)));

  const docRef = calibrations(aliceDb, ALICE_UID).doc("cal_1");
  await assertSucceeds(docRef.set(validCalibrationPayload(ALICE_UID)));
  await assertFails(docRef.update({ isActive: false, updatedAt: past }));
  await assertFails(docRef.update({ isActive: false }));
});

test("Spec #10: createdAt and userId are immutable on update", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const calRef = calibrations(aliceDb, ALICE_UID).doc("cal_1");
  await assertSucceeds(calRef.set(validCalibrationPayload(ALICE_UID)));
  await assertFails(calRef.update({ createdAt: serverNow(), updatedAt: serverNow() }));
  await assertFails(calRef.update({ userId: BOB_UID, updatedAt: serverNow() }));

  const sessRef = sessions(aliceDb, ALICE_UID).doc("sess_1");
  await assertSucceeds(sessRef.set(validSessionPayload(ALICE_UID)));
  await assertFails(sessRef.update({ createdAt: serverNow(), updatedAt: serverNow() }));
});

test("Spec #11: isActive type poisoning on update is rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const docRef = calibrations(aliceDb, ALICE_UID).doc("cal_1");
  await assertSucceeds(docRef.set(validCalibrationPayload(ALICE_UID)));
  await assertFails(docRef.update({ isActive: "yes", updatedAt: serverNow() }));
});

test("Spec #12: clusterPoints with 100 items and sectionLoads with 13 items are rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const clusters = validSessionPayload(ALICE_UID);
  clusters.clusterPoints = Array.from({ length: 100 }, (_, i) => `0.1,0.2,${i}`);
  await assertFails(sessions(aliceDb, ALICE_UID).doc("sess_clusters").set(clusters));

  const loads = validSessionPayload(ALICE_UID);
  loads.sectionLoads = Array.from({ length: 13 }, (_, i) => `sec_${i}:x:1`);
  await assertFails(sessions(aliceDb, ALICE_UID).doc("sess_loads").set(loads));
});

test("Blink settings that empty the command window or invert the hysteresis are rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const col = calibrations(aliceDb, ALICE_UID);

  const longMin = validCalibrationPayload(ALICE_UID);
  longMin.intentionalBlinkMinMs = 1000;
  await assertFails(col.doc("cal_long_blink").set(longMin));

  const inverted = validCalibrationPayload(ALICE_UID);
  inverted.closeBlinkThreshold = 0.7;
  inverted.openBlinkThreshold = 0.5;
  await assertFails(col.doc("cal_inverted").set(inverted));

  const edge = validCalibrationPayload(ALICE_UID);
  edge.intentionalBlinkMinMs = 700;
  edge.closeBlinkThreshold = 0.45;
  edge.openBlinkThreshold = 0.65;
  await assertSucceeds(col.doc("cal_edge").set(edge));
});

test("Owner: get and delete of a missing document are allowed (idempotent delete)", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const missingCal = calibrations(aliceDb, ALICE_UID).doc("never_created");
  const snap = await assertSucceeds(missingCal.get());
  if (snap.exists) {
    throw new Error("expected a missing document");
  }
  await assertSucceeds(missingCal.delete());
  await assertSucceeds(sessions(aliceDb, ALICE_UID).doc("never_created").delete());

  const docRef = calibrations(aliceDb, ALICE_UID).doc("cal_twice");
  await assertSucceeds(docRef.set(validCalibrationPayload(ALICE_UID)));
  await assertSucceeds(docRef.delete());
  await assertSucceeds(docRef.delete());
});

test("Authenticated owner: can create, query, rename, and delete tracking session", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const sessRef = sessions(aliceDb, ALICE_UID).doc("sess_1");

  await assertSucceeds(sessRef.set(validSessionPayload(ALICE_UID)));
  await assertSucceeds(sessions(aliceDb, ALICE_UID).where("userId", "==", ALICE_UID).get());
  await assertSucceeds(sessRef.update({ title: "Renamed Session", updatedAt: serverNow() }));
  await assertFails(sessRef.update({ durationSeconds: 999, updatedAt: serverNow() }));
  await assertSucceeds(sessRef.delete());
});

test("Owner: batched exclusive activation (one profile on, others off) is accepted", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const col = calibrations(aliceDb, ALICE_UID);
  await assertSucceeds(col.doc("cal_old").set(validCalibrationPayload(ALICE_UID)));
  await assertSucceeds(col.doc("cal_new").set(validCalibrationPayload(ALICE_UID)));

  const batch = aliceDb.batch();
  batch.update(col.doc("cal_old"), { isActive: true, updatedAt: serverNow() });
  batch.update(col.doc("cal_new"), { isActive: false, updatedAt: serverNow() });
  await assertSucceeds(batch.commit());
});

test("Calibration: optional heldOutValidated / calibrationPosture are typed on create and immutable on update", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const col = calibrations(aliceDb, ALICE_UID);

  // The repository writes both keys; older documents without them stay valid.
  const validated = { ...validCalibrationPayload(ALICE_UID), heldOutValidated: true, calibrationPosture: "RECLINING" };
  await assertSucceeds(col.doc("cal_validated").set(validated));
  await assertSucceeds(col.doc("cal_legacy").set(validCalibrationPayload(ALICE_UID)));
  for (const posture of ["UPRIGHT", "LYING_DOWN"]) {
    await assertSucceeds(col.doc(`cal_${posture}`).set({ ...validCalibrationPayload(ALICE_UID), heldOutValidated: false, calibrationPosture: posture }));
  }

  await assertFails(col.doc("cal_bad_flag").set({ ...validCalibrationPayload(ALICE_UID), heldOutValidated: "yes" }));
  await assertFails(col.doc("cal_bad_posture").set({ ...validCalibrationPayload(ALICE_UID), calibrationPosture: "HANDSTAND" }));
  await assertFails(col.doc("cal_num_posture").set({ ...validCalibrationPayload(ALICE_UID), calibrationPosture: 1 }));

  // A saved profile cannot be flipped to "validated" (or re-postured) later; activation still works.
  const unvalidatedRef = col.doc("cal_UPRIGHT");
  await assertFails(unvalidatedRef.update({ heldOutValidated: true, updatedAt: serverNow() }));
  await assertFails(unvalidatedRef.update({ calibrationPosture: "RECLINING", updatedAt: serverNow() }));
  await assertFails(col.doc("cal_legacy").update({ heldOutValidated: true, updatedAt: serverNow() }));
  await assertSucceeds(col.doc("cal_validated").update({ isActive: false, updatedAt: serverNow() }));
});
