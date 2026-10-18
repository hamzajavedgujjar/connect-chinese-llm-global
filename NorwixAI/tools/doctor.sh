#!/usr/bin/env bash
set -u
norwix_missing=0
for norwix_tool in java javac sdkmanager adb; do
    if command -v "$norwix_tool" >/dev/null 2>&1; then
        printf 'FOUND: %s\n' "$norwix_tool"
    else
        printf 'MISSING: %s\n' "$norwix_tool"
        norwix_missing=1
    fi
done
if command -v gradle >/dev/null 2>&1; then
    printf 'FOUND: installed Gradle (use 8.11.1)\n'
else
    printf 'MISSING: installed Gradle; bootstrap needs network access\n'
fi
if command -v emulator >/dev/null 2>&1; then
    printf 'FOUND: Android emulator binary\n'
else
    printf 'MISSING: Android emulator binary (use a connected device for device tests)\n'
fi
exit "$norwix_missing"
