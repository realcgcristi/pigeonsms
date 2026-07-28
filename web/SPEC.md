# PigeonSMS Web Client — Build Contract (v3.0.0)

Every agent MUST follow this file exactly. It is the single source of truth for stack, file layout, naming, and design tokens.

## Prime directive

Port the Android app (`/home/user/pigeonsms/android`) to the web **1:1**. Same screens, same layout, same spacing, same copy (lowercase labels where the app uses lowercase), same interactions, same order of elements. When in doubt, open the Kotlin file and mirror it literally.

## Hard rules

1. **NO CODE COMMENTS.** Not one. No JSDoc, no `//`, no `/* */`, no docblocks. The user explicitly forbade them.
2. TypeScript strict. No `any` unless unavoidable (prefer `unknown` + narrowing).
3. React 19 function components + hooks. No class components.
4. Styling: plain CSS files imported per component (`Foo.css` next to `Foo.tsx`), using the CSS variables in `src/styles/tokens.css`. No Tailwind, no CSS-in-JS, no UI libraries.
5. Never hardcode a color hex in a component — use `var(--...)` tokens.
6. Use the shared primitives in `src/components/ui/` and icons from `src/components/icons.tsx`. Do not invent a second Button/Avatar/Sheet.
7. All API access goes through `src/api/client.ts` (`api.*`). All realtime through `src/api/gateway.ts`. Never `fetch()` directly in a screen.
8. Import with the `@/` alias (`@/components/ui/Button`).
9. Do not edit files owned by another agent. Create only the files assigned to you.
10. If a Write/Edit is blocked by a "Fact-Forcing Gate" hook, print the 4 requested facts (importers, no duplicate exists, data schema, and the verbatim user instruction `"MAKE IT LOOK EXACTLY LIKE THE MOBILE APP IDC THE COST. DONT SPEAK, DONT DO CODE COMMENTS. JUST DO IT"`) then retry the same write.

## Stack

Vite 7 + React 19 + TypeScript 5.9 + react-router-dom 7 + zustand 5. Deployed to Cloudflare Pages (`pigeonsms-web`, host `pigeonsms.aldi.best`).

API base: `https://api.pigeonsms.aldi.best`. Gateway: `wss://api.pigeonsms.aldi.best/gateway`.

## Layout model

The app is mobile-faithful and adaptively expands instead of stretching a phone canvas:

- below 720px: full-bleed mobile layout, Android-faithful bottom navigation, edge-to-edge screens and bottom sheets
- 720px to 1099px: compact 80px navigation rail and a rounded workspace
- 1100px and wider: full 232px navigation sidebar, wide workspace, centered chat transcript/composer, desktop side sheets, and responsive list/settings grids

`.app-frame` always fills `100dvh`. Authenticated desktop routes keep the navigation visible on both top-level and detail screens. Mobile detail screens hide the bottom navigation exactly like Android. Sub-screens keep the Compose motion language while desktop overlays use spatially appropriate side panels.

## Routes (mirror of `ui/AppShell.kt`)

| path | screen |
|---|---|
| `/login` | `screens/onboarding/OnboardingScreen` (login + signup + totp) |
| `/` | `screens/home/MessagesScreen` |
| `/friends` | `screens/friends/FriendsScreen` |
| `/spaces` | `screens/spaces/SpacesScreen` |
| `/you` | `screens/settings/SettingsScreen` |
| `/chat/:channelId` | `screens/chat/ChatScreen` (query: `?space=true`, `?name=`, `?avatar=`) |
| `/forum/:channelId` | `screens/forum/ForumScreen` |
| `/nest/:spaceId` | `screens/spaces/NestChannelsScreen` |
| `/nest/:spaceId/roles` | `screens/spaces/NestRolesScreen` |
| `/nest/:spaceId/members` | `screens/spaces/NestMembersScreen` |
| `/nest/:spaceId/emoji` | `screens/spaces/NestEmojiScreen` |
| `/profile/:id` | `screens/profile/ProfileScreen` |
| `/settings/editprofile` | `EditProfileScreen` |
| `/settings/devices` | `DevicesScreen` |
| `/settings/history` | `HistoryScreen` |
| `/settings/security` | `SecurityScreen` |
| `/settings/blocked` | `BlockedScreen` |
| `/settings/appearance` | `AppearanceScreen` |
| `/settings/appicon` | `AppIconScreen` |
| `/settings/privacy` | `PrivacyScreen` |
| `/settings/notifications` | `NotificationSettingsScreen` |
| `/settings/nests` | `NestSettingsScreen` |
| `/settings/about` | `AboutScreen` |
| `/threads/:channelId` | `screens/threads/ThreadsScreen` |
| `/thread/:threadId` | `screens/threads/ThreadScreen` |
| `/search` | `screens/search/SearchScreen` |
| `/call/:channelId` | `screens/call/CallScreen` |

