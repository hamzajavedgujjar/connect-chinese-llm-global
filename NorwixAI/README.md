# Norwix AI — Android source preview

**Status: source implementation for review; not a verified, finished APK release.**

This package contains a Kotlin / Jetpack Compose Android project, build configuration,
local-data and OAuth implementation, tests, build scripts, and CI workflow. **No APK was
compiled, installed, or tested in the authoring environment.** It had a Java runtime but
no JDK compiler, Android SDK, Gradle distribution, ADB, or emulator; attempts to download
the required toolchain returned HTTP 403 under its network policy. Kotlin compilation,
dependency resolution, Android lint, instrumented tests, and live-provider login tests
therefore remain pending. Read [VERIFICATION.md](docs/VERIFICATION.md) before using or
distributing a build. There are no bundled mock AI responses or renamed binaries.

## Provider access

| Provider | Implemented connection | Native AI responses | Important boundary |
| --- | --- | --- | --- |
| ChatGPT | Official **open-source/local-app** dynamic OAuth registration, Authorization Code + PKCE, OIDC identity validation, per-account encrypted credentials | Source implements account model discovery and Responses SSE with OAuth bearer tokens; **not live-tested** | Eligible users must explicitly grant ChatGPT plan usage. This is a documented preview, not an API-key connection. A commercial/hosted product needs the appropriate OpenAI approval. |
| Gemini / Google AI Pro | Official Gemini site in Android Custom Tabs, or a browser fallback | No native subscription inference integration | Account switching, model selection, sending, files, voice, and history on Gemini's site belong to Gemini. Developer API OAuth is a separate Cloud-project integration. |
| Claude / Claude Pro | Official Claude site in Android Custom Tabs, or a browser fallback | No native subscription inference integration | Anthropic prohibits third-party Claude.ai login and routing inference through subscription credentials. No Claude OAuth tokens or browser sessions are collected. |

