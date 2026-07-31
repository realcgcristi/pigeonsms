#!/usr/bin/env node
import { checkServer } from './server.mjs';

const origin = process.argv[2];
if (!origin) {
  console.error('usage: pigeon-conformance <server-origin>');
  process.exit(2);
}

const result = await checkServer(origin);
for (const check of result.checks) {
  console.log(`${check.ok ? 'PASS' : 'FAIL'} ${check.name}${check.detail ? ` (${check.detail})` : ''}`);
}
process.exitCode = result.ok ? 0 : 1;
