#!/usr/bin/env node
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { spawn } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const generated = resolve(root, '.pigeon-generated');
const args = process.argv.slice(2);
const command = args.shift();

function option(name, fallback = undefined) {
  const index = args.indexOf(name);
  return index < 0 ? fallback : args[index + 1] ?? fallback;
}

function flag(name) { return args.includes(name); }

function run(program, commandArgs, input) {
  return new Promise((resolvePromise, reject) => {
    const child = spawn(program, commandArgs, { cwd: root, stdio: input === undefined ? 'inherit' : ['pipe', 'inherit', 'inherit'], shell: process.platform === 'win32' });
    if (input !== undefined) child.stdin.end(input);
    child.on('error', reject);
    child.on('exit', (code) => code === 0 ? resolvePromise() : reject(new Error(`${program} exited with ${code}`)));
  });
}

async function wrangler(args, input) {
  const executable = process.platform === 'win32' ? 'npx.cmd' : 'npx';
  await run(executable, ['wrangler', ...args], input);
}

async function createResource(args, pattern) {
  const executable = process.platform === 'win32' ? 'npx.cmd' : 'npx';
  return new Promise((resolvePromise, reject) => {
    const child = spawn(executable, ['wrangler', ...args], { cwd: root, shell: process.platform === 'win32', stdio: ['ignore', 'pipe', 'pipe'] });
    let output = '';
    child.stdout.on('data', (chunk) => { output += chunk; });
    child.stderr.on('data', (chunk) => { output += chunk; });
    child.on('error', reject);
    child.on('exit', (code) => {
      if (code !== 0) return reject(new Error(output));
      try {
        const json = JSON.parse(output);
        resolvePromise(pattern(json));
      } catch {
        const match = output.match(/[0-9a-f]{8}-[0-9a-f-]{27,}/i);
        if (match) resolvePromise(match[0]);
        else resolvePromise(output.trim());
      }
    });
  });
}

async function cloudflare() {
  const name = option('--name', 'pigeon-production');
  const webOrigin = option('--web-origin', '');
  const databaseId = option('--database-id');
  const bucket = option('--bucket');
  const queue = option('--queue');
  await mkdir(generated, { recursive: true });
  const database = databaseId ?? await createResource(['d1', 'create', `${name}-db`, '--json'], (value) => value.result?.uuid ?? value.database_id);
  const mediaBucket = bucket ?? `${name}-media`;
  if (!bucket) await createResource(['r2', 'bucket', 'create', mediaBucket], () => mediaBucket);
  const pushQueue = queue ?? `${name}-push`;
  if (!queue) await createResource(['queues', 'create', pushQueue], () => pushQueue);
  const template = await readFile(resolve(root, 'backend', 'wrangler.toml'), 'utf8');
  const config = template
    .replace(/name = "pigeon-api"/, `name = "${name}"`)
    .replace(/account_id = "[^"]+"\r?\n/, '')
    .replace(/routes = \[[\s\S]*?\]\r?\n\r?\n/, '')
    .replace(/database_name = "[^"]+"\r?\ndatabase_id = "[^"]+"/, `database_name = "${name}-db"\ndatabase_id = "${database}"`)
    .replace(/bucket_name = "[^"]+"/, `bucket_name = "${mediaBucket}"`)
    .replace(/queue = "pigeon-push"/g, `queue = "${pushQueue}"`)
    .replace(/\[observability\]/, `[vars]\nSERVER_NAME = "${name}"\nSERVER_VERSION = "3.0.0"\n${webOrigin ? `WEB_ORIGIN = "${webOrigin}"\n` : ''}\n[observability]`);
  const configPath = resolve(generated, 'wrangler.toml');
  await writeFile(configPath, config);
  await wrangler(['d1', 'migrations', 'apply', `${name}-db`, '--remote', '--config', configPath]);
  await wrangler(['deploy', '--config', configPath]);
  console.log(`PigeonSMS deployed: ${name}`);
  console.log(`Discovery: check the Worker URL at /.well-known/pigeon`);
}

if (command === 'cloudflare') await cloudflare();
else if (command === 'docker') await run('docker', ['compose', 'up', '--build']);
else {
  console.log('Pigeon self-hosting');
  console.log('  npx pigeonctl cloudflare --name my-pigeon --web-origin https://chat.example.com');
  console.log('  npx pigeonctl docker');
  process.exitCode = 2;
}
