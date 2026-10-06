// Physical consumer observations, kept separate from the direct sandbox probe.
import {readFileSync} from 'node:fs';
import assert from 'node:assert/strict';
import test from 'node:test';
const proof = JSON.parse(readFileSync(new URL('../../../../docs/v2/evidence/phase6-20260928/acceptance.json', import.meta.url)));
test('Kotlin logic and UI activate on the same accepted APK and rollback together', () => {
  assert.equal(proof.status, 'PASS');
  assert.equal(proof.installedBeforeSha256, proof.installedAfterSha256);
  assert.equal(proof.reinstallDuringAcceptedSequence, false);
  assert.equal(proof.dataClear, false);
  assert.equal(proof.manualAcceptanceJS, false);
  assert.equal(proof.manualFunctionIds, false);
  assert.equal(proof.stages.business.text[0], 'Discount: 20');
  for (const name of ['combined', 'online', 'offline', 'recovered-combined'])
    assert.equal(proof.stages[name].text[0], 'Special discount: 20');
  for (const name of ['rollback', 'rollback-offline'])
    assert.deepEqual(proof.stages[name].text, proof.stages.baseline.text);
  assert(proof.recomposition.includes('Count: 1'));
  for (const stage of Object.values(proof.stages)) {
    assert.equal(stage.lastUpdateTime, proof.lastUpdateTime);
    assert.equal(stage.fatalException, false);
    assert(stage.text.includes('Member'));
    assert(stage.text.includes('Member explicit'));
  }
  for (const receipt of Object.values(proof.signedReleases)) assert.equal(receipt.signatureVerified, true);
});
test('signed adversarial artifacts reject before remote health or native effects', () => {
  assert.equal(proof.signedNegativeArtifacts.length, 12);
  for (const result of proof.signedNegativeArtifacts) {
    assert.equal(result.rejected, true, result.case);
    assert.equal(result.manifestSignatureVerified, true, result.case);
    assert.equal(result.nativeRowsPreserved, true, result.case);
    assert.equal(result.nativeText, 'Discount: 10', result.case);
    assert.equal(result.healthAcknowledged, false, result.case);
  }
  assert.equal(proof.invalidManifestSignatureRejected, true);
  assert.equal(proof.invalidDirectiveSignatureRejected, true);
});
