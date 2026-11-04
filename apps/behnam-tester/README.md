# ApiX

**ApiX 1.6.0** is an Android API client for developers, built with Kotlin, OkHttp and a local HTML interface.

## Features
- REST API, GraphQL and WebSocket clients
- HTTP methods: GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS
- Query parameters, request headers and Bearer / Basic / API Key authentication
- JSON, raw text and URL-encoded form bodies
- Response body, headers, cookies, status, duration and size
- Response search with matching line previews
- JSON formatting, validation and response diff
- Request history and saved collections
- Collection runner with sequential execution and JSON reports
- Request chaining inside a collection using `{{prev.access_token}}`, `{{prev.status}}` and `{{prev.raw}}`
- Collection JSON export and import
- Named environments, global variables and `{{variable}}` interpolation
- JWT header and payload decoder (does not verify signatures)
- Code generator for JavaScript Fetch, Python Requests and PHP cURL
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
