#!/usr/bin/env node
/**
 * PigeonSMS echo bot — long-poll flavour, built on the SDK.
 *
 * pigeonsms.js holds GET /bots/me/updates open for you, hands each interaction
 * to the matching handler and answers through POST /interactions/:id/callback.
 * Nothing here has to be reachable from the internet: a laptop, a container
 * behind NAT and a cron box all work.
 *
 *   PIGEON_BOT_TOKEN=PGB.123.abc node poll-bot.mjs
 *
 * Commands answered: /echo text:<...>   /ping
 */

import { Client, PigeonError } from 'pigeonsms.js';

const startedAt = Date.now();

const client = new Client({
  token: process.env.PIGEON_BOT_TOKEN,
  api: process.env.PIGEON_API,
  mode: 'poll',
  gateway: true,
});

/**
 * Options arrive already coerced and validated against the schema declared
 * here — `text` is a string, it is present because it is required, and anything
 * the user didn't fill in is simply absent.
 */
client.command(
  'echo',
  'Repeat something back',
  (o) => o.string('text', 'what to repeat', { required: true, max: 200 }),
  (ctx) => ctx.reply(ctx.options.text),
);

client.command('ping', 'Check the bot is alive', (ctx) => {
  // created_at is when the invocation was recorded, so this is the real
  // end-to-end latency: their keystroke to our answer.
  const latency = Date.now() - Number(ctx.createdAt ?? Date.now());
  return ctx.reply(`pong — ${latency} ms round trip, up ${uptime()}`);
});

client.on('ready', (bot) => {
  console.log(`polling as @${bot.username} (bot ${bot.id}) against ${client.api}`);
  if (bot.interactions_url) {
    // With a URL set, work is delivered by webhook and this queue stays empty.
    console.warn(`heads up: interactions_url is ${bot.interactions_url} — nothing will arrive here`);
  }
});

client.on('interaction', (ctx) => {
  console.log(`/${ctx.command} from @${ctx.user?.username ?? ctx.userId} in ${ctx.channelId}`);
});

client.on('error', (error) => {
  console.error(error instanceof PigeonError ? error.toString() : error);
});

if (process.env.PIGEON_DEBUG) client.on('debug', (line) => console.log(`· ${line}`));

// Abort the in-flight poll on the way out, so the process exits now instead of
// when the 25 s hold times out.
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
  // login() = GET /bots/me, sync the commands above, start the loop. It resolves
  // once the bot is ready; the loop keeps running behind it.
  await client.login();
} catch (error) {
  console.error(error instanceof PigeonError ? error.toString() : error);
  process.exit(1);
}
