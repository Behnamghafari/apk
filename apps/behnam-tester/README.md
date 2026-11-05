# ApiX

**ApiX 1.21.3** is an Android API client for developers, built with Kotlin, OkHttp and a self-contained local HTML interface.

## Capabilities

- REST client with GET, POST, PUT, PATCH, DELETE, HEAD and OPTIONS.
- GraphQL workspace with query, mutation, variables and schema introspection.
- Native WebSocket client using OkHttp, custom headers, automatic protocol Ping keepalive, reconnect and message history.
- Query parameters, headers, Bearer / Basic / API Key authentication, JSON, raw and URL-encoded form bodies.
- Response body, headers, cookies, status, duration, size, search and response diff.
- Saved requests, nested collections, environments, global variables and request chaining.
- Collection runner, data-driven execution, scenario builder, assertions and comparable local test reports.
- Offline JSON Schema validation for the implemented subset of keywords (not a claim of full Draft 7 compliance).
- Local Mock Server, API monitoring, controlled load testing and basic HTTP security-header review.
- cURL and OpenAPI import, collection/environment export, code generation and JWT decoding (decode only; no signature verification).
- Local IndexedDB persistence with a localStorage fallback. No CDN or remote UI assets.

## Data and security notes

- Request history, saved collections, environments, cookies, WebSocket history and reports are stored locally on the device.
- Requests and WebSocket connections are sent directly to the endpoint selected by the user. ApiX does not proxy API traffic through a developer server.
- Credentials and tokens saved in environments or request snapshots are sensitive local data. Protect the device and avoid exporting collections that contain secrets.
- Imported pre-request and test scripts are disabled by default. Only enable script execution for code you trust.
- The Security Audit tool is a basic HTTP response-header review, not a penetration test or a complete security assessment.
- Monitoring is an in-app feature; it does not promise uninterrupted background checks or push notifications.
- Load testing can create significant traffic. Only test systems you own or have explicit permission to test.
- Cleartext HTTP is supported because API development sometimes requires it. Prefer HTTPS/WSS whenever the target supports it.

## Development build

The current GitHub workflow produces a **debug-signed APK** for development and direct testing:

```bash
gradle -p apps/behnam-tester :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

The committed stable debug key exists only to preserve in-place upgrades of development builds. **It must not be used for marketplace distribution.** A commercial release still requires a separate private release/upload keystore, protected CI secrets, a signed release APK/AAB, store policy review, privacy policy and listing materials.

## Privacy policy

[ApiX Privacy Policy](PRIVACY.md) — Persian and English.

## Creator

Behnam Ghafari — https://t.me/behnamghafari
