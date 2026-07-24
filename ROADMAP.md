# roadmap

where pigeonsms is headed. loose, not a promise — it's a hobby project and i work on it when i can. help on any of this is very welcome (see CONTRIBUTING).

## now (v2.x)

shipped and iterating: dms, nests with text/voice/forum channels, forum tags + likes + mark-as-`<label>` + mentions, the image editor, super-pins, push notifs, totp 2fa, ~20 app icons, and the three UI skins (classic / nova / galaxy).

## v3 — the big rocks

- **fix calls.** the #1 priority. get voice/video actually working: reliable native mic/camera capture across devices, and stand up a **TURN server** (coturn) so media traverses symmetric/cellular NAT. the signaling + UI are already done. this is the thing i most want help with.
- **desktop client.** a proper desktop app — leaning toward tauri (rust shell + web UI) so it's light and cross-platform (win/mac/linux), sharing the same backend. maybe a plain web client first as a stepping stone.
- ~~**end-to-end encryption** for DMs~~ — **done, shipped in v2.8.0.** X25519 identity keys per device, a double-ratchet DM message stream, sealed per-device key envelopes, and a password-derived encrypted key backup for multi-device. it's experimental and off-by-default while it hardens, but the full key exchange + device management story is built. e2ee is off the roadmap.

## v3 — smaller wants

- web client (browser access, no install)
- message search that's actually good (server-side, across a whole nest)
- threads / better forum threading
- richer roles + permissions per nest (not just owner/member)
- scheduled messages + reminders
- better media: gifs picker, stickers, larger uploads with resumable transfer
- import/export your data (privacy = you can take it and leave)
- accessibility pass (talkback, larger text, reduced motion is partly there)
- self-host quality-of-life: a one-command deploy, clearer docs, an admin dashboard

## maybe / someday

- federation between self-hosted instances
- bots / a small automation api
- voice channels that actually work like discord (depends on calls landing first)

if something here excites you, open an issue to say you're taking it so we don't double up.
