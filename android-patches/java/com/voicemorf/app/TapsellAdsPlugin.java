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
 * App key, interstitial-video zone id, and standard-banner zone id.
 *
 * NOTE: standard-banner is temporarily paused again. TapsellPlusBannerType
 * is NOT in ir.tapsell.plus.model for this SDK version (confirmed by CI
 * compile error) — re-enable once the improved "Debug list Tapsell SDK
 * classes" CI step (which now runs after a real build attempt, so the SDK
 * is guaranteed to actually be unpacked on disk) reports the real
 * package/class name.
 */
@CapacitorPlugin(name = "TapsellAds")
public class TapsellAdsPlugin extends Plugin {

    private static final String TAG = "TapsellAds";
    private static final String APP_KEY = "mhdnftegiglnsbkjfspljeadaiiegncqhbibdlrdhejkofdigscnoorljemrfodihbcgdf";
    private static final String ZONE_INTERSTITIAL = "6a8ccbaff34d73758477ecd4";
    private static final String ZONE_BANNER = "6a8ccb85f34d73758477ecd3";

    private String interstitialResponseId = null;
    private FrameLayout bannerContainer = null;

    public void setBannerContainer(FrameLayout container) {
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
        // Paused — see note above the class. No-op so JS calls don't break.
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
