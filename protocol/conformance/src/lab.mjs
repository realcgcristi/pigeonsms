#!/usr/bin/env node
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { checkServer } from './server.mjs';

const registryPath = resolve(process.argv[2] || 'protocol/lab/servers.json');
const output = resolve(process.argv[3] || 'protocol/lab/site');
const registry = JSON.parse(await readFile(registryPath, 'utf8'));
await mkdir(resolve(output, 'badges'), { recursive: true });
await mkdir(resolve(output, 'results'), { recursive: true });

function safe(value) { return String(value).replace(/[^a-z0-9_-]/gi, '-').toLowerCase(); }
function tier(score) { return score === 100 ? 'gold' : score >= 85 ? 'silver' : score >= 60 ? 'bronze' : 'failing'; }
function color(score) { return score === 100 ? '#b58b00' : score >= 85 ? '#7c8797' : score >= 60 ? '#b26a36' : '#c73737'; }
function badge(name, score) {
  const label = `${tier(score)} ${score}%`;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="220" height="28" role="img" aria-label="Open Pigeon: ${label}"><rect width="220" height="28" rx="6" fill="#211b29"/><rect x="112" width="108" height="28" rx="6" fill="${color(score)}"/><text x="56" y="19" fill="#fff" text-anchor="middle" font-family="Verdana" font-size="11">Open Pigeon</text><text x="166" y="19" fill="#fff" text-anchor="middle" font-family="Verdana" font-size="11">${label}</text></svg>`;
}

const reports = [];
for (const server of registry.servers || []) {
  const report = await checkServer(server.origin);
  const combined = { id: safe(server.id || server.name), name: server.name, source: server.source || null, ...report, tier: tier(report.score) };
  reports.push(combined);
  await writeFile(resolve(output, 'results', `${combined.id}.json`), `${JSON.stringify(combined, null, 2)}\n`);
  await writeFile(resolve(output, 'badges', `${combined.id}.svg`), badge(combined.name, combined.score));
}

await writeFile(resolve(output, 'results.json'), `${JSON.stringify({ generated_at: new Date().toISOString(), reports }, null, 2)}\n`);
const cards = reports.map((report) => `<article><img src="badges/${report.id}.svg" alt="${report.tier}"><h2>${report.name}</h2><a href="${report.origin}">${report.origin}</a><ul>${report.checks.map((check) => `<li class="${check.ok ? 'pass' : 'fail'}">${check.ok ? 'PASS' : 'FAIL'} ${check.name}</li>`).join('')}</ul></article>`).join('');
await writeFile(resolve(output, 'index.html'), `<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Open Pigeon Compatibility Lab</title><style>body{margin:auto;max-width:1100px;padding:40px 20px;background:#15111a;color:#f7f1ff;font:15px system-ui}a{color:#bd8cff}main{display:grid;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));gap:18px}article{padding:22px;border:1px solid #44374f;border-radius:18px;background:#211b29}h1{font-size:34px}h2{margin-bottom:4px}ul{padding:0;list-style:none;line-height:1.8}.pass{color:#83dda4}.fail{color:#ff8d8d}</style><h1>Open Pigeon Compatibility Lab</h1><p>Daily independent protocol checks. Badges are generated from live results.</p><main>${cards}</main></html>`);
console.log(JSON.stringify({ ok: reports.every((report) => report.ok), reports: reports.map(({ id, name, score, tier }) => ({ id, name, score, tier })) }, null, 2));
if (reports.some((report) => !report.ok)) process.exitCode = 1;
