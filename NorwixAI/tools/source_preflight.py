#!/usr/bin/env python3
"""Offline source contracts only. This is NOT Kotlin compilation or Android testing."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
checks = []


def check(name, condition):
    if not condition:
        raise AssertionError(name)
    checks.append(name)


def lexical_delimiters(source, filename):
    """Balance Kotlin lexical delimiters, including strings with nested interpolation."""
    size = len(source)

    def string(pos, raw=False):
        end = '"""' if raw else '"'
        pos += len(end)
        while pos < size:
            if source.startswith(end, pos):
                return pos + len(end)
            if not raw and source[pos] == "\\":
                pos += 2
            elif source.startswith("${", pos):
                pos = code(pos + 2, ["{"], interpolation=True)
            else:
                pos += 1
        raise AssertionError(f"{filename}: unterminated string")

    def code(pos, stack, interpolation=False):
        while pos < size:
            if source.startswith("//", pos):
                line = source.find("\n", pos)
                pos = size if line < 0 else line + 1
                continue
            if source.startswith("/*", pos):
                depth = 1
                pos += 2
                while pos < size and depth:
                    if source.startswith("/*", pos):
                        depth += 1
                        pos += 2
                    elif source.startswith("*/", pos):
                        depth -= 1
                        pos += 2
                    else:
                        pos += 1
                if depth:
                    raise AssertionError(f"{filename}: unterminated comment")
                continue
            if source.startswith('"""', pos):
                pos = string(pos, raw=True)
                continue
            if source[pos] == '"':
                pos = string(pos)
                continue
            if source[pos] == "'":
                pos += 1
                while pos < size:
                    if source[pos] == "\\":
                        pos += 2
                    elif source[pos] == "'":
                        pos += 1
                        break
                    else:
                        pos += 1
                else:
                    raise AssertionError(f"{filename}: unterminated character literal")
                continue
            char = source[pos]
            if char in "([{":
                stack.append(char)
            elif char in ")]}" :
                expected = {")": "(", "]": "[", "}": "{"}[char]
                if not stack or stack.pop() != expected:
                    raise AssertionError(f"{filename}: unmatched delimiter at offset {pos}")
                if interpolation and not stack:
                    return pos + 1
            pos += 1
        if stack:
            raise AssertionError(f"{filename}: unclosed delimiters {stack}")
        return pos

    code(0, [])


def main():
    required = ["settings.gradle.kts", "build.gradle.kts", "app/build.gradle.kts", "gradlew",
                "gradlew.bat", "tools/gradle.sh", "tools/gradle.ps1", "README.md", "LICENSE",
                ".github/workflows/android.yml", "docs/PROVIDERS.md", "docs/VERIFICATION.md"]
    check("Project, build, documentation, and CI files exist", all((ROOT / p).is_file() for p in required))

    kotlin = sorted(ROOT.rglob("*.kt")) + sorted(ROOT.rglob("*.kts"))
    for file in kotlin:
        lexical_delimiters(file.read_text(), str(file.relative_to(ROOT)))
    check(f"Lexical delimiter checks across {len(kotlin)} Kotlin / Gradle files", bool(kotlin))

    xml_files = list((ROOT / "app/src/main").rglob("*.xml"))
    for file in xml_files:
        ET.parse(file)
    check(f"XML well-formedness across {len(xml_files)} resources / manifest files", bool(xml_files))

    ns = "{http://schemas.android.com/apk/res/android}"
    manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
    application = manifest.find("application")
    check("Android backup and cleartext traffic disabled", application.get(ns + "allowBackup") == "false"
          and application.get(ns + "usesCleartextTraffic") == "false")
    permissions = {item.get(ns + "name") for item in manifest.findall("uses-permission")}
    check("Only Internet and microphone permissions declared", permissions == {
        "android.permission.INTERNET", "android.permission.RECORD_AUDIO"})
    exported = [item for item in application if item.get(ns + "exported") == "true"]
    check("Only launcher Activity exported", len(exported) == 1 and exported[0].tag == "activity")
    extraction = ET.parse(ROOT / "app/src/main/res/xml/data_extraction_rules.xml").getroot()
    check("Cloud backup and device-transfer data excluded", len(extraction.findall("./cloud-backup/exclude")) >= 4
          and len(extraction.findall("./device-transfer/exclude")) >= 4)

    code_root = ROOT / "app/src/main/java"
    sources = {str(file.relative_to(code_root)): file.read_text() for file in code_root.rglob("*.kt")}
    joined = "\n".join(sources.values())
    check("No private provider website inference endpoints", not any(token in joined for token in
          ["backend-api", "api.anthropic.com", "generativelanguage.googleapis.com"]))
    check("No embedded WebView / browser cookie scraping", not any(token in joined for token in
          ["android.webkit.WebView", "CookieManager", "evaluateJavascript"]))
    check("No API-key configuration or Authorization Basic", not any(token in joined for token in
          ["OPENAI_API_KEY", "x-api-key", "api_key=", '"Basic ']))
    check("No empty clickable button callbacks", re.search(r"onClick\s*=\s*\{\s*\}", joined) is None)

    vault = sources["ai/norwix/data/EncryptedVault.kt"]
    check("Authenticated Keystore encryption and atomic records present", all(token in vault for token in
          ["AndroidKeyStore", "AES/GCM/NoPadding", "updateAAD", "setKeySize(256)", "AtomicFile"]))
    auth = sources["ai/norwix/network/ChatGptAuth.kt"]
    check("Official public-client OAuth, PKCE, host ID, scopes, and verifier wired", all(token in auth for token in
          ["dynamic_agent_client", "code_verifier", "code_challenge", "ext_agent_host_id",
           "chatgpt.tokens.use.direct", "verifier.verify", "refresh_token"]))
    identity = sources["ai/norwix/network/IdentityVerifier.kt"]
    check("Signature and issuer/audience/time/nonce checks present", all(token in identity for token in
          ["jwt.verify", "claims.issuer", "claims.audience", "expirationTime", "issueTime", 'getStringClaim("nonce")']))
    network = sources["ai/norwix/network/OpenAiChat.kt"]
    check("Public Responses endpoint and native streaming completion state wired", all(token in network for token in
          ["https://api.openai.com/v1/responses", 'put("store", false)', 'put("stream", true)',
           "ResponseAccumulator", "accumulator.finish()", "onText"]))
    check("Explicit test source exists for core and device security", len(list((ROOT / "app/src/test").rglob("*Test.kt"))) >= 3
          and len(list((ROOT / "app/src/androidTest").rglob("*Test.kt"))) >= 2)
    check("No APK falsely included in source tree", not list(ROOT.rglob("*.apk")))

    for name in checks:
        print(f"PASS: {name}")
    print(f"\n{len(checks)} offline source contracts passed.")
    print("NOT RUN: Kotlin compiler, Android lint, unit/device tests, APK installation, live provider login/inference.")
    print("These contracts are not a substitute for the pending checks in docs/VERIFICATION.md.")


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, ET.ParseError) as failure:
        print(f"FAIL: {failure}", file=sys.stderr)
        sys.exit(1)
