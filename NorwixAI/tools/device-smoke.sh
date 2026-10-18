#!/usr/bin/env bash
set -euo pipefail
gradle --no-daemon --stacktrace :app:connectedDebugAndroidTest
mkdir -p deliverables
adb exec-out run-as ai.norwix tar -C files -cf - ui-smoke > deliverables/ui-smoke.tar
tar -xf deliverables/ui-smoke.tar -C deliverables
rm deliverables/ui-smoke.tar
