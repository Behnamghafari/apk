# ApiX

**ApiX** is a professional API testing and development client for Android, built with Kotlin, OkHttp and a local HTML interface.

## Features
- REST API, GraphQL and WebSocket clients
- HTTP methods: GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS
- Query parameters and editable request headers
- Bearer, Basic and API Key authentication
- JSON, raw text and URL-encoded form bodies
- Response body, headers, cookies, status, duration and size
- Request history and saved collections
- Named environments and `{{variable}}` interpolation
- cURL import/export
- Pre-request and response test scripts
- Local persistence; no external CDN or remote UI dependencies

## Build
```bash
gradle -p apps/behnam-tester :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Creator
Behnam Ghafari — https://t.me/behnamghafari
