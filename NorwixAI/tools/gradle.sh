#!/usr/bin/env bash
set -euo pipefail
norwix_version=8.11.1
norwix_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
norwix_cache="${NORWIX_BUILD_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/norwix-build-tools}"
norwix_gradle="$norwix_cache/gradle-$norwix_version/bin/gradle"
if [[ ! -x "$norwix_gradle" ]]; then
    if command -v gradle >/dev/null 2>&1 && gradle --version 2>/dev/null | grep -q '^Gradle 8\.11\.1$'; then
        cd "$norwix_root"
        exec gradle "$@"
    fi
    command -v curl >/dev/null || { echo 'Install curl, or Gradle 8.11.1, to bootstrap the build.' >&2; exit 2; }
    command -v unzip >/dev/null || { echo 'Install unzip, or Gradle 8.11.1, to bootstrap the build.' >&2; exit 2; }
    mkdir -p "$norwix_cache"
    norwix_temp="$(mktemp -d "$norwix_cache/download.XXXXXXXX")"
    trap 'rm -rf -- "$norwix_temp"' EXIT
    norwix_url="https://services.gradle.org/distributions/gradle-$norwix_version-bin.zip"
    echo "Downloading official Gradle $norwix_version and its published SHA-256 checksum."
    curl --fail --location --silent --show-error --connect-timeout 10 --max-time 180 "$norwix_url" -o "$norwix_temp/gradle.zip"
    curl --fail --location --silent --show-error --connect-timeout 10 --max-time 30 "$norwix_url.sha256" -o "$norwix_temp/gradle.sha256"
    norwix_expected="$(tr -d '[:space:]' < "$norwix_temp/gradle.sha256")"
    if command -v sha256sum >/dev/null 2>&1; then
        norwix_actual="$(sha256sum "$norwix_temp/gradle.zip" | cut -d ' ' -f 1)"
    else
        norwix_actual="$(shasum -a 256 "$norwix_temp/gradle.zip" | cut -d ' ' -f 1)"
    fi
    [[ "$norwix_expected" =~ ^[a-fA-F0-9]{64}$ && "$norwix_actual" == "$norwix_expected" ]] || {
        echo 'Gradle checksum verification failed; nothing was executed.' >&2; exit 3;
    }
    unzip -q "$norwix_temp/gradle.zip" -d "$norwix_temp"
    mv "$norwix_temp/gradle-$norwix_version" "$norwix_cache/"
    rm -rf -- "$norwix_temp"
    trap - EXIT
fi
cd "$norwix_root"
exec "$norwix_gradle" "$@"
