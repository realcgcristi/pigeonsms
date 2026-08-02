import { Hono } from 'hono';
import type { AppEnv } from '../types';

interface GithubAsset {
  name?: unknown;
  browser_download_url?: unknown;
}

interface GithubRelease {
  draft?: unknown;
  assets?: unknown;
}

export function desktopUpdateFor(value: unknown, target: string, arch: string) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const manifest = value as Record<string, unknown>;
  const version = typeof manifest.version === 'string' ? manifest.version : '';
  if (!/^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/.test(version)) return null;
  if (!manifest.platforms || typeof manifest.platforms !== 'object' || Array.isArray(manifest.platforms)) return null;
  const entry = (manifest.platforms as Record<string, unknown>)[`${target}-${arch}`];
  if (!entry || typeof entry !== 'object' || Array.isArray(entry)) return null;
  const record = entry as Record<string, unknown>;
  const signature = typeof record.signature === 'string' ? record.signature : '';
  const url = typeof record.url === 'string' ? record.url : '';
  if (signature.length < 32 || signature.length > 4096) return null;
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    return null;
  }
  if (
    parsed.protocol !== 'https:' ||
    parsed.hostname !== 'github.com' ||
    !parsed.pathname.startsWith('/realcgcristi/pigeonsms/releases/download/')
  ) return null;
  return {
    version,
    url: parsed.toString(),
    signature,
    ...(typeof manifest.notes === 'string' ? { notes: manifest.notes.slice(0, 16_384) } : {}),
    ...(typeof manifest.pub_date === 'string' ? { pub_date: manifest.pub_date } : {}),
  };
}

const updates = new Hono<AppEnv>();

updates.get('/latest', async (c) => {
  const row = await c.env.DB.prepare(
    'SELECT version_code, version_name, url, notes, created_at FROM app_releases ORDER BY version_code DESC LIMIT 1',
  ).first();
  return c.json({ release: row ?? null });
});

updates.get('/desktop/:target/:arch/:currentVersion', async (c) => {
  const target = c.req.param('target');
  const arch = c.req.param('arch');
  if (target !== 'windows' || !['x86_64', 'aarch64', 'i686'].includes(arch)) return c.body(null, 204);
  try {
    const releasesResponse = await fetch('https://api.github.com/repos/realcgcristi/pigeonsms/releases?per_page=10', {
      headers: {
        accept: 'application/vnd.github+json',
        'user-agent': 'PigeonSMS-Updater',
        'x-github-api-version': '2022-11-28',
      },
      cf: { cacheEverything: true, cacheTtl: 300 },
    });
    if (!releasesResponse.ok) return c.body(null, 502);
    const releases = await releasesResponse.json() as GithubRelease[];
    let manifestUrl = '';
    for (const release of releases) {
      if (release.draft === true || !Array.isArray(release.assets)) continue;
      const asset = (release.assets as GithubAsset[]).find((item) => item?.name === 'latest.json');
      if (typeof asset?.browser_download_url === 'string') {
        manifestUrl = asset.browser_download_url;
        break;
      }
    }
    if (!manifestUrl) return c.body(null, 204);
    const manifestResponse = await fetch(manifestUrl, {
      headers: { accept: 'application/json', 'user-agent': 'PigeonSMS-Updater' },
      cf: { cacheEverything: true, cacheTtl: 300 },
    });
    if (!manifestResponse.ok) return c.body(null, 502);
    const update = desktopUpdateFor(await manifestResponse.json(), target, arch);
    if (!update) return c.body(null, 502);
    c.header('cache-control', 'public, max-age=300');
    return c.json(update);
  } catch {
    return c.body(null, 502);
  }
});

export default updates;
