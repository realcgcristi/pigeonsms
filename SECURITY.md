# security policy

pigeonsms is a privacy-first project, so security reports are taken seriously even though this is a one-person passion project.

## reporting a vulnerability

**please don't open a public issue for security bugs.** email me directly: **realcgcristi@getswift.cloud** with:

- what the bug is + where (endpoint, screen, file if you know it)
- how to reproduce it
- what an attacker could actually do with it

i'll try to reply within a few days. if it's a real issue i'll fix it and credit you (unless you'd rather stay anonymous). there's no paid bug bounty — it's a hobby project — but you'll have my genuine thanks.

## what counts

things i care about: auth bypass, reading/writing other users' data, token/session leaks, injection (d1/sql, the media serve path), leaking private nest content, mention/notification abuse, anything that breaks the privacy promise.

things i already know / won't treat as vulns: no TURN server (calls don't traverse symmetric NAT — see the roadmap), the account id sitting in wrangler.toml (it's not a secret), rate-limit tuning, and the general "it's a hobby backend" roughness.

## supported versions

only the latest release gets security fixes. self-hosters: stay current with `main`.

## if you self-host

you own your instance's security. set your own `PASSWORD_PEPPER`, `ADMIN_TOKEN`, and FCM secret (never reuse mine or the examples), keep wrangler + deps updated, and don't expose admin endpoints without the token.
