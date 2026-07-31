import test from 'node:test';
import assert from 'node:assert/strict';
import { readdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { validateFixture } from './src/validate.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const fixtures = resolve(here, '../fixtures');

for (const kind of ['valid', 'invalid']) {
  for (const file of await readdir(resolve(fixtures, kind))) {
    test(`${kind} fixture: ${file}`, async () => {
      const errors = await validateFixture(resolve(fixtures, kind, file));
      if (kind === 'valid') assert.deepEqual(errors, []);
      else assert.ok(errors.length > 0, 'invalid fixture unexpectedly passed');
    });
  }
}
