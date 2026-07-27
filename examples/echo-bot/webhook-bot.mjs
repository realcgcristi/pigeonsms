#!/usr/bin/env node
/**
 * PigeonSMS echo bot — webhook flavour, built on the SDK.
 *
 * client.listen() serves the handler pigeonsms.js builds: it verifies the
 * X-Pigeon-Signature HMAC over the raw bytes before parsing anything, then
 * answers inline in the HTTP response — the fastest path there is, because the
 * person who typed the command gets the answer in the same round trip.
 *
 *   PIGEON_SIGNING_SECRET=... PIGEON_BOT_TOKEN=PGB.123.abc node webhook-bot.mjs
 *
 * Then point the bot at it (the URL must be https, so put it behind a tunnel or
 * a reverse proxy in front of PORT):
 *
 *   curl -sX PATCH "$API/bots/$BOT_ID" \
 *     -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
 *     -d '{"interactions_url":"https://bots.example.com/pigeon"}'
 *
 * Commands answered: /echo text:<...>   /ping   /slow (deferred demo)
 */

import { Client, PigeonError } from 'pigeonsms.js';

const PORT = Number(process.env.PORT ?? 8787);
const PATH = process.env.PIGEON_WEBHOOK_PATH ?? '/pigeon';

if (!process.env.PIGEON_SIGNING_SECRET) {
  console.error('PIGEON_SIGNING_SECRET is required — see BOTS.md §3 for how to obtain it');
  process.exit(1);
}

const startedAt = Date.now();

const client = new Client({
  token: process.env.PIGEON_BOT_TOKEN,
  api: process.env.PIGEON_API,
  mode: 'webhook',
  signingSecret: process.env.PIGEON_SIGNING_SECRET,
  webhookPath: PATH,
});

client.command(
  'echo',
  'Repeat something back',
  (o) => o.string('text', 'what to repeat', { required: true, max: 200 }),
  (ctx) => ctx.reply(ctx.options.text),
);

client.command('ping', 'Check the bot is alive', (ctx) => {
  const latency = Date.now() - Number(ctx.createdAt ?? Date.now());
  return ctx.reply(`pong — ${latency} ms round trip, up ${uptime()}`);
});

/**
 * The escape hatch for work slower than the server's 3 s deadline: defer inside
 * the budget so the interaction survives, then answer through the callback.
 *
 * The SDK would have deferred for us at ~2.5 s anyway, but saying so explicitly
 * is clearer — and it frees the HTTP response immediately instead of holding it.
 */
client.command('slow', 'Deliberately slow reply (webhook demo)', async (ctx) => {
  await ctx.defer();
  await new Promise((resolve) => setTimeout(resolve, 5000));
  await ctx.reply('…done. That took 5 s and arrived through the callback.');
});

client.on('ready', (bot) => {
  console.log(`webhook bot @${bot.username} (bot ${bot.id}) ready against ${client.api}`);
  if (!bot.interactions_url) {
    console.warn('heads up: interactions_url is unset — interactions are being queued for polling instead');
  }
});

client.on('interaction', (ctx) => {
  console.log(`/${ctx.command} from @${ctx.user?.username ?? ctx.userId} in ${ctx.channelId}`);
});

client.on('error', (error) => {
  console.error(error instanceof PigeonError ? error.toString() : error);
});

if (process.env.PIGEON_DEBUG) client.on('debug', (line) => console.log(`· ${line}`));

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    console.log(`\n${signal} — stopping`);
    client.destroy().then(() => process.exit(0));
  });
}

function uptime() {
  const seconds = Math.floor((Date.now() - startedAt) / 1000);
  if (seconds < 60) return `${seconds}s`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m`;
  return `${Math.floor(seconds / 3600)}h${Math.floor((seconds % 3600) / 60)}m`;
}

try {
  // login() identifies the bot and syncs the commands above; nothing polls in
  // webhook mode, so listen() is what actually receives work.
  await client.login();
  await client.listen(PORT);
  console.log(`listening on http://localhost:${PORT}${PATH}  (GET /health for your supervisor)`);
} catch (error) {
  console.error(error instanceof PigeonError ? error.toString() : error);
  process.exit(1);
}
