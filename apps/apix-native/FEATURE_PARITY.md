# ApiX Native — Feature Parity Plan

The existing `apps/behnam-tester` implementation is the product behavior reference. This project must reach feature parity before it can replace that app. The legacy app remains untouched and independently buildable.

## Reference audit

The current `tester.html` is approximately 199 KB and contains 197 distinct JavaScript functions. The native starter currently implements only a basic HTTP request form and response display. It is not feature-complete.

## Feature inventory from the existing app

- [ ] **Request workspace:** multiple open request tabs, per-tab state, restore workspace, REST / GraphQL / WebSocket modes.
- [ ] **HTTP request builder:** methods, URL, query parameters, headers, enabled/disabled rows, request body modes (none, JSON, raw, URL-encoded form, multipart upload), request settings.
- [ ] **Authorization:** none, Bearer, Basic, API Key; environment-variable interpolation.
- [ ] **Scripting:** pre-request scripts, post-response scripts, script error reporting, assertions and assertion builder.
- [ ] **Environments:** multiple environments, active environment, variables, global variables, duplicate/edit/delete, import/export.
- [ ] **Cookies:** managed cookie jar, add/delete/clear, cookie integration with requests.
- [ ] **Collections and folders:** save/update requests, nested folders, rename/delete, folder navigation, collection import/export, sample data.
- [ ] **History:** request history, restore, delete, clear.
- [ ] **Response inspector:** status, timing, headers, body formatting, syntax highlighting, response search, copy/download, HTML preview, response diff.
- [ ] **GraphQL:** query/mutation, operation name, variables, formatting, validation, schema introspection and documentation.
- [ ] **WebSocket:** connect/disconnect, headers, ping, keepalive, auto-reconnect, saved connections, message history and export.
- [ ] **Import/export:** cURL import/export, Swagger/OpenAPI import (JSON and YAML), collection/environment exports, API documentation export.
- [ ] **Test runner:** folder runs, data-driven runs, batch execution, run reports, saved test runs and run comparison.
- [ ] **Scenarios:** scenario editor, examples, sequential steps, variable extraction/substitution, assertions, execution logs and cancellation.
- [ ] **Uploads:** file picker, multipart configuration, upload progress/result handling.
- [ ] **API utilities:** code generation, JWT decoder, Base64URL decoder, JSON body format/validation, JSON Schema editor and validator.
- [ ] **Developer tools:** mock API server, load testing, security audit.
- [ ] **Monitoring:** saved endpoint monitors, manual checks, enable/disable, history and scheduled monitoring.
- [ ] **Storage migration:** read and migrate existing ApiX local records where technically possible, with explicit backup and no destructive overwrite.

## Delivery phases

1. **Foundation:** navigation, design system, persistence/data models, request workspace, robust HTTP engine, query/header/body/auth support.
2. **Core workflow:** environments, variables, cookies, collections, nested folders, history, import/export and workspace restoration.
3. **Advanced protocols:** GraphQL, WebSocket, multipart uploads and response inspection/preview.
4. **Automation:** scripting, assertions, folder/data-driven runner, test reports, scenarios.
5. **Power tools:** OpenAPI/cURL, JSON Schema, utilities, mock server, load test, security audit and monitors.
6. **Parity and release:** regression tests against the legacy app, data migration tests, accessibility/responsive checks, release signing and APK validation.

## Native architecture rules

- Kotlin + Jetpack Compose for all screens; no WebView or HTML/JavaScript UI.
- Repository / use-case / UI-state separation; keep networking and persistence out of composables.
- OkHttp for HTTP and WebSocket transport; all I/O off the main thread.
- Room for structured durable data, DataStore for preferences, and encrypted storage for secrets/tokens.
- Explicit import/export formats and versioned migrations.
- Preserve user data; never silently discard legacy records.
- Add automated tests for request construction, variable interpolation, persistence, import/export and execution flows.

## Acceptance rule

A feature is not considered complete because a screen exists. It must work end-to-end, persist correctly where expected, handle errors, and have regression coverage. The native app is not a replacement until the feature inventory is implemented and verified.
