import test from 'node:test';
import assert from 'node:assert/strict';
import { SAMPLE_TEXT, SAMPLE_AI } from '../src/sample.js';
import { blocksFromText } from '../src/summary.js';
import { checkEvidence } from '../server/verify.js';

test('the prepared AI example passes the same evidence and number checks as real AI results', () => {
  const blocks = blocksFromText(SAMPLE_TEXT);
  // checkEvidence drops unsupported items, so an unchanged length means every example item is backed by its cited lines.
  assert.equal(checkEvidence(SAMPLE_AI, blocks).length, SAMPLE_AI.length);
});
