#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Build a public, unsigned main-app IPA without reading private local settings."""
import argparse
import hashlib
import json
import plistlib
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path


def run(*args, **kwargs):
    subprocess.run(args, check=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, help="Output directory; defaults to dist/ios-public")
    options = parser.parse_args()
    ios_root = Path(__file__).resolve().parents[1]
    repo_root = ios_root.parent
    output = (options.output or repo_root / "dist" / "ios-public").resolve()
    version = "0.1.0"
    for tool in ("swift", "xcodebuild", "xcodegen"):
        if not shutil.which(tool):
            raise SystemExit(f"Missing required tool: {tool}")
    run("swift", "test", "--package-path", str(ios_root))
    output.mkdir(parents=True, exist_ok=True)

    # Generate a separate project instead of loading the developer's Local.xcconfig.
    # Copy only public sources so all generated project paths stay within one root.
    with tempfile.TemporaryDirectory(prefix="qingxin-public-") as temporary:
        work = Path(temporary)
        shutil.copytree(ios_root / "App", work / "App")
        core = work / "QingxinCore"
        core.mkdir()
        shutil.copy2(ios_root / "Package.swift", core / "Package.swift")
        shutil.copytree(ios_root / "Sources", core / "Sources")
        shutil.copytree(ios_root / "Tests", core / "Tests")
        licenses = work / "Licenses"
        licenses.mkdir()
        shutil.copy2(repo_root / "LICENSES" / "AGPL-3.0.txt", licenses / "AGPL-3.0.txt")
        shutil.copy2(repo_root / "NOTICE", licenses / "NOTICE")
        info = {
            "CFBundleDisplayName": "清新签到",
            "CFBundleShortVersionString": version,
            "CFBundleVersion": "1",
            "UILaunchScreen": {},
            "UIApplicationSceneManifest": {"UIApplicationSupportsMultipleScenes": False},
            "UISupportedInterfaceOrientations": ["UIInterfaceOrientationPortrait", "UIInterfaceOrientationLandscapeLeft", "UIInterfaceOrientationLandscapeRight"],
            "UISupportedInterfaceOrientations~ipad": ["UIInterfaceOrientationPortrait", "UIInterfaceOrientationPortraitUpsideDown", "UIInterfaceOrientationLandscapeLeft", "UIInterfaceOrientationLandscapeRight"],
            "BGTaskSchedulerPermittedIdentifiers": ["com.vel.qingxin.refresh"],
            "UIBackgroundModes": ["fetch"],
            "QingxinBootstrap": "",
            "NSAppTransportSecurity": {"NSAllowsArbitraryLoads": True, "NSAllowsArbitraryLoadsInWebContent": True},
            "CFBundleURLTypes": [{"CFBundleURLSchemes": ["qingxin"], "CFBundleURLName": "com.vel.qingxin"}],
        }
        spec = {
            "name": "QingxinPublic",
            "options": {"deploymentTarget": {"iOS": "17.0"}, "createIntermediateGroups": False},
            "packages": {"QingxinCore": {"path": "QingxinCore"}},
            "settings": {"base": {
                "SWIFT_VERSION": "5.0", "TARGETED_DEVICE_FAMILY": "1,2",
                "MARKETING_VERSION": version, "CURRENT_PROJECT_VERSION": "1",
                "CODE_SIGNING_ALLOWED": "NO", "CODE_SIGNING_REQUIRED": "NO",
                "ENABLE_USER_SCRIPT_SANDBOXING": "YES",
            }},
            "targets": {"Qingxin": {
                "type": "application", "platform": "iOS",
                "sources": [{"path": "App"}, {"path": "Licenses", "buildPhase": "resources"}],
                "dependencies": [{"package": "QingxinCore"}],
                "settings": {"base": {"PRODUCT_BUNDLE_IDENTIFIER": "com.vel.qingxin", "ASSETCATALOG_COMPILER_APPICON_NAME": "AppIcon"}},
                "info": {"path": "Public-Info.plist", "properties": info},
            }},
            "schemes": {"Qingxin": {"build": {"targets": {"Qingxin": "all"}}}},
        }
        spec_path = work / "project.json"
        spec_path.write_text(json.dumps(spec), encoding="utf-8")
        run("xcodegen", "generate", "--no-env", "--spec", str(spec_path), "--project", str(work), "--project-root", str(work))
        derived = work / "derived"
        run("xcodebuild", "-quiet", "-project", str(work / "QingxinPublic.xcodeproj"), "-scheme", "Qingxin", "-configuration", "Release", "-sdk", "iphoneos", "-destination", "generic/platform=iOS", "-derivedDataPath", str(derived), "CODE_SIGNING_ALLOWED=NO", "CODE_SIGNING_REQUIRED=NO", "build")
        app = derived / "Build" / "Products" / "Release-iphoneos" / "Qingxin.app"
        with (app / "Info.plist").open("rb") as stream:
            app_info = plistlib.load(stream)
        if app_info.get("QingxinBootstrap") != "" or "QingxinAppGroup" in app_info:
            raise RuntimeError("Public package unexpectedly contains bootstrap or App Group configuration")
        if app_info.get("CFBundleShortVersionString") != version or set(app_info.get("UIDeviceFamily", [])) != {1, 2}:
            raise RuntimeError(f"Unexpected metadata: version={app_info.get('CFBundleShortVersionString')}, families={app_info.get('UIDeviceFamily')}")
        if any(p.name in {"embedded.mobileprovision", "_CodeSignature", "PlugIns"} for p in app.rglob("*")):
            raise RuntimeError("Public package unexpectedly contains signing material or extensions")
        if not (app / "AGPL-3.0.txt").is_file() or not (app / "NOTICE").is_file():
            raise RuntimeError("Missing bundled license or attribution notice")
        ipa = output / f"Qingxin-iOS-{version}-unsigned.ipa"
        with zipfile.ZipFile(ipa, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for path in sorted(app.rglob("*")):
                if path.is_file():
                    archive.write(path, str(Path("Payload") / app.name / path.relative_to(app)))
        digest = hashlib.sha256(ipa.read_bytes()).hexdigest()
        (output / "SHA256SUMS.txt").write_text(f"{digest}  {ipa.name}\n", encoding="utf-8")
        print(f"Public unsigned IPA: {ipa}")
        print(f"SHA-256: {digest}")


if __name__ == "__main__":
    try:
        main()
    except (subprocess.CalledProcessError, RuntimeError) as error:
        print(f"Build failed: {error}", file=sys.stderr)
        sys.exit(1)
