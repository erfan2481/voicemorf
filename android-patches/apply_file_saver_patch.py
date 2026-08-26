#!/usr/bin/env python3
"""
Injects a native "FileSaver" Capacitor plugin into the freshly-generated
android/ project (created each CI run via `npx cap add android`) and wires
it into MainActivity.java.

Design (two-step, crash-safe):
  1. JS calls FileSaver.writeCache({ data, fileName }) — writes the base64
     payload straight to the app's own cache directory while the app is
     still fully in the foreground. Fast, no system UI involved, so it
     can't be interrupted by Android backgrounding/killing the process.
  2. JS calls FileSaver.saveFileAs({ cachePath, fileName, mimeType }) —
     opens Android's native "Save As" (Storage Access Framework) picker.
     Only the small cachePath string needs to survive whatever the OS
     does to our process while that system screen is on top; once the
     user picks a destination, we just stream-copy the already-on-disk
     cached file over to it and delete the cache copy.

This avoids ever holding a large payload in the PluginCall that has to
survive an Activity-for-result round trip, which is what was causing the
app to appear to "crash and exit" with a 0-byte result file: some devices
(especially aggressive OEM Android like MIUI/EMUI) kill backgrounded apps
holding large in-memory payloads while a heavy system picker is open.

Run this AFTER `npx cap add android` and BEFORE the Gradle build step.
"""

import os
import re
import sys

APP_ID = "com.voicemorf.app"
PACKAGE_PATH = os.path.join(
    "android", "app", "src", "main", "java", *APP_ID.split(".")
)
PLUGIN_FILE = os.path.join(PACKAGE_PATH, "FileSaverPlugin.java")
MAIN_ACTIVITY = os.path.join(PACKAGE_PATH, "MainActivity.java")

PLUGIN_JAVA = f"""package {APP_ID};

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;

@CapacitorPlugin(name = "FileSaver")
public class FileSaverPlugin extends Plugin {{

    private static final String CACHE_SUBDIR = "file_saver_tmp";

    /**
     * Step 1: write the base64 payload to a private cache file. Runs fully
     * in the foreground — no system UI, so nothing can interrupt it.
     */
    @PluginMethod
    public void writeCache(PluginCall call) {{
        String fileName = call.getString("fileName", "file");
        String data = call.getString("data");

        if (data == null || data.isEmpty()) {{
            call.reject("Missing 'data' (base64 file content)");
            return;
        }}

        try {{
            File dir = new File(getContext().getCacheDir(), CACHE_SUBDIR);
            if (!dir.exists()) dir.mkdirs();

            String safeName = System.currentTimeMillis() + "_" + fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
            File outFile = new File(dir, safeName);

            byte[] bytes = Base64.decode(data, Base64.DEFAULT);
            try (FileOutputStream fos = new FileOutputStream(outFile)) {{
                fos.write(bytes);
                fos.flush();
            }}

            JSObject ret = new JSObject();
            ret.put("path", outFile.getAbsolutePath());
            call.resolve(ret);
        }} catch (Throwable t) {{
            call.reject("Failed to write cache file: " + t.getMessage());
        }}
    }}

    /**
     * Step 2: open the native "Save As" picker, then stream-copy the
     * already-cached file to wherever the user chose. Only `cachePath`
     * (a short string) needs to survive the round trip through the
     * system picker Activity — never the raw file bytes.
     */
    @PluginMethod
    public void saveFileAs(PluginCall call) {{
        String fileName = call.getString("fileName", "file");
        String mimeType = call.getString("mimeType", "application/octet-stream");
        String cachePath = call.getString("cachePath");

        if (cachePath == null || cachePath.isEmpty()) {{
            call.reject("Missing 'cachePath' — call writeCache first");
            return;
        }}

        File cacheFile = new File(cachePath);
        if (!cacheFile.exists()) {{
            call.reject("Cache file no longer exists: " + cachePath);
            return;
        }}

        call.setKeepAlive(true);

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mimeType);
        intent.putExtra(Intent.EXTRA_TITLE, fileName);

        try {{
            startActivityForResult(call, intent, "handleSaveResult");
        }} catch (Throwable t) {{
            call.reject("Could not open the Save As picker: " + t.getMessage());
        }}
    }}

    @ActivityCallback
    private void handleSaveResult(PluginCall call, ActivityResult result) {{
        if (call == null) return;

        String cachePath = call.getString("cachePath");

        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {{
            call.reject("User cancelled the save dialog");
            return;
        }}

        Uri destUri = result.getData().getData();
        if (destUri == null) {{
            call.reject("No destination was returned by the picker");
            return;
        }}

        File cacheFile = (cachePath != null) ? new File(cachePath) : null;
        if (cacheFile == null || !cacheFile.exists()) {{
            call.reject("Cached file is gone — please try downloading again");
            return;
        }}

        try {{
            ContentResolver resolver = getContext().getContentResolver();
            try (
                FileInputStream in = new FileInputStream(cacheFile);
                OutputStream out = resolver.openOutputStream(destUri)
            ) {{
                if (out == null) {{
                    call.reject("Could not open an output stream for the chosen file");
                    return;
                }}
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {{
                    out.write(buffer, 0, read);
                }}
                out.flush();
            }}

            //noinspection ResultOfMethodCallIgnored
            cacheFile.delete();

            JSObject ret = new JSObject();
            ret.put("uri", destUri.toString());
            call.resolve(ret);
        }} catch (Throwable t) {{
            call.reject("Failed to write file: " + t.getMessage());
        }}
    }}
}}
"""