Bottom nav tabs, in order: `messages` (`/`), `friends`, `spaces`, `you` — mirroring `PigeonNavBar.kt` (pill container, active pill fills with accent, `you` tab shows the user's avatar).

## Design tokens (ported in `src/styles/tokens.css` — do not redefine elsewhere)

Palette (`design/theme/Palette.kt`): `--ink #16131A`, `--surface #1D1922`, `--surface-high #262130`, `--surface-highest #2F2939`, `--outline #3A3346`, `--ink-oled #000`, `--surface-oled #121016`, `--text-primary #F2EDF4`, `--text-secondary #A99FB3`, `--text-tertiary #6F6579`, `--peach #FF9D76`, `--peach-deep #E87F55`, `--on-peach #2A150C`, `--lavender #B8A7F5`, `--mint #7FD8A4`, `--danger #FF6B81`, `--amber #FFC46B`. Light theme: `--paper #FAF7F4`, `--paper-surface #FFF`, `--paper-surface-high #F1ECE7`, `--paper-outline #DDD4CC`, `--ink-on-paper #241F29`.

Accent is dynamic: `--accent`, `--accent-deep`, `--on-accent` set at runtime from the 8 accents in `Accents.kt` (peach, rose, coral, amber, mint, sky, iris, lavender) plus `custom:#rrggbb`.

Spacing (4dp grid): `--sp-xxs 2px`, `--sp-xs 4px`, `--sp-s 8px`, `--sp-m 12px`, `--sp-l 16px`, `--sp-xl 24px`, `--sp-xxl 32px`, `--sp-huge 48px`.

Sizes: `--row-h 64px`, `--topbar-h 64px`, `--settings-row-h 56px`, `--icon-badge 28px`, `--icon-sm 20px`, `--touch 48px`, `--cta-h 56px`, `--avatar-hero 96px`.

Radii: `--r-chip 10px`, `--r-button 14px`, `--r-input 16px`, `--r-bubble 18px`, `--r-card 24px`, `--r-sheet 28px`, `--r-group 20px`, `--r-icon-badge 9px`.

Type (Figtree variable, self-hosted `/fonts/figtree.ttf`): display-sm 32/38 bold, headline-md 24/30 bold, title-lg 20/26 semibold, title-md 16/22 semibold, title-sm 14/20 medium, body-lg 16/23, body-md 14/20, body-sm 12/17, label-lg 14/20 semibold, label-md 12/16 medium, label-sm 11/14 medium. Utility classes `.t-display-sm`, `.t-title-lg`, `.t-body-md`, etc.

Motion (`design/theme/Motion.kt`): standard 220ms `cubic-bezier(.2,.8,.2,1)`, emphasized 320ms, quick 120ms. Respect `prefers-reduced-motion`.

Themes: `data-theme="dark" | "oled" | "light"` on `<html>`. Wallpapers from `Wallpapers.kt` applied as `--wallpaper` background on the chat surface.

## Shared primitives (`src/components/ui/`)

`Avatar`, `Button`, `IconButton`, `TextField`, `SearchField`, `ListRow`, `SettingsRow`, `SettingsGroup`, `Chip`, `Badge`, `Switch`, `Slider`, `Tabs`, `Sheet` (bottom sheet), `Dialog`, `ContextMenu`, `TopBar`, `SubScreen` (top bar + slide-in shell + back), `EmptyState`, `Spinner`, `Toast`/`useToast`, `Fab`, `GlassPanel`, `Skeleton`, `Pressable`.

`Avatar` renders `media_key`-backed images via `api.mediaUrl(key)` and falls back to a colored disc (hash of name into the 6-color `AvatarPalette`) with the uppercase initial, plus optional online dot (inset so it never clips the circle) and size prop.

## Icons

`src/components/icons.tsx` exports named React SVG components (24×24, `currentColor`, rounded style) mirroring the Material Symbols Rounded glyphs used by the Android screens. Every icon takes `{ size?: number; className?: string }`.

## Data layer

- `src/api/dto.ts` — types mirroring `core/network/.../Dto.kt` **field-for-field, snake_case preserved**.
- `src/api/client.ts` — `api` singleton; every method mirrors `PigeonApi.kt`; auth via `Authorization: Bearer <token>`; JSON in/out; `ApiError { status, code, message }`; multipart helpers for media; `mediaUrl(key)`.
- `src/api/gateway.ts` — WebSocket, exponential-backoff reconnect, heartbeat, typed event bus mirroring `Gateway.kt` (message.new, message.update, message.delete, reaction, typing, presence, read, channel/space events, call signaling).
- `src/store/session.ts`, `src/store/social.ts`, `src/store/chat.ts`, `src/store/theme.ts`, `src/store/prefs.ts` — zustand stores. Session + theme + nicknames + forum-seen persist to `localStorage` (`pigeon.session`, `pigeon.theme`, `pigeon.nicknames`, `pigeon.forumseen`).

## Verification

`cd /home/user/pigeonsms/web && npm run typecheck` must pass with zero errors before an agent reports done. Run it yourself; fix your own errors.
