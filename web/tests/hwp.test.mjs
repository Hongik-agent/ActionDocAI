import test from 'node:test';
import assert from 'node:assert/strict';
import CFB from 'cfb';
import { deflateRaw } from 'pako';
import { extractHwp } from '../src/hwp.js';
import { readFileSync } from 'node:fs';
import { hwpFixture } from './fixtures.mjs';
test('compressed and plain HWP preserve Korean text', () => {
  for (const flag of [0, 1]) assert.ok(extractHwp(hwpFixture(undefined, flag)).includes('지역 중소기업'));
});
test('protected, invalid and malformed HWP fail explicitly', () => {
  assert.throws(() => extractHwp(new Uint8Array(512)));
  for (const flag of [3, 5, 17, 257, 1025]) assert.throws(() => extractHwp(hwpFixture('비밀', flag)), /보호/);
  assert.throws(() => extractHwp(hwpFixture('줄바꿈\u0001')), /읽지 못/);
});
test('bounded decompression rejects an oversized body', () => {
  const ole = CFB.utils.cfb_new(); const header = new Uint8Array(256); header.set(new TextEncoder().encode('HWP Document File')); header[35] = 5; header[36] = 1;
  CFB.utils.cfb_add(ole, 'FileHeader', header); CFB.utils.cfb_add(ole, 'BodyText/Section0', deflateRaw(new Uint8Array(33 * 1024 * 1024)));
  assert.throws(() => extractHwp(new Uint8Array(CFB.write(ole, { type: 'array' }))), /32MB/);
});
test('real public HWP fixture', { skip: !process.env.ACTIONDOC_HWP_FIXTURE }, () => {
  assert.ok(extractHwp(new Uint8Array(readFileSync(process.env.ACTIONDOC_HWP_FIXTURE))).includes('국민건강정보자료'));
});
