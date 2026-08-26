package com.voicemorf.app;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/*
 * Saves a base64-encoded blob (sent from the WebView JS side) into the
 * device's public Downloads folder. Needed because Android's WebView has
 * no built-in handling for <a download href="blob:..."> the way a real
 * mobile browser does — clicking it silently does nothing.
 */
@CapacitorPlugin(name = "FileSaver")
public class FileSaverPlugin extends Plugin {

    @PluginMethod
    public void saveFile(PluginCall call) {
        String base64Data = call.getString("data");
        String fileName = call.getString("fileName");
        String mimeType = call.getString("mimeType");
        if (mimeType == null) mimeType = "application/octet-stream";

        if (base64Data == null || fileName == null) {
            call.reject("data and fileName are required");
            return;
        }

        // Strip a "data:...;base64," prefix if the JS side sent a full data URL.
        int commaIdx = base64Data.indexOf(",");
        if (base64Data.startsWith("data:") && commaIdx != -1) {
            base64Data = base64Data.substring(commaIdx + 1);
        }

        byte[] bytes;
        try {
            bytes = Base64.decode(base64Data, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            call.reject("invalid base64 data: " + e.getMessage());
            return;
        }

        try {
            Context context = getContext();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+: MediaStore, no storage permission needed.
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                values.put(MediaStore.Downloads.IS_PENDING, 1);

                Uri itemUri = context.getContentResolver()
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (itemUri == null) {
                    call.reject("could not create file entry");
                    return;
                }
                try (OutputStream out = context.getContentResolver().openOutputStream(itemUri)) {
                    if (out == null) throw new java.io.IOException("openOutputStream returned null");
                    out.write(bytes);
                }
                values.clear();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                context.getContentResolver().update(itemUri, values, null, null);
            } else {
                // Android 9 and below: direct file write to the public Downloads dir.
                File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!downloadsDir.exists()) downloadsDir.mkdirs();
                File outFile = new File(downloadsDir, fileName);
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    fos.write(bytes);
                }
            }

            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("failed to save file: " + e.getMessage(), e);
        }
    }
}
