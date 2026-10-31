#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if [ ! -f Config/Local.xcconfig ]; then
  cp Config/Local.xcconfig.example Config/Local.xcconfig
fi
swift test
if command -v xcodegen >/dev/null 2>&1; then
  xcodegen generate
fi
xcodebuild -quiet -project Qingxin.xcodeproj -scheme Qingxin \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath build CODE_SIGNING_ALLOWED=NO build
