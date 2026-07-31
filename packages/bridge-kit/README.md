# Open Pigeon Bridge Kit

Create a scoped bridge in PigeonSMS, keep the returned `PGBR` token on the connector machine, then run:

```sh
npx @pigeonsms/bridge-kit ./bridge.config.json
```

```json
{
  "pigeon": {
    "api": "https://api.example.com",
    "token": "PGBR.bridge.secret"
  },
  "adapter": {
    "kind": "matrix",
    "homeserver": "https://matrix.example.com",
    "token": "MATRIX_ACCESS_TOKEN",
    "room": "!room:example.com"
  }
}
```

Supported adapters are `matrix`, `discord`, `irc`, `slack`, and `email`. Discord and Slack support webhook-only outbound mode or token-based two-way mode. Email uses SMTP outbound and an authenticated local webhook for inbound delivery. External credentials never enter the PigeonSMS server.
