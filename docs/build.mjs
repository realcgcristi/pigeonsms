import { readFile, writeFile, mkdir, cp } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { marked } from 'marked';

const PAGES = [
  { slug: 'index', title: 'PigeonSMS', source: 'content/index.md' },
  { slug: 'bots', title: 'Bots', source: '../BOTS.md' },
  { slug: 'sdk', title: 'pigeonsms.js', source: '../packages/pigeonsms.js/README.md' },
  { slug: 'api', title: 'HTTP API', source: 'content/api.md' },
  { slug: 'clients', title: 'Clients', source: 'content/clients.md' },
  { slug: 'protocol', title: 'Open Protocol', source: 'content/protocol.md' },
  { slug: 'selfhost', title: 'Self-hosting', source: 'content/selfhost.md' },
  { slug: 'platform', title: 'V3 Platform', source: 'content/platform.md' },
];

const NAV = PAGES.map((page) => ({ href: page.slug === 'index' ? '/' : `/${page.slug}`, title: page.title }));

function shell(page, body) {
  const nav = NAV.map(
    (item) =>
      `<a href="${item.href}"${
        (page.slug === 'index' && item.href === '/') || item.href === `/${page.slug}` ? ' class="on"' : ''
      }>${item.title}</a>`,
  ).join('');
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${page.title} — PigeonSMS docs</title>
<meta name="theme-color" content="#16131A">
<link rel="icon" href="/favicon.png" type="image/png">
<link rel="stylesheet" href="/style.css">
</head>
<body>
<header class="top">
  <a class="brand" href="/">
    <img src="/logo.png" alt="" width="30" height="30">
    <span>pigeonsms <em>docs</em></span>
  </a>
  <nav>${nav}</nav>
</header>
<main class="page">${body}</main>
<footer class="foot">
  <span>GPL-3.0 · <a href="https://github.com/realcgcristi/pigeonsms">github</a> · <a href="https://pigeonsms.aldi.best">open the app</a></span>
</footer>
</body>
</html>`;
}

await mkdir('dist', { recursive: true });

for (const page of PAGES) {
  if (!existsSync(page.source)) {
    console.warn('skipping missing', page.source);
    continue;
  }
  const markdown = await readFile(page.source, 'utf8');
  const html = marked.parse(markdown, { mangle: false, headerIds: true });
  const out = page.slug === 'index' ? 'dist/index.html' : `dist/${page.slug}.html`;
  await writeFile(out, shell(page, html));
  console.log('built', out);
}

await cp('static', 'dist', { recursive: true });
console.log('static assets copied');
