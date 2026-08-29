#!/usr/bin/env python3
"""
Runs inside GitHub Actions, after `npx cap add android` has generated a
fresh android/ project. Copies our custom Java sources in and patches
the manifest + gradle file to wire up the Tapsell SDK.
"""
import os
import re
import shutil
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(ROOT)
ANDROID_DIR = os.path.join(PROJECT_ROOT, "android")
APP_ID_PATH = "com/voicemorf/app"

java_src = os.path.join(ROOT, "java", *APP_ID_PATH.split("/"))
java_dst = os.path.join(ANDROID_DIR, "app", "src", "main", "java", *APP_ID_PATH.split("/"))
os.makedirs(java_dst, exist_ok=True)
for fname in os.listdir(java_src):
    shutil.copy(os.path.join(java_src, fname), os.path.join(java_dst, fname))
    print("copied", fname)

# --- Manifest: add the AD_ID permission required by ad SDKs on API 33+ ---
manifest_path = os.path.join(ANDROID_DIR, "app", "src", "main", "AndroidManifest.xml")
with open(manifest_path, "r", encoding="utf-8") as f:
    manifest = f.read()
    
mic_perm = '    <uses-permission android:name="android.permission.RECORD_AUDIO" />\n'
mic_settings_perm = '    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />\n'

ad_id_perm = '    <uses-permission android:name="com.google.android.gms.permission.AD_ID" />\n'
if "com.google.android.gms.permission.AD_ID" not in manifest:
    marker = "<application"
    idx = manifest.index(marker)
    manifest = manifest[:idx] + mic_perm + mic_settings_perm + ad_id_perm + manifest[idx:]
    with open(manifest_path, "w", encoding="utf-8") as f:
        f.write(manifest)
    print("patched AndroidManifest.xml")
else:
    print("AndroidManifest.xml already had AD_ID permission")

# --- app/build.gradle: append the Tapsell dependency in its own block ---
gradle_path = os.path.join(ANDROID_DIR, "app", "build.gradle")
with open(gradle_path, "r", encoding="utf-8") as f:
    gradle_content = f.read()

if "tapsell-plus-sdk-android" not in gradle_content:
    with open(gradle_path, "a", encoding="utf-8") as f:
        f.write(
            "\n\n"
            "// --- Tapsell ad SDK (added automatically by android-patches/apply_patches.py) ---\n"
            "dependencies {\n"
            '    implementation("ir.tapsell.plus:tapsell-plus-sdk-android:2.3.3")\n'
            '    implementation("androidx.multidex:multidex:2.0.1")\n'
            "}\n"
        )
    print("appended Tapsell + multidex dependency to app/build.gradle")
    gradle_content = gradle_content  # re-read below anyway
else:
    print("app/build.gradle already had the Tapsell dependency")

# Re-read after the possible append above so the edits below see the
# dependencies block too (not strictly required, but keeps this robust
# if this script's steps are ever reordered).
with open(gradle_path, "r", encoding="utf-8") as f:
    gradle_content = f.read()

# --- Enable multidex on the app module. Tapsell's own changelog notes
# dexing-pipeline-related runtime issues with their SDK on some setups
# (e.g. "Fixed PreRoll Media3 Crash on Android 6.0 by adding
# android.enableDexingArtifactTransform=false"). A silently-missing
# plugin registration (compiles fine, not present at runtime) is the
# classic signature of a dex/class-verification issue, so we enable
# multidex defensively — it's always safe to turn on, never a downside. ---
if "multiDexEnabled" not in gradle_content:
    gradle_content = re.sub(
        r"(defaultConfig\s*\{)",
        r"\1\n        multiDexEnabled true",
        gradle_content,
        count=1,
    )
    with open(gradle_path, "w", encoding="utf-8") as f:
        f.write(gradle_content)
    print("enabled multiDexEnabled true in app/build.gradle")
else:
    print("app/build.gradle already had multiDexEnabled")

