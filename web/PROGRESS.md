# Web client (v3.0.0) — shipped

Live: https://pigeonsms.aldi.best (Cloudflare Pages `pigeonsms-web` + `pigeonsms-web-proxy` worker for the exact host route) and https://pigeonsms-web.pages.dev

## Built
- Vite 7 + React 19 + TS strict + react-router 7 + zustand 5, mobile-faithful 460px app frame
- design tokens ported from Palette/Accents/Dimens/Type/Motion/NovaMaterial/Wallpapers, dark/oled/light + 8 accents + wallpapers
- src/api: http.ts, dto.ts, gateway.ts (ws + call/channel sockets), client.ts (full endpoint surface)
- src/store: session, social, chat (optimistic send, retry/discard, live gateway patching), prefs (nicknames, forum seen, drafts), theme
- src/components: 133 icons, ui primitives (Avatar, Button, IconButton, TextField, SearchField, Switch, Spinner, Layout set, Sheet/Dialog/ConfirmDialog/ContextMenu, Toast), NavBar (PigeonNavBar port)
- src/lib: format, hash, markdown (bold/italic/underline/strike/code/quote/heading/list/fence, @mention, #channel, :emoji:, SPC- invite, links)
- screens: onboarding, messages, friends, spaces, nest channels/roles/members/emoji, chat (replies, reactions, polls, stickers, emoji picker + autocomplete, attachments, invite preview), forum, threads list + thread, search, profile, call, settings hub + editprofile/devices/history/security/blocked/appearance/appicon/privacy/notifications/nests/about

## Backend change
`backend/src/index.ts` CORS now allows pigeonsms.aldi.best, pigeonsms-web.pages.dev and its preview subdomains. Worker redeployed (version 55678664).

## Known gaps
- `GET /spaces/:id/members` still does not return `role_ids`, so the role-assign sheet cannot pre-check a member's existing roles (client already reads the field).
- calls are local-media only; WebRTC signalling via `connectCall` is not wired into CallScreen yet.
- e2ee, scheduled messages, super-pins, channel overrides and notifications inbox exist in the client API but have no dedicated screen yet.
