# Self-hosting

## Local Docker

Requires Docker Desktop:

```sh
docker compose up --build
```

Open `http://localhost:4173`. The Worker, D1, R2 and Durable Objects are persisted in the `pigeon-wrangler` volume. This local profile is for development and evaluation; change the development secrets before using it with real data.

## Cloudflare production

Requires Node 20+ and an authenticated Wrangler account:

```sh
node scripts/pigeonctl.mjs cloudflare --name my-pigeon --web-origin https://chat.example.com
```

The command creates D1, R2 and Queue resources, writes an isolated generated Wrangler config, applies every migration, deploys the Worker, and prints the discovery URL. Existing resources can be supplied with `--database-id`, `--bucket`, and `--queue` for repeatable upgrades.

Set secrets before inviting real users:

```sh
npx wrangler secret put PASSWORD_PEPPER --config .pigeon-generated/wrangler.toml
npx wrangler secret put ADMIN_TOKEN --config .pigeon-generated/wrangler.toml
npx wrangler secret put FCM_SERVICE_ACCOUNT --config .pigeon-generated/wrangler.toml
```

The generated worker advertises `/.well-known/pigeon` and supports the public compatibility suite.