The ChatGPT implementation follows the current public OSS flow documented at
[OpenAI's registration guide](https://developers.openai.com/siwc/token-sharing-open-source/sign-in).
It requests plan-usage consent and sends OAuth-authenticated requests only to the
documented public endpoints. It never uses private ChatGPT website endpoints.
Eligibility and Android loopback behavior must still be tested with a real account.
This app does not claim affiliation with any provider.

All authentication occurs on provider-owned pages. There is no provider password field,
API-key setting, Norwix billing service, token import form, cookie scraping, or hidden
WebView. Existing ChatGPT conversations and memories are not imported by OAuth.
See [PROVIDERS.md](docs/PROVIDERS.md) for official references and limitations.

## What the source implements

- Midnight, Pearl, and Android-following themes; sculpted gradient orb, soft clay-like
  surfaces, glass-style borders, adaptive content widths, reduced motion option,
  edge-to-edge layout, RTL support, and labelled navigation controls.
- Minimal conversation screen with a wide, compact composer, Send / Stop, document
  and image pickers, dictation, speech playback, Markdown, code blocks, and tables.
- ChatGPT account-specific model catalog, explicit completion detection, partial
  response preservation, cancellation, and account switching. No hardcoded model list.
- New, rename, search, delete, and resume local conversations; drafts; message deletion;
  independent chat memory; optional global preferences, off by default for each chat.
- AES-256-GCM encryption of workspace data and attachment copies with an Android
  Keystore key; atomic persistence; backup exclusion; no analytics or Norwix backend.
- Android speech recognition and installed TTS voices; runtime microphone permission;
  on-device-only recognition and offline-only voices by default; audio focus and
  lifecycle interruption handling. This is Android speech, not provider voice cloning
  or a real-time provider audio API.
- Explicit plaintext export of local history/memory, excluding all sign-in credentials
  and attachment bytes; full local deletion including the encryption key.

These are **implemented source paths, not passed device-test claims**. The appearance
has not been rendered on an Android emulator in this environment.

## Build prerequisites

Install Android Studio with:

- JDK 17 (a JDK, including `javac`, rather than only a Java runtime).
- Android SDK Platform 35 and Build Tools 35.0.0; platform tools for `adb`.
- Gradle 8.11.1. Android Gradle Plugin 8.9.1, Kotlin 2.1.20, and the Compose BOM are pinned.
- Network access to Gradle, Google's Maven repository, Maven Central, and the Android
  SDK distribution servers for the first build.

Open this folder in Android Studio. Set the Gradle JDK to 17. Configure your SDK via
Android Studio or a **local, uncommitted** `local.properties`, for example:

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

The archive has no Gradle wrapper JAR because downloads were blocked. Its `gradlew`
and `gradlew.bat` are transparent **source bootstrap scripts**, not the stock wrapper.
They download the pinned official distribution and verify its published SHA-256 checksum.
You can instead use an installed Gradle 8.11.1, or generate the stock wrapper after
installing Gradle: `gradle wrapper --gradle-version 8.11.1`.

Linux/macOS:

```bash
chmod +x gradlew tools/*.sh
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
# Or build and copy a successful APK to a friendly filename:
bash tools/build.sh
```

Windows PowerShell (with Java and Android SDK configured):

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

If your Windows policy prevents local PowerShell scripts, build using Android Studio
or an already installed Gradle 8.11.1; the bootstrap does not change machine policy.

After a **successful** build the actual APK is:

```text
app/build/outputs/apk/debug/app-debug.apk
```

`tools/build.sh` additionally copies that successful binary to
`deliverables/Norwix-AI-debug.apk`. That directory is not present in the delivered
source preview because the build did not run successfully. Debug builds are signed
by the Android build tools for installation; production signing is your own separate
keystore and is not included in this source package.

Install and run device tests with a connected Android device or emulator:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew --no-daemon :app:connectedDebugAndroidTest
```

The included GitHub Actions workflow builds, lints, runs unit tests, then runs the
instrumented smoke tests on API 35 before uploading an APK artifact. **The workflow
has not been run here.** It requires no provider credentials. Live-provider acceptance
must still be performed separately; CI cannot claim provider logins were tested.

## First use after a successful build

1. Open the hamburger menu → **Login Accounts** → **Continue with ChatGPT**.
2. Complete the official OpenAI sign-in and optional plan-usage consent in the browser.
   Close the tab after it reports the callback was received. A rejected or unavailable
   flow produces an error; no account is presented as connected without validation.
3. In **Models & provider**, select a model returned for that account. Send a message.
   Only a provider `response.completed` event marks the assistant reply complete.
4. For another ChatGPT account/workspace, add a separate connection in Login Accounts.
   Account registrations are not merged by email. Selecting one affects the current
   local conversation and the account used on its next request.
5. For Gemini or Claude, select browser mode. Sending a local draft opens a review.
   **Copy & open** copies that reviewed context, opens the official page, and lets you
   paste/send there. Attach local files separately in the provider page. Norwix cannot
   auto-send, inspect responses, verify the active website account, or isolate browser
   cookies. Add a copied response manually if you want a local record; it is visibly
   labelled **pasted by you / unverified**.
6. Configure speech, appearance, memory, and privacy in the same menu.

## Data behavior and limits

- Workspace state lives only in app-private, backup-excluded storage. Encryption keys
  remain in Android Keystore; hardware backing depends on the device. Losing the key
  prevents decryption. The app offers retry or explicit erase; it does not silently reset.
- Local chat memory is an editable note, not an invisible automatic summary. History is
  sent per conversation. Incomplete assistant replies are excluded from subsequent context.
- Global preferences are included only for individually opted-in conversations.
- Up to six attachments per message, 10 MB each; 20 MB of attachment bytes in a native
  request; 200 MB local archive cap. Native requests accept supported images, PDFs, and
  bounded UTF-8 text. The model/provider may reject an input it does not support.
- Text context has a visible 120,000-character local limit; composer drafts are bounded
  at 60,000 characters. A larger history produces an explicit error instead of silently
  dropping earlier turns. Start a new chat and save a summary as memory when needed.
- The native route does not use the Files API, transcription API, audio/video input,
  hosted tools, or remote conversation persistence. Android voice fills a text draft.
- A process kill interrupts a request. Saved partial text is marked interrupted after
  restart. Requests are not automatically retried; a retry could consume more plan usage.
- Exports and deliberate clipboard copies are plaintext outside the vault. Deleting
  an imported attachment removes its encrypted Norwix copy, not the original file.
- Local erase does not revoke browser sessions or delete provider-side history. Sign-out
  attempts remote session revocation and reports if it cannot confirm it. Account grants
  can be reviewed/disconnected in the provider's own settings.

## Source map

| Folder | Responsibility |
| --- | --- |
| `app/src/main/java/ai/norwix/domain` | Conversation types, context isolation, URL policy, PKCE, SSE state machine |
| `.../data` | Encrypted vault, atomic repository, bounded attachment import |
| `.../network` | Official discovery/JWKS verification, loopback OAuth, renewal/revocation, models and streaming |
| `.../platform` | Secure browser handoff and Android voice lifecycle |
| `.../ui` | Compose UI, themes, settings, ViewModel and state management |
| `app/src/test` | Unit tests of isolation, exports, storage failure, OAuth identity, callback security, and stream completion |
| `app/src/androidTest` | Keystore tamper test and navigation smoke test |
| `docs` | Provider evidence, architecture, verification status and device acceptance checklist |
| `tools` | Build bootstrap and offline source preflight |

Licensed under MIT. Third-party dependencies retain their own licenses.
