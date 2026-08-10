# Web client (v3.0.0) — shipped

Live: https://pigeonsms.aldi.best (Cloudflare Pages `pigeonsms-web` + `pigeonsms-web-proxy` worker for the exact host route) and https://pigeonsms-web.pages.dev

## Built
- Vite 7 + React 19 + TS strict + react-router 7 + zustand 5, Android-faithful mobile shell plus responsive tablet rail and full desktop workspace
- adaptive desktop UX includes two-column conversation/friend/nest grids, persistent navigation, split-view chat, wide profile heroes, side-panel overlays, and responsive settings cards
- design tokens ported from Palette/Accents/Dimens/Type/Motion/NovaMaterial/Wallpapers, dark/oled/light + 8 accents + wallpapers
- src/api: http.ts, dto.ts, gateway.ts (ws + call/channel sockets), client.ts (full endpoint surface)
- src/store: session, social, chat (optimistic send, retry/discard, live gateway patching), prefs (nicknames, forum seen, drafts), theme
- src/components: 133 icons, ui primitives (Avatar, Button, IconButton, TextField, SearchField, Switch, Spinner, Layout set, Sheet/Dialog/ConfirmDialog/ContextMenu, Toast), NavBar (PigeonNavBar port)
- src/lib: format, hash, markdown (bold/italic/underline/strike/code/quote/heading/list/fence, @mention, #channel, :emoji:, SPC- invite, links)
- screens: onboarding, messages, friends, spaces, nest channels/roles/members/emoji, chat (replies, reactions, polls, stickers, emoji picker + autocomplete, attachments, invite preview), forum, threads list + thread, search, profile, call, settings hub + editprofile/devices/history/security/blocked/appearance/appicon/privacy/notifications/nests/about

## Backend change
`backend/src/index.ts` CORS now allows pigeonsms.aldi.best, pigeonsms-web.pages.dev and its preview subdomains. Worker redeployed (version 55678664).

## Current notes
- `GET /spaces/:id/members` returns `role_ids`, so the role-assign sheet can pre-check a member's existing roles.
- calls now use the Worker `CallRoom` signalling path, with screen sharing, camera renegotiation, ICE restart, and optional TURN configuration through `VITE_TURN_*`.
- end-to-end encryption shipped in v3-rc3: a double-ratchet DM stream with the same Open Pigeon contract as Android, exposed as an opt-in "encrypted direct messages" switch in Settings → Security (off by default).
- production host: `https://pigeonsms.aldi.best`; API health: `https://api.pigeonsms.aldi.best/health`.
