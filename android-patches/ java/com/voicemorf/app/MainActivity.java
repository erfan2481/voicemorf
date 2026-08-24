package com.voicemorf.app;

import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.PluginHandle;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(TapsellAdsPlugin.class);
        super.onCreate(savedInstanceState);

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
}
