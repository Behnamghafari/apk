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

## Current desktop port status
The shared ApiX interface and initial Electron bridge are in place. Some Android-specific file/SQLite restore and multipart upload flows still require desktop-specific implementation and validation before this build should be considered feature-complete.
