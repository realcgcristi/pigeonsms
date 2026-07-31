import { validate, loadSchema } from './validate.mjs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));

export async function checkServer(origin, options = {}) {
  const base = new URL(origin);
  const checks = [];
  const add = (name, ok, detail = '') => checks.push({ name, ok, detail });
  const fetcher = options.fetch ?? fetch;
  const started = Date.now();
  try {
    const discoveryResponse = await fetcher(new URL('/.well-known/pigeon', base), {
      headers: { 'Pigeon-Protocol-Version': '1.0', Origin: 'https://lab.openpigeon.org' },
      signal: AbortSignal.timeout(options.timeout ?? 10000),
    });
    add('core.discovery.status', discoveryResponse.ok, `${discoveryResponse.status}`);
    const discovery = await discoveryResponse.json().catch(() => null);
    const schema = await loadSchema(resolve(here, '../../schemas/discovery.schema.json'));
    const errors = discovery ? await validate(discovery, schema) : ['response was not JSON'];
    add('core.discovery.schema', errors.length === 0, errors.join('; '));
    add('core.protocol.name', discovery?.protocol?.name === 'open-pigeon', discovery?.protocol?.name ?? 'missing');
    add('core.protocol.v1', discovery?.protocol?.versions?.includes('1.0') === true, (discovery?.protocol?.versions ?? []).join(','));
    const version = discoveryResponse.headers.get('pigeon-protocol-version');
    add('core.version.header', version?.startsWith('1.') === true, version ?? 'missing');
    add('core.tls', base.protocol === 'https:' || ['localhost', '127.0.0.1'].includes(base.hostname), base.protocol);
    add('core.endpoint.origin', discovery?.endpoints?.api ? new URL(discovery.endpoints.api).origin === base.origin : false, discovery?.endpoints?.api ?? 'missing');

    const healthResponse = await fetcher(new URL('/health', base), { signal: AbortSignal.timeout(options.timeout ?? 10000) });
    const health = await healthResponse.json().catch(() => null);
    add('core.health', healthResponse.ok && health?.ok === true, `${healthResponse.status}`);

    const missingResponse = await fetcher(new URL(`/.well-known/pigeon-lab-missing-${Date.now()}`, base), { signal: AbortSignal.timeout(options.timeout ?? 10000) });
    const missing = await missingResponse.json().catch(() => null);
    add('core.error.status', missingResponse.status === 404, `${missingResponse.status}`);
    add('core.error.envelope', typeof missing?.error?.code === 'string' && typeof missing?.error?.message === 'string', missing?.error?.code ?? 'missing');
    add('core.request.id', !!missingResponse.headers.get('x-request-id'), missingResponse.headers.get('x-request-id') ?? 'missing');
    add('core.latency', Date.now() - started < 10000, `${Date.now() - started}ms`);
    return { origin: base.origin, tested_at: new Date().toISOString(), discovery, checks, score: Math.round(100 * checks.filter((check) => check.ok).length / checks.length), ok: checks.every((check) => check.ok) };
  } catch (error) {
    add('core.reachable', false, error instanceof Error ? error.message : String(error));
    return { origin: base.origin, tested_at: new Date().toISOString(), discovery: null, checks, score: 0, ok: false };
  }
}
