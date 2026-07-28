# PigeonSMS Desktop

The Windows client wraps the shared PigeonSMS web interface in a secure Tauri 2 shell. It adds Windows Credential Manager session storage, native window state restoration, a system tray, single-instance behavior, deep links, and NSIS/MSI installers.

## Requirements

- Node.js 24 or newer
- Rust stable with the `stable-msvc` toolchain
- Microsoft Visual Studio Build Tools with Desktop development with C++
- Microsoft Edge WebView2 Runtime

## Develop

```powershell
npm install
npm run dev
```

The development command starts the web app on port 5183 and launches the native window.

## Build

```powershell
npm run build:app
npm run build:windows
```

The first command creates the standalone executable without an installer. The second creates Windows NSIS and MSI installers.

Deep links use the `pigeonsms://` scheme. For example, `pigeonsms://chat/123` opens chat `123`.