MAIN_ACTIVITY_PATCHED = f"""package {APP_ID};

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {{
    @Override
    public void onCreate(Bundle savedInstanceState) {{
        registerPlugin(FileSaverPlugin.class);
        super.onCreate(savedInstanceState);
    }}
}}
"""


def write_plugin():
    os.makedirs(PACKAGE_PATH, exist_ok=True)
    with open(PLUGIN_FILE, "w", encoding="utf-8") as f:
        f.write(PLUGIN_JAVA)
    print(f"[file-saver-patch] wrote {PLUGIN_FILE}")


def patch_main_activity():
    if not os.path.isfile(MAIN_ACTIVITY):
        print(f"::error::[file-saver-patch] {MAIN_ACTIVITY} not found — did `npx cap add android` run first?")
        sys.exit(1)

    with open(MAIN_ACTIVITY, "r", encoding="utf-8") as f:
        content = f.read()

    if "FileSaverPlugin" in content:
        # Already patched by a previous run of this script on a stale
        # checkout; rewrite cleanly to keep it in sync.
        with open(MAIN_ACTIVITY, "w", encoding="utf-8") as f:
            f.write(MAIN_ACTIVITY_PATCHED)
        print(f"[file-saver-patch] re-wrote already-patched {MAIN_ACTIVITY}")
        return

    default_pattern = re.compile(
        r"public class MainActivity extends BridgeActivity\s*\{\s*\}"
    )

    if default_pattern.search(content):
        with open(MAIN_ACTIVITY, "w", encoding="utf-8") as f:
            f.write(MAIN_ACTIVITY_PATCHED)
        print(f"[file-saver-patch] patched {MAIN_ACTIVITY} (default template replaced)")
        return

    # Fallback: surgical patch for a MainActivity that already has a body.
    if "import android.os.Bundle;" not in content:
        content = content.replace(
            "import com.getcapacitor.BridgeActivity;",
            "import android.os.Bundle;\nimport com.getcapacitor.BridgeActivity;",
            1,
        )

    if re.search(r"public\s+void\s+onCreate\s*\(\s*Bundle\s+savedInstanceState\s*\)", content):
        content = content.replace(
            "super.onCreate(savedInstanceState);",
            "registerPlugin(FileSaverPlugin.class);\n        super.onCreate(savedInstanceState);",
            1,
        )
    else:
        content = re.sub(
            r"(public class MainActivity extends BridgeActivity\s*\{)",
            (
                r"\1\n"
                "    @Override\n"
                "    public void onCreate(Bundle savedInstanceState) {\n"
                "        registerPlugin(FileSaverPlugin.class);\n"
                "        super.onCreate(savedInstanceState);\n"
                "    }\n"
            ),
            content,
            count=1,
        )

    with open(MAIN_ACTIVITY, "w", encoding="utf-8") as f:
        f.write(content)
    print(f"[file-saver-patch] patched {MAIN_ACTIVITY} (surgical insert)")


if __name__ == "__main__":
    write_plugin()
    patch_main_activity()
