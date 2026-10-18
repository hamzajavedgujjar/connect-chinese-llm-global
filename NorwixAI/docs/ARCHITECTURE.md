# Architecture and security boundaries

The app is a single Android application module with explicit domain, persistence,
network, platform, and UI packages. `NorwixApplication` supplies process-owned services;
`NorwixViewModel` owns screen state, request jobs, and voice state. Compose observes
lifecycle-aware StateFlows. No backend or embedded web browser is present.

## Data flow

`Composer → ViewModel → encrypted repository → current-chat context → selected OAuth
account → public provider endpoint → terminal-checked SSE → encrypted repository → UI`.

`ContextBuilder` accepts one `Chat` and preferences, never an entire workspace. This
prevents accidentally enumerating other conversations into a request. Global notes
have a separate per-chat opt-in. A switch of account is an explicit user action; it
does not merge account credentials or erase the local conversation's existing context.

Browser-mode messages remain `LOCAL_ONLY`. The user reviews the exact text before
copying it and visiting the selected provider. Pasted external responses carry an
unverified source label. Browser content never enters the native repository implicitly.

## Encryption and deletion

`EncryptedVault` uses independently authenticated AES-256-GCM records with a fresh
random IV, 256-bit Android Keystore key, and filename-bound associated data. Records
are written through `AtomicFile`. Repository changes are published only after a
successful durable write; missing keys and corrupt records do not trigger silent reset.

Workspace state, including session credentials, and each attachment are encrypted
separately under app-private, backup-excluded storage. Plaintext attachment copies
are not persisted or exposed through an exported file provider. Imports are bounded
and read immediately from the Storage Access Framework result.

Removing a message/chat/draft attachment garbage-collects unreferenced encrypted
attachment blobs. Full local erasure deletes the vault and key, then creates a new
workspace and host lifecycle. An explicit export constructs credential-free DTOs;
it never serializes `Account.credentials` and tries to redact afterward.

## OAuth and HTTP

- Browser-owned sign-in; no native password field or intercepted browser session.
- Stable host ID; fresh nonce/state/verifier for every attempt; 5-minute callback limit.
- Exact loopback path and state validation, duplicate parameter rejection, one-use listener.
- Issued client IDs bound to verified subjects; returning identity mismatches are rejected.
- Discovery limited to the official issuer. No token-supplied key URLs are followed.
- Nimbus verifies allowed RS256/ES256 signatures and issuer/audience/time/nonce claims.
- Refresh-token rotation is serialized and committed with access, expiry, and scopes.
- Sign-out attempts the discovery revocation endpoint and reports unconfirmed revocation.
- Platform HTTPS certificate validation; no trust-all configuration, logs, cookies,
  automatic redirects, automatic retry, or alternative billing/inference endpoint.
- JSON reads are bounded; cancellation closes open streams. SSE completion is a separate
  state machine, including refusal, incomplete, usage-limit, and interrupted-stream handling.

Dependency signatures and vulnerability review still need normal release-time tooling;
this environment did not resolve or audit the complete transitive dependency graph.
Nimbus 10.5 was selected rather than 9.47, which falls within the affected range for
[CVE-2025-53864](https://github.com/advisories/GHSA-xwmg-2g98-w7v9).

## Lifecycle and speech

Voice APIs run on the main thread. Dictation cancels playback, and playback cancels
dictation. Session identifiers prevent callbacks from a stopped session updating a
new session. Audio-focus loss and Activity stop halt speech. A response finishing in
the background cannot trigger automatic playback. The user grants microphone access
only when requested; system/offline engine availability is checked explicitly.

The ViewModel survives configuration changes. Drafts are debounced and flushed when
the app backgrounds. Streaming text is checkpointed periodically; process-death
recovery marks any previously streaming message interrupted. A request can be stopped
immediately. Android may terminate a background process; there is no claim of durable
unattended generation or a background microphone service.

## Deliberate constraints

This implementation does not provide automatic cross-provider chat sync, arbitrary
provider token imports, third-party Claude subscription inference, native Gemini
subscription inference, or browser-account isolation. No UI control pretends those
capabilities are available. This is an uncompiled source preview awaiting the checks
listed in `VERIFICATION.md`.
