package com.voicemorf.app;

import android.util.Log;
import android.widget.FrameLayout;

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
 * App key and interstitial-video zone id.
 * NOTE: the standard-banner feature is temporarily disabled because
 * ir.tapsell.plus.model.TapsellPlusBannerType could not be resolved
 * against the SDK version pulled in CI. Interstitial ads still work.
 */
@CapacitorPlugin(name = "TapsellAds")
public class TapsellAdsPlugin extends Plugin {

    private static final String TAG = "TapsellAds";
    private static final String APP_KEY = "mhdnftegiglnsbkjfspljeadaiiegncqhbibdlrdhejkofdigscnoorljemrfodihbcgdf";
    private static final String ZONE_INTERSTITIAL = "6a8ccbaff34d73758477ecd4";

    private String interstitialResponseId = null;
    private FrameLayout bannerContainer = null;

    public void setBannerContainer(FrameLayout container) {
        // Kept so MainActivity still compiles/works; banner ad is disabled for now.
        this.bannerContainer = container;
    }

    public void initTapsell() {
        TapsellPlus.initialize(getContext(), APP_KEY, new TapsellPlusInitListener() {
            @Override
            public void onInitializeSuccess(AdNetworks adNetworks) {
                Log.d(TAG, "Tapsell init success");
                requestInterstitial();
            }

            @Override
            public void onInitializeFailed(AdNetworks adNetworks, AdNetworkError adNetworkError) {
                Log.d(TAG, "Tapsell init failed: " + adNetworkError);
            }
        });
    }

    private void requestInterstitial() {
        TapsellPlus.requestInterstitialAd(getActivity(), ZONE_INTERSTITIAL,
                new AdRequestCallback() {
                    @Override
                    public void response(TapsellPlusAdModel model) {
                        super.response(model);
                        interstitialResponseId = model.getResponseId();
                    }

                    @Override
                    public void error(String message) {
                        Log.d(TAG, "interstitial request error: " + message);
                    }
                });
    }

    @PluginMethod
    public void showBanner(PluginCall call) {
        // Standard banner temporarily disabled — no-op so JS calls don't break.
        call.resolve();
    }

    @PluginMethod
    public void hideBanner(PluginCall call) {
        call.resolve();
    }

    @PluginMethod
    public void showInterstitial(PluginCall call) {
        if (interstitialResponseId == null || getActivity() == null) {
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
                        requestInterstitial();
                    }

                    @Override
                    public void onError(TapsellPlusErrorModel error) {
                        super.onError(error);
                        Log.d(TAG, "interstitial show error: " + error);
                        requestInterstitial();
                    }
                }));
        call.resolve();
    }
}
