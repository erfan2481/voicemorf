package com.voicemorf.app;

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

/*
 * Two-step, crash-safe "Save As" flow:
 *
 *  1. writeCache({ data, fileName }) — writes the base64 payload straight
 *     to the app's own cache dir while the app is still fully in the
 *     foreground. Fast, no system UI involved, so it can't be interrupted
 *     by Android backgrounding/killing the process.
 *
 *  2. saveFileAs({ cachePath, fileName, mimeType }) — opens Android's
 *     native "Save As" (Storage Access Framework) picker. Only the small
 *     cachePath string needs to survive the round trip through that
 *     system picker Activity; once the user picks a destination we just
 *     stream-copy the already-on-disk cached file over to it.
 *
 * This replaces an earlier single-step version that passed the full
 * base64 payload through the PluginCall across the picker Activity call —
 * some devices (especially aggressive OEM Android like MIUI/EMUI) kill
 * backgrounded apps holding a large in-memory payload while a heavy
 * system picker is open, which showed up as the app appearing to "crash
 * and exit" with a 0-byte result file.
 */
@CapacitorPlugin(name = "FileSaver")
public class FileSaverPlugin extends Plugin {

    private static final String CACHE_SUBDIR = "file_saver_tmp";

    @PluginMethod
    public void writeCache(PluginCall call) {
        String fileName = call.getString("fileName", "file");
        String data = call.getString("data");

        if (data == null || data.isEmpty()) {
            call.reject("Missing 'data' (base64 file content)");
            return;
        }

        try {
            File dir = new File(getContext().getCacheDir(), CACHE_SUBDIR);
            if (!dir.exists()) dir.mkdirs();

            String safeName = System.currentTimeMillis() + "_" + fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
            File outFile = new File(dir, safeName);

            byte[] bytes = Base64.decode(data, Base64.DEFAULT);
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                fos.write(bytes);
                fos.flush();
            }

            JSObject ret = new JSObject();
            ret.put("path", outFile.getAbsolutePath());
            call.resolve(ret);
        } catch (Throwable t) {
            call.reject("Failed to write cache file: " + t.getMessage());
        }
    }

    @PluginMethod
    public void saveFileAs(PluginCall call) {
        String fileName = call.getString("fileName", "file");
        String mimeType = call.getString("mimeType", "application/octet-stream");
        String cachePath = call.getString("cachePath");

        if (cachePath == null || cachePath.isEmpty()) {
            call.reject("Missing 'cachePath' — call writeCache first");
            return;
        }

        File cacheFile = new File(cachePath);
        if (!cacheFile.exists()) {
            call.reject("Cache file no longer exists: " + cachePath);
            return;
        }

        call.setKeepAlive(true);

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mimeType);
        intent.putExtra(Intent.EXTRA_TITLE, fileName);

        try {
            startActivityForResult(call, intent, "handleSaveResult");
        } catch (Throwable t) {
            call.reject("Could not open the Save As picker: " + t.getMessage());
        }
    }

    @ActivityCallback
    private void handleSaveResult(PluginCall call, ActivityResult result) {
        if (call == null) return;

        String cachePath = call.getString("cachePath");

        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            call.reject("User cancelled the save dialog");
            return;
        }

        Uri destUri = result.getData().getData();
        if (destUri == null) {
            call.reject("No destination was returned by the picker");
            return;
        }

        File cacheFile = (cachePath != null) ? new File(cachePath) : null;
        if (cacheFile == null || !cacheFile.exists()) {
            call.reject("Cached file is gone — please try downloading again");
            return;
        }

        try {
            ContentResolver resolver = getContext().getContentResolver();
            try (
                FileInputStream in = new FileInputStream(cacheFile);
                OutputStream out = resolver.openOutputStream(destUri)
            ) {
                if (out == null) {
                    call.reject("Could not open an output stream for the chosen file");
                    return;
                }
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            }

            //noinspection ResultOfMethodCallIgnored
            cacheFile.delete();

            JSObject ret = new JSObject();
            ret.put("uri", destUri.toString());
            call.resolve(ret);
        } catch (Throwable t) {
            call.reject("Failed to write file: " + t.getMessage());
        }
    }
}
