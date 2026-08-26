#!/usr/bin/env python3
"""
Runs inside GitHub Actions, after `npx cap add android` has generated a
fresh android/ project. Copies our custom Java sources in and patches
the manifest + gradle file to wire up the Tapsell SDK.
"""
import os
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
storage_perm = '    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28" />\n'

ad_id_perm = '    <uses-permission android:name="com.google.android.gms.permission.AD_ID" />\n'
if "com.google.android.gms.permission.AD_ID" not in manifest:
    marker = "<application"
    idx = manifest.index(marker)
    manifest = manifest[:idx] + mic_perm + mic_settings_perm + storage_perm + ad_id_perm + manifest[idx:]
    with open(manifest_path, "w", encoding="utf-8") as f:
        f.write(manifest)
    print("patched AndroidManifest.xml")
else:
    print("AndroidManifest.xml already had AD_ID permission")

# --- app/build.gradle: append the Tapsell dependency in its own block ---
gradle_path = os.path.join(ANDROID_DIR, "app", "build.gradle")
with open(gradle_path, "a", encoding="utf-8") as f:
    f.write(
        "\n\n"
        "// --- Tapsell ad SDK (added automatically by android-patches/apply_patches.py) ---\n"
        "dependencies {\n"
        '    implementation("ir.tapsell.plus:tapsell-plus-sdk-android:2.3.3")\n'
        "}\n"
    )
print("appended Tapsell dependency to app/build.gradle")

print("Patches applied successfully.")
