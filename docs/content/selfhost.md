# Self-hosting

Local development is one command:

```bash
docker compose up --build
```

Open `http://localhost:4173`. The local Worker, D1 database, R2 media bucket and Durable Objects persist in a Docker volume.

Cloudflare production is also one command after Wrangler authentication:

```bash
node scripts/pigeonctl.mjs cloudflare --name my-pigeon --web-origin https://chat.example.com
```

It provisions D1, R2 and Queue resources, applies all migrations and deploys the Worker. Set `PASSWORD_PEPPER`, `ADMIN_TOKEN` and `FCM_SERVICE_ACCOUNT` as Wrangler secrets before real use.
