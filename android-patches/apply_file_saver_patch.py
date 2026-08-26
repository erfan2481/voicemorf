#!/usr/bin/env python3
"""
Injects a native "FileSaver" Capacitor plugin into the freshly-generated
android/ project (created each CI run via `npx cap add android`) and wires
it into MainActivity.java.

The plugin implements exactly the contract index.html already calls:
    window.Capacitor.Plugins.FileSaver.saveFileAs({ data, fileName, mimeType })

It opens Android's native "Save As" (Storage Access Framework) picker, so
the user picks exactly where to save (Downloads is one of the default
shortcuts shown), and no storage permission is required on any Android
version.

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

import java.io.OutputStream;

@CapacitorPlugin(name = "FileSaver")
public class FileSaverPlugin extends Plugin {{

    @PluginMethod
    public void saveFileAs(PluginCall call) {{
        String fileName = call.getString("fileName", "file");
        String mimeType = call.getString("mimeType", "application/octet-stream");
        String data = call.getString("data");

        if (data == null || data.isEmpty()) {{
            call.reject("Missing 'data' (base64 file content)");
            return;
        }}

        call.setKeepAlive(true);

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mimeType);
        intent.putExtra(Intent.EXTRA_TITLE, fileName);

        try {{
            startActivityForResult(call, intent, "handleSaveResult");
        }} catch (Exception e) {{
            call.reject("Could not open the Save As picker: " + e.getMessage());
        }}
    }}

    @ActivityCallback
    private void handleSaveResult(PluginCall call, ActivityResult result) {{
        if (call == null) return;

        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {{
            call.reject("User cancelled the save dialog");
            return;
        }}

        Uri uri = result.getData().getData();
        if (uri == null) {{
            call.reject("No destination was returned by the picker");
            return;
        }}

        try {{
            String base64 = call.getString("data");
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);

            ContentResolver resolver = getContext().getContentResolver();
            try (OutputStream os = resolver.openOutputStream(uri)) {{
                if (os == null) {{
                    call.reject("Could not open an output stream for the chosen file");
                    return;
                }}
                os.write(bytes);
                os.flush();
            }}

            JSObject ret = new JSObject();
            ret.put("uri", uri.toString());
            call.resolve(ret);
        }} catch (Exception e) {{
            call.reject("Failed to write file: " + e.getMessage());
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
        print("[file-saver-patch] MainActivity.java already patched, skipping.")
        return

    # Only overwrite if it still looks like the untouched Capacitor default.
    # If some other patch has already customized MainActivity, do a
    # surgical insert instead of clobbering it.
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
