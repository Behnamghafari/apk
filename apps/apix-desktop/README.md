# ApiX Desktop for Windows

Electron desktop edition of ApiX. The Android application remains independent.

## Requirements
- Windows 10/11 x64
- Node.js 22 or newer
- npm

## Development
```powershell
cd apps/apix-desktop
npm install
npm start
```

## Build Windows installers
```powershell
npm run dist:win
npm run dist:portable
```

The Windows build workflow also publishes an NSIS installer and a portable executable.

## Data
Application data is stored in the current Windows user's Electron application data directory in `apix.sqlite`. API requests are made directly from the desktop main process to the URL selected in ApiX. No ApiX relay server is used.

## Desktop implementation
The desktop port reuses the ApiX interface and provides an Electron/Node bridge for REST requests, GraphQL, WebSocket connections with ping keepalive, SQLite-backed local storage, JSON and SQLite backup/restore, OpenAPI file selection, multipart uploads, a local Mock Server, monitoring, load testing and the same non-destructive security assessment flow.

This is the first Windows desktop release candidate. Validate your own API environments and backup files before relying on it as the only copy of important data.
