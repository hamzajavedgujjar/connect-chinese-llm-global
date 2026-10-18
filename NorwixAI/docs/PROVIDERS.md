# Provider integration evidence

Documentation reviewed: **18 October 2026**. Documentation access is not live account,
device, or subscription verification. Recheck before a release.

## ChatGPT

The documented open-source/local-app route supports dynamic user registration without
an API key or client secret. Norwix uses that route in this MIT-licensed source preview;
it does not assume a commercial client registration. A paid or remotely hosted edition
must use the appropriate participation/approval process.

Relevant official documentation:

- [OSS overview](https://developers.openai.com/siwc/token-sharing-open-source)
- [Registration and sign-in](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)
- [Account isolation, refresh and revocation](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions)
- [Models and inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)
- [Preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)
- [ID-token validation example](https://developers.openai.com/siwc/website)
- [Commercial client participation](https://developers.openai.com/siwc/request-client-id)
- [User-facing plan usage explanation](https://help.openai.com/en/articles/20001542-using-your-chatgpt-plan-in-other-apps-and-sites)

The source generates a persistent opaque host ID and fresh state, nonce, and S256 PKCE
values. It listens on IPv4 loopback, uses the returned issued client ID for code exchange,
verifies the ID-token signature with trusted JWKS, and checks identity claims before
saving. Permissions are read from returned token scopes rather than callback claims.
Each registration retains its own client/identity/token mapping. Refreshes are serialized.

The native inference route uses `GET https://api.openai.com/v1/models` and
`POST https://api.openai.com/v1/responses` with OAuth bearer credentials, `store:false`
and `stream:true`. Full required local history is submitted on each request. No private
website API or API-key fallback is present. Files API, provider transcription, video/audio
inputs, and unsupported hosted tools are omitted. Model access is account-specific.

An OAuth implementation in source is not proof that a particular plan, workspace,
Android browser, account, or regional rollout accepts the flow. No live authorization,
refresh, revocation, model access, image/PDF inference, or stream was verified here.

## Google Gemini

Sources:

- [Gemini API OAuth quickstart](https://ai.google.dev/gemini-api/docs/oauth)
- [Gemini API billing and project tiers](https://ai.google.dev/gemini-api/docs/billing)
- [Google AI plan management](https://support.google.com/gemini/answer/14517446?hl=en)
- [Official Gemini app](https://gemini.google.com/app)

The documented developer OAuth route requires a Google Cloud project and API
configuration. It does not establish that a consumer Google AI Pro subscription can
authorize a third-party native assistant. No such subscription connection was verified
for this project. Norwix therefore provides a normal browser visit to the official
Gemini interface. It does not claim OAuth-based native Gemini login or inference.
Account and model switching remain in Gemini's own UI.

## Claude

Sources:

- [Anthropic authentication and credential restrictions](https://code.claude.com/docs/en/legal-and-compliance)
- [Official Claude app](https://claude.ai/)

Anthropic distinguishes its own application's subscription OAuth use from third-party
product integrations, which should use supported developer authentication. Its stated
restrictions prohibit offering Claude.ai login inside a third-party application and
routing inference through subscription credentials. Norwix therefore does not collect
Claude tokens or implement a native Claude Pro integration. It only opens Claude's
provider-owned site in a browser surface, where the user can interact directly.

## Browser boundary

[Android Custom Tabs overview](https://developer.chrome.com/docs/android/custom-tabs)
describes the browser-managed page, shared browser state, and integrated return path.
Norwix uses that browser surface, preserving its security/identity UI, with a normal
browser fallback. It does not inspect cookies, inject JavaScript, scrape content, or
receive a success callback from Gemini/Claude site login. Website authentication and
independent website account switching have **not** been verified on a device here.
