package com.voicemorf.app;

import android.util.Log;
import android.widget.FrameLayout;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import ir.tapsell.plus.AdRequestCallback;
import ir.tapsell.plus.AdShowListener;
import ir.tapsell.plus.TapsellPlus;
import ir.tapsell.plus.TapsellPlusInitListener;
import ir.tapsell.plus.model.AdNetworkError;
import ir.tapsell.plus.model.AdNetworks;
import ir.tapsell.plus.model.TapsellPlusAdModel;
import ir.tapsell.plus.model.TapsellPlusErrorModel;

/*
 * Tapsell keys — from the Tapsell dashboard for "وویس‌مورف".
 * App key, interstitial-video zone id, and standard-banner zone id.
 *
 * NOTE: standard-banner is temporarily paused again. TapsellPlusBannerType
 * is NOT in ir.tapsell.plus.model for this SDK version (confirmed by CI
 * compile error) — re-enable once the "Debug list Tapsell SDK classes" CI
 * step reports the real package/class name.
 *
 * Every meaningful step below also calls emitLog(...), which forwards a
 * plain-text line to JS (window.Capacitor.Plugins.TapsellAds addListener
 * 'tapsellLog'), which the web app displays in its on-device diagnostic
 * panel — so ad issues can be diagnosed on-device, without adb/logcat.
 */
@CapacitorPlugin(name = "TapsellAds")
public class TapsellAdsPlugin extends Plugin {

    private static final String TAG = "TapsellAds";
    private static final String APP_KEY = "mhdnftegiglnsbkjfspljeadaiiegncqhbibdlrdhejkofdigscnoorljemrfodihbcgdf";
    private static final String ZONE_INTERSTITIAL = "6a8ccbaff34d73758477ecd4";
    private static final String ZONE_BANNER = "6a8ccb85f34d73758477ecd3";

    private String interstitialResponseId = null;
    private FrameLayout bannerContainer = null;

    private void emitLog(String message) {
        Log.d(TAG, message);
        try {
            JSObject data = new JSObject();
            data.put("message", message);
            notifyListeners("tapsellLog", data);
        } catch (Throwable t) {
            // never let a logging hiccup affect ad flow
        }
    }

    public void setBannerContainer(FrameLayout container) {
        this.bannerContainer = container;
    }

    public void initTapsell() {
        emitLog("initTapsell() called");
        TapsellPlus.initialize(getContext(), APP_KEY, new TapsellPlusInitListener() {
            @Override
            public void onInitializeSuccess(AdNetworks adNetworks) {
                emitLog("Tapsell init SUCCESS (" + adNetworks + ")");
                requestInterstitial();
            }

            @Override
            public void onInitializeFailed(AdNetworks adNetworks, AdNetworkError adNetworkError) {
                emitLog("Tapsell init FAILED: " + adNetworkError);
            }
        });
    }

    private void requestInterstitial() {
        emitLog("requesting interstitial ad for zone " + ZONE_INTERSTITIAL + " ...");
        TapsellPlus.requestInterstitialAd(getActivity(), ZONE_INTERSTITIAL,
                new AdRequestCallback() {
                    @Override
                    public void response(TapsellPlusAdModel model) {
                        super.response(model);
                        interstitialResponseId = model.getResponseId();
                        emitLog("interstitial ready (responseId=" + interstitialResponseId + ")");
                    }

                    @Override
                    public void error(String message) {
                        emitLog("interstitial REQUEST ERROR: " + message);
                    }
                });
    }

    @PluginMethod
    public void showBanner(PluginCall call) {
        // Paused — see note above the class. No-op so JS calls don't break.
        emitLog("showBanner() called but banner is currently disabled in code (pending TapsellPlusBannerType fix)");
        call.resolve();
    }

    @PluginMethod
    public void hideBanner(PluginCall call) {
        call.resolve();
    }

    @PluginMethod
    public void showInterstitial(PluginCall call) {
        emitLog("showInterstitial() called, cached responseId=" + interstitialResponseId);
        if (interstitialResponseId == null || getActivity() == null) {
            emitLog("interstitial NOT shown: no ad preloaded yet (either still loading, or last request had no fill)");
            call.reject("interstitial not ready yet");
            return;
        }
        final String responseId = interstitialResponseId;
        interstitialResponseId = null; // consumed; preload the next one below
        getActivity().runOnUiThread(() -> TapsellPlus.showInterstitialAd(getActivity(), responseId,
                new AdShowListener() {
                    @Override
                    public void onClosed(TapsellPlusAdModel model) {
                        super.onClosed(model);
                        emitLog("interstitial closed by user");
                        requestInterstitial();
                    }

                    @Override
                    public void onError(TapsellPlusErrorModel error) {
                        super.onError(error);
                        emitLog("interstitial SHOW ERROR: " + error);
                        requestInterstitial();
                    }
                }));
        call.resolve();
    }
}
