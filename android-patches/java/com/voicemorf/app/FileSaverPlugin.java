package com.voicemorf.app;

import android.app.Activity;
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

import java.io.IOException;
import java.io.OutputStream;

/*
 * Opens Android's real "Save As" picker (Storage Access Framework) so the
 * user picks exactly where in their personal files to save, same as any
 * normal Android app's download/export flow. Replaces the earlier silent
 * MediaStore-write approach.
 */
@CapacitorPlugin(name = "FileSaver")
public class FileSaverPlugin extends Plugin {

    @PluginMethod
    public void saveFileAs(PluginCall call) {
        String fileName = call.getString("fileName");
        String mimeType = call.getString("mimeType");
        if (mimeType == null) mimeType = "application/octet-stream";

        if (fileName == null || call.getString("data") == null) {
            call.reject("data and fileName are required");
            return;
        }

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mimeType);
        intent.putExtra(Intent.EXTRA_TITLE, fileName);

        startActivityForResult(call, intent, "handleSaveResult");
    }

    @ActivityCallback
    private void handleSaveResult(PluginCall call, ActivityResult result) {
        if (call == null) return;

        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            // User backed out of the picker without choosing a location.
            JSObject ret = new JSObject();
            ret.put("cancelled", true);
            call.resolve(ret);
            return;
        }

        Uri destinationUri = result.getData().getData();
        if (destinationUri == null) {
            call.reject("no destination selected");
            return;
        }

        String base64Data = call.getString("data");
        int commaIdx = base64Data.indexOf(",");
        if (base64Data.startsWith("data:") && commaIdx != -1) {
            base64Data = base64Data.substring(commaIdx + 1);
        }

        try {
            byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
            try (OutputStream out = getContext().getContentResolver().openOutputStream(destinationUri)) {
                if (out == null) throw new IOException("openOutputStream returned null");
                out.write(bytes);
            }
            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (IllegalArgumentException e) {
            call.reject("invalid base64 data: " + e.getMessage());
        } catch (Exception e) {
            call.reject("failed to save file: " + e.getMessage(), e);
        }
    }
}
