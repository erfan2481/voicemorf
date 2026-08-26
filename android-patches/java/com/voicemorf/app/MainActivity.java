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
        registerPlugin(TapsellAdsPlugin.class);
        registerPlugin(FileSaverPlugin.class);
        super.onCreate(savedInstanceState);

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
        if (handle != null) {
            TapsellAdsPlugin plugin = (TapsellAdsPlugin) handle.getInstance();
            plugin.setBannerContainer(bannerContainer);
            plugin.initTapsell();
        }
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
