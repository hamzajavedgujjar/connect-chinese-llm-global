#!/usr/bin/env bash
set -euo pipefail
norwix_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$norwix_root"
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
mkdir -p deliverables
cp app/build/outputs/apk/debug/app-debug.apk deliverables/Norwix-AI-debug.apk
echo 'Built deliverables/Norwix-AI-debug.apk. Install and finish the live-provider/device verification matrix before distribution.'
