import { validate, loadSchema } from './validate.mjs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));

export async function checkServer(origin) {
  const base = new URL(origin);
  const checks = [];
  const discoveryResponse = await fetch(new URL('/.well-known/pigeon', base), {
    headers: { 'Pigeon-Protocol-Version': '1.0' },
  });
  checks.push({ name: 'core.discovery.status', ok: discoveryResponse.ok, detail: `${discoveryResponse.status}` });
  const discovery = await discoveryResponse.json().catch(() => null);
  const schema = await loadSchema(resolve(here, '../../schemas/discovery.schema.json'));
  const errors = discovery ? await validate(discovery, schema) : ['response was not JSON'];
  checks.push({ name: 'core.discovery.schema', ok: errors.length === 0, detail: errors.join('; ') });
  const version = discoveryResponse.headers.get('pigeon-protocol-version');
  checks.push({ name: 'core.version.header', ok: version?.startsWith('1.') === true, detail: version ?? 'missing' });
  const healthResponse = await fetch(new URL('/health', base));
  const health = await healthResponse.json().catch(() => null);
  checks.push({ name: 'core.health', ok: healthResponse.ok && health?.ok === true, detail: `${healthResponse.status}` });
  return { origin: base.origin, discovery, checks, ok: checks.every((check) => check.ok) };
}
