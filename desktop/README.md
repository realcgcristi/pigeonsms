# pigeonsms for windows

the native client shares the web interface and adds credential manager storage, native notifications, launch at login, tray mode, unread status, deep links, single-instance handling and signed updates.

## setup

- node.js 24+
- rust stable with `stable-msvc`
- visual studio build tools with desktop development with c++
- microsoft edge webview2 runtime

```powershell
npm install
npm run dev
```

## build

```powershell
npm run build:app
npm run build:windows
```

`ctrl + shift + p` shows or hides the app. closing the window leaves it in the tray. deep links use `pigeonsms://`, such as `pigeonsms://chat/123`.
