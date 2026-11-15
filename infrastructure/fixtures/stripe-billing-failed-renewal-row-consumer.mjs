/** Hosted-only bridge from the genuine composition. Input contains actual SQL and signed deliveries. */
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {validateBillingFailedRenewalEvidence} from '../stripe-billing-failed-renewal-evidence.mjs';

try {
  const chunks = [];
  let bytes = 0;
  for await (const chunk of process.stdin) {
    bytes += chunk.length;
    if (bytes > 262144) throw new Error('input_limit');
    chunks.push(chunk);
  }
  const {scope, configuration, settlement, row, queuedRetryRow, retryEvent, delivered} =
    JSON.parse(Buffer.concat(chunks).toString('utf8'));
  assert.equal(delivered.length, 5);
  const inspection = {
    count: delivered.length,
    duplicates: delivered.length - new Set(delivered.map(event => event.event_id)).size,
    events: delivered.map(event => {
      const canonical = JSON.stringify(Object.fromEntries(Object.keys(event).sort().map(key => [key, event[key]])));
      return {event_id: event.event_id, event_type: event.event_type, count: 1,
        sha256: createHash('sha256').update(canonical).digest('hex')};
    }),
  };
  const validate = candidate => validateBillingFailedRenewalEvidence(scope, candidate, inspection, configuration, settlement);
  assert.deepEqual(validate(row), {ownedVerified: true, cashVerified: true, journalVerified: true, signedCallbacksVerified: true});
  const marker = queuedRetryRow.callbacks.find(event => event.id === retryEvent);
  assert.equal(marker.delivered, null);
  assert.equal(queuedRetryRow.callbacks.filter(event => event.delivered === true).length, 4);
  assert.deepEqual(queuedRetryRow.proof, row.proof);
  assert.deepEqual(queuedRetryRow.lineage, row.lineage);
  for (const event of queuedRetryRow.callbacks)
    assert.deepEqual(event.payload, row.callbacks.find(original => original.id === event.id).payload);
  assert.throws(() => validate(queuedRetryRow));
  process.stdout.write('combined_actual_sql_and_signed_union_verified\n');
} catch {
  process.stderr.write('failed_renewal_actual_evidence_rejected\n');
  process.exitCode = 1;
}