# --- Ensure Java 8 compileOptions, as Tapsell's own setup docs call out
# explicitly (https://docs.tapsell.ir/en/plus-sdk/android/initialize/).
# Modern Capacitor templates already default higher than this, but we
# make sure explicitly rather than assume. ---
with open(gradle_path, "r", encoding="utf-8") as f:
    gradle_content = f.read()

if "compileOptions" not in gradle_content:
    gradle_content = re.sub(
        r"(android\s*\{)",
        r"\1\n    compileOptions {\n        sourceCompatibility JavaVersion.VERSION_1_8\n        targetCompatibility JavaVersion.VERSION_1_8\n    }",
        gradle_content,
        count=1,
    )
    with open(gradle_path, "w", encoding="utf-8") as f:
        f.write(gradle_content)
    print("added explicit Java 8 compileOptions to app/build.gradle")
else:
    print("app/build.gradle already had a compileOptions block, leaving it alone")

# --- gradle.properties: disable the dexing artifact transform. This is
# the exact flag Tapsell's own changelog says fixes a class of crashes
# caused by their SDK interacting badly with Android's per-module dexing
# pipeline. Safe to set even if unrelated to our exact symptom. ---
gradle_props_path = os.path.join(ANDROID_DIR, "gradle.properties")
props_line = "android.enableDexingArtifactTransform=false\n"
existing_props = ""
if os.path.isfile(gradle_props_path):
    with open(gradle_props_path, "r", encoding="utf-8") as f:
        existing_props = f.read()

if "android.enableDexingArtifactTransform" not in existing_props:
    with open(gradle_props_path, "a", encoding="utf-8") as f:
        f.write("\n" + props_line)
    print("added android.enableDexingArtifactTransform=false to gradle.properties")
else:
    print("gradle.properties already had android.enableDexingArtifactTransform")

print("Patches applied successfully.")

# --- Version bump for Bazaar/Play releases -----------------------------
# android/ gets wiped and regenerated from scratch on every CI run
# (`rm -rf android && npx cap add android`), which always resets
# versionCode/versionName back to the Capacitor template defaults
# (1 / "1.0"). Editing android/app/build.gradle directly in the repo
# would just get thrown away on the next run.
#
# Instead, the version to ship lives in this one small file:
#     android-patches/version.txt
# Format (two lines):
#     versionCode=<integer, must go up by at least 1 each Bazaar release>
#     versionName=<string shown to users, e.g. 1.1>
#
# Bump versionCode (and usually versionName too) in that file before
# each new Bazaar upload, commit + push, and this script applies it to
# the freshly-generated build.gradle automatically every time.
version_file = os.path.join(ROOT, "version.txt")
default_version_code = 1
default_version_name = "1.0"

if not os.path.isfile(version_file):
    with open(version_file, "w", encoding="utf-8") as f:
        f.write(f"versionCode={default_version_code}\nversionName={default_version_name}\n")
    print(f"Created {version_file} with defaults — edit this file to bump the version next time.")
    version_code = default_version_code
    version_name = default_version_name
else:
    version_code = default_version_code
    version_name = default_version_name
    with open(version_file, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line.startswith("versionCode="):
                version_code = int(line.split("=", 1)[1].strip())
            elif line.startswith("versionName="):
                version_name = line.split("=", 1)[1].strip()
    print(f"Read version.txt: versionCode={version_code}, versionName={version_name}")

with open(gradle_path, "r", encoding="utf-8") as f:
    gradle_content = f.read()

gradle_content = re.sub(r"versionCode\s+\d+", f"versionCode {version_code}", gradle_content, count=1)
gradle_content = re.sub(r'versionName\s+"[^"]*"', f'versionName "{version_name}"', gradle_content, count=1)

with open(gradle_path, "w", encoding="utf-8") as f:
    f.write(gradle_content)
print(f"Set versionCode={version_code}, versionName={version_name} in app/build.gradle")
