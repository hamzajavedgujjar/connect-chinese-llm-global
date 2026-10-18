# Verification and acceptance record

Prepared: **18 October 2026**.

**Not release-ready. No tested or installable APK is included.** Source preflight and
manual source review do not establish that Kotlin compiles or that device/provider
features work.

| Check | Status in this authoring run | Evidence / next action |
| --- | --- | --- |
| Official provider documentation | Reviewed | `PROVIDERS.md` links primary documentation; no live account implied |
| Offline source/resource/security contract preflight | See `source-preflight.txt` | This checks file structure, XML, basic lexical structure, endpoint/credential and manifest contracts only |
| Shell / Python syntax and CI YAML | Passed; `script-checks.txt` | Source syntax / YAML parsing only; not execution of Gradle or CI |
| Gradle bootstrap/build attempt | Blocked | `build-attempt.txt`; required distribution download denied with HTTP 403 |
| Kotlin/Compose compilation | Not run | No downloaded build toolchain / resolved dependencies |
| Android lint | Not run | Run `:app:lintDebug` |
| Kotlin unit tests | Not run | Test source provided; run `:app:testDebugUnitTest` |
| Instrumented tests | Not run | No Android SDK / ADB / emulator; run `:app:connectedDebugAndroidTest` |
| APK packaging/signature/install | Not run | No APK produced; do not rename the source archive or another app as an APK |
| Visual QA / tablet / rotation / TalkBack | Not run | Device acceptance below |
| Live ChatGPT OAuth / consent / renewal / revocation | Not verified | No account or device authorization was performed |
| Live ChatGPT models / text stream / image / PDF | Not verified | Needs eligible account and model after successful OAuth |
| Gemini / Claude site login | Not verified on device | Browser-launch code only; no native subscription integration claimed |
| Microphone / installed voices / audio focus | Not verified on device | Hardware and installed-service tests required |
| GitHub Actions | Configured, not executed | Run the included workflow on a connected repository |

## Tests supplied

- Chat-specific memory isolation and global opt-in; exclusion of incomplete replies;
  explicit oversized-history rejection.
- Official provider URL allowlist and origin-confusion attacks.
- RFC 7636 PKCE vector, duplicate callback parameters, nonce/state comparison.
- SSE framing and explicit completion; late usage-limit failure after partial text;
  refusal output and completion-only final output.
- Repository reload, interrupted-process recovery, disk-write failure isolation,
  credential-free exports, and attachment cleanup when deleting chats.
- Locally signed test JWT verification; invalid signature, issuer, audience, nonce,
  and expiration rejection; loopback wrong-state rejection before a valid callback.
- Android Keystore encryption/tamper detection and drawer/password-free login navigation.

Test fixtures are generated locally or clearly synthetic. They are not provider
credentials and do not make live API calls.

## Device acceptance checklist

Record device model, Android version, browser/version, language/voice service, test
date, and outcome. Do not put passwords, OAuth tokens, callback URLs, or private chat
content in the report.

1. **Build / install:** pass unit tests and lint; build the actual debug APK; verify
   its signature with Android `apksigner verify`; install on an API 26+ device and API 35
   device/emulator. Confirm package `ai.norwix`, display name Norwix AI, and clean launch.
2. **Layout:** small phone/landscape/tablet; keyboard visible; long composer and Markdown
   code/tables; light/dark/system theme; 200% font scale; TalkBack labels/focus; reduce
   motion; screen-protection setting. Fix clipped text, unreachable controls, or contrast.
3. **ChatGPT login:** official issuer visible in browser; success, cancellation, declined
   plan permission, timeout, network failure, browser absent, and process death during
   sign-in. Account must appear only after ID-token verification. Verify loopback callback
   works in the device/browser combination; if not, keep it unavailable rather than
   substituting an undocumented URI or private endpoint.
4. **Multiple accounts/workspaces:** add two; even identical email labels must keep their
   client/subject/token sets separate. Switch accounts; refresh the server model catalog.
   Verify the next request uses only the selected session and the current chat's context.
5. **Native chat:** text, Markdown, long code, supported image/PDF/UTF-8 attachment; unsupported
   format/model; genuine partial stream; Stop; dropped network; HTTP 401/403/429; late
   provider failure after text. Only explicit completion may mark a reply complete.
6. **Renewal / logout:** test expiry and rotating refresh, revoked credentials, scope loss,
   successful revocation, and offline logout. Local tokens must clear; unconfirmed remote
   revocation must be reported. Other accounts must remain unaffected.
7. **Persistence:** create/rename/search/resume/delete chats; edit independent memory; opt
   into global notes for one chat only. Rotate, background, force-stop, restart, and reboot.
   An interrupted stream must remain partial; no unrelated chat's history may be sent.
8. **Voice:** permission accept/deny/permanent denial, missing recognizer, offline language
   pack, optional system service, partial/final dictation, selected TTS voice, Stop, incoming
   audio-focus loss, app backgrounding, and response completion while backgrounded. No
   background playback or recording should start unexpectedly.
9. **Browser providers:** official Custom Tab and fallback browser; Gemini and Claude sign-in
   and user-controlled account/model switching. Verify handoff is review → copy → paste →
   provider Send, never automatic. Verify source labels on pasted responses. Verify the
   saved conversation link resumes the official page and rejects foreign URLs.
10. **Privacy:** inspect backup exclusions; check encrypted local files; import and delete
    attachments; full erase; export JSON excludes all access/refresh/ID tokens and host
    identity. Export contains plaintext chat/memory by explicit confirmation only.

Passing these local/device checks still does not create permission to offer a provider
integration outside its documented terms. Recheck each provider's current documentation
for the intended distribution model.
