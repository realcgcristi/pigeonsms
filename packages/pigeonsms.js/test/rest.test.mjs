import assert from 'node:assert/strict';
import test from 'node:test';
import { normalizeApiBase } from '../src/rest.js';

test('normalizes API bases without a backtracking expression', () => {
  assert.equal(normalizeApiBase('https://api.example.test///'), 'https://api.example.test');
  assert.equal(normalizeApiBase('https://api.example.test/v1'), 'https://api.example.test/v1');
  assert.equal(normalizeApiBase('/'.repeat(100_000)), '');
});
