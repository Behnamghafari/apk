# ApiX Native — Feature Parity Plan

The existing `apps/behnam-tester` implementation is the product behavior reference. This project must reach feature parity before it can replace that app. The legacy app remains untouched and independently buildable.

## Reference audit

The current `tester.html` is approximately 199 KB and contains 197 distinct JavaScript functions. The native starter currently implements only a basic HTTP request form and response display. It is not feature-complete.

## Feature inventory from the existing app

- [ ] **Request workspace:** multiple open request tabs, per-tab state, restore workspace, REST / GraphQL / WebSocket modes.
- [ ] **HTTP request builder:** methods, URL, query parameters, headers, enabled/disabled rows, request body modes (none, JSON, raw, URL-encoded form, multipart upload), request settings.
- [ ] **Authorization:** none, Bearer, Basic, API Key; environment-variable interpolation.
- [ ] **Scripting:** pre-request scripts, post-response scripts, script error reporting, assertions and assertion builder.
- [x] **Environments (basic):** create/update environments, activate/switch environment, persist key/value variables, and interpolate `{{variable}}` in URL, query parameters, headers, body, and auth fields.
- [~] **Environments (remaining):** secrets and request payloads are now encrypted at rest with Android Keystore-backed AES-GCM, including transparent migration of existing plaintext records. Global variables, duplicate/delete, and import/export remain pending.
- [~] **Cookies:** OkHttp automatically sends/receives cookies and persists them in encrypted local storage. Cookie manager UI, add/delete/clear controls remain pending.
- [~] **Collections and folders:** save/update/delete requests, nested folder navigation and creation, rename/delete current folder, move requests between folders, and Postman Collection v2.1 import/export with nested folder preservation are implemented. Sample data and bulk folder operations remain pending.
- [~] **History:** recent request history is persisted with encrypted URLs; restore to the request editor, delete individual entries, and clear all history are implemented. Full searchable history screen and restoring complete request configuration remain pending.
- [~] **Response inspector:** status, timing, final URL, body/headers tabs, JSON pretty-printing, response search, and copy are implemented. Syntax highlighting, download, HTML preview, response diff and cookie inspector remain pending.
- [~] **GraphQL:** native query/mutation mode, operation name, JSON variables, environment interpolation, and saving/restoring GraphQL requests are implemented. Formatting, validation, schema introspection and documentation remain pending.
- [ ] **WebSocket:** connect/disconnect, headers, ping, keepalive, auto-reconnect, saved connections, message history and export.
- [~] **Import/export:** common cURL command import and Postman Collection v2.1 import/export are implemented. cURL export, Swagger/OpenAPI import (JSON and YAML), environment export/import, and API documentation export remain pending.
- [ ] **Test runner:** folder runs, data-driven runs, batch execution, run reports, saved test runs and run comparison.
- [ ] **Scenarios:** scenario editor, examples, sequential steps, variable extraction/substitution, assertions, execution logs and cancellation.
- [ ] **Uploads:** file picker, multipart configuration, upload progress/result handling.
- [ ] **API utilities:** code generation, JWT decoder, Base64URL decoder, JSON body format/validation, JSON Schema editor and validator.
- [ ] **Developer tools:** mock API server, load testing, security audit.
- [ ] **Monitoring:** saved endpoint monitors, manual checks, enable/disable, history and scheduled monitoring.
- [~] **Local data security:** AES-GCM encryption backed by Android Keystore protects saved request URLs, headers, query parameters, bodies, auth configs, environment variables, and history URLs. Legacy plaintext records are migrated when read.
- [ ] **Storage migration:** read and migrate existing ApiX local records where technically possible, with explicit backup and no destructive overwrite.

## Implemented in the current Native increment

- Postman Collection v2.1 JSON import through Android document picker; nested folders and requests are imported into the local workspace with new IDs (existing records are not overwritten).
- Postman Collection v2.1 JSON export of the complete saved workspace, including nested folders.
- cURL command import for common URL, method, header, body, Basic Auth, and GET-with-data options; parsing is local and never executes a shell command.
- GraphQL request mode with query/mutation, operation name, JSON variables, environment-variable interpolation, and GraphQL request save/restore.
- AES-GCM at-rest encryption for saved request content, auth credentials, environment variables, and history URLs using an Android Keystore key; legacy plaintext values are migrated on read.
- Response inspector with Body/Headers tabs, JSON pretty-print toggle, case-insensitive line search, match count, and copy actions.
- Encrypted persistent cookie jar integrated with OkHttp; cookies are scoped by domain/path and expired entries are cleaned when accessed.
- History actions to restore a request's method/URL, delete individual entries, and clear recent history.
- Nested folder navigation, subfolder creation, current-folder rename/delete, request move between folders, and saved-request deletion.
- CI debug APK build verified successfully for commit `85bac21f80f80378479d3be5a72ad73af90d9bdd`.

Known limits in this increment: Postman file-based form-data entries are not imported as local files; advanced cURL flags and cURL export are not yet supported.

## Delivery phases

1. **Foundation:** navigation, design system, persistence/data models, request workspace, robust HTTP engine, query/header/body/auth support. Basic environment editor and variable interpolation are implemented; encrypted secret storage remains pending.
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
