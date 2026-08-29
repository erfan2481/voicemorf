package com.voicemorf.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.PluginHandle;

public class MainActivity extends BridgeActivity {

    private static final int MIC_PERMISSION_REQUEST_CODE = 1001;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Bulletproof "is the new build actually running" check — a modal
        // dialog you must tap to dismiss, so it can't be missed like a
        // Toast can. If you don't see this the instant the app opens, the
        // new MainActivity.java never actually made it into the installed
        // APK (a build/deploy issue, not a logic bug).
        new android.app.AlertDialog.Builder(this)
                .setTitle("Build check")
                .setMessage("MainActivity v3 (tapsell-diag) is running")
                .setPositiveButton("OK", null)
                .setCancelable(false)
                .show();

        // Always-on remote debugging (safe for a personal/sideloaded app):
        // connect this phone to chrome://inspect on a PC to read real
        // console errors instead of guessing blind.
        WebView.setWebContentsDebuggingEnabled(true);

        // Proactively try to load every ir.tapsell.plus.* class the plugin
        // needs, BEFORE calling registerPlugin(). If registerPlugin()
        // internally swallows a registration failure (many plugin bridges
        // do this on purpose so one bad plugin can't crash the whole app),
        // that failure would otherwise be invisible to us. Doing our own
        // Class.forName() first means WE catch the real
        // ClassNotFoundException / NoClassDefFoundError directly.
        String tapsellDiag = null;
        try {
            Class.forName("ir.tapsell.plus.TapsellPlus");
            Class.forName("ir.tapsell.plus.TapsellPlusInitListener");
            Class.forName("ir.tapsell.plus.AdRequestCallback");
            Class.forName("ir.tapsell.plus.AdShowListener");
            Class.forName("ir.tapsell.plus.model.AdNetworks");
            Class.forName("ir.tapsell.plus.model.AdNetworkError");
            Class.forName("ir.tapsell.plus.model.TapsellPlusAdModel");
            Class.forName("ir.tapsell.plus.model.TapsellPlusErrorModel");
            tapsellDiag = "all ir.tapsell.plus.* classes loaded OK";
        } catch (Throwable t) {
            tapsellDiag = "Class.forName FAILED: " + t.getClass().getName() + ": " + t.getMessage();
        }

        String tapsellRegisterDiag;
        try {
            registerPlugin(TapsellAdsPlugin.class);
            tapsellRegisterDiag = "registerPlugin(TapsellAdsPlugin) did not throw";
        } catch (Throwable t) {
            tapsellRegisterDiag = "registerPlugin(TapsellAdsPlugin) THREW: " + t.getClass().getName() + ": " + t.getMessage();
        }

        registerPlugin(FileSaverPlugin.class);
        super.onCreate(savedInstanceState);

        sendNativeDiagToJs("Tapsell class check: " + tapsellDiag);
        sendNativeDiagToJs("Tapsell registerPlugin: " + tapsellRegisterDiag);

        // Ask Android itself for the microphone permission. Declaring it in
        // AndroidManifest.xml is not enough — without this call the system
        // permission dialog never appears, and the WebView's getUserMedia()
        // will keep failing even if the user later flips it on manually in
        // system Settings.
        requestMicPermissionIfNeeded();

        // Make the whole app fullscreen/immersive: hide the top status bar
        // and bottom nav bar so the WebView content uses the entire screen.
        enableFullscreenMode();

        // Rebuild the screen as [ WebView (fills remaining space) ][ banner slot at bottom ]
        WebView webView = getBridge().getWebView();
        ViewGroup currentParent = (ViewGroup) webView.getParent();
        if (currentParent != null) currentParent.removeView(webView);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout.LayoutParams webViewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(webView, webViewParams);

        FrameLayout bannerContainer = new FrameLayout(this);
        LinearLayout.LayoutParams bannerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(bannerContainer, bannerParams);

        setContentView(root);

        PluginHandle handle = getBridge().getPlugin("TapsellAds");
        sendNativeDiagToJs("getBridge().getPlugin(\"TapsellAds\") = " + (handle == null ? "null" : "found"));
        if (handle != null) {
            try {
                TapsellAdsPlugin plugin = (TapsellAdsPlugin) handle.getInstance();
                plugin.setBannerContainer(bannerContainer);
                plugin.initTapsell();
            } catch (Throwable t) {
                sendNativeDiagToJs("initTapsell() setup THREW: " + t.getClass().getName() + ": " + t.getMessage());
            }
        }
    }

    /** Pushes a diagnostic line straight into the web app's on-device error
     *  panel (window.__logError), once the WebView is ready. This lets us
     *  see real native-side failures on the phone itself, no adb/logcat or
     *  computer needed. */
    private void sendNativeDiagToJs(String message){
        final WebView wv = getBridge().getWebView();
        final String safe = message
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", " | ");
        wv.postDelayed(() -> {
            String js = "if(window.__logError){ window.__logError('MainActivity native', '" + safe + "'); }";
            wv.evaluateJavascript(js, null);
        }, 2500);
    }

    private void requestMicPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.MODIFY_AUDIO_SETTINGS
                    },
                    MIC_PERMISSION_REQUEST_CODE
            );
        }
    }

    private void enableFullscreenMode() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            );
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Re-hide the system bars whenever the app regains focus (e.g. after
        // the user swipes them back in, or returns from another app),
        // otherwise Android leaves them visible.
        if (hasFocus) {
            enableFullscreenMode();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // No extra handling needed here: once Android grants RECORD_AUDIO,
        // Capacitor's WebView bridge will grant the matching getUserMedia
        // request on its own the next time the web page asks for it.
    }
}
