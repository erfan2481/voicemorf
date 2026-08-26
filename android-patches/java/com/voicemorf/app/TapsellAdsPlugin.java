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
import ir.tapsell.plus.model.TapsellPlusBannerType;
import ir.tapsell.plus.model.TapsellPlusErrorModel;

/*
 * Tapsell keys — from the Tapsell dashboard for "وویس‌مورف".
 * App key, interstitial-video zone id, and standard-banner zone id.
 *
 * ZONE_BANNER: create a "Standard Banner" zone in the Tapsell panel
 * (separate from the interstitial zone) and paste its id below —
 * this is account-specific, so it has to be filled in manually.
 */
@CapacitorPlugin(name = "TapsellAds")
public class TapsellAdsPlugin extends Plugin {

    private static final String TAG = "TapsellAds";
    private static final String APP_KEY = "mhdnftegiglnsbkjfspljeadaiiegncqhbibdlrdhejkofdigscnoorljemrfodihbcgdf";
    private static final String ZONE_INTERSTITIAL = "6a8ccbaff34d73758477ecd4";
    private static final String ZONE_BANNER = "6a8ccb85f34d73758477ecd3";

    private String interstitialResponseId = null;
    private String bannerResponseId = null;
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
                requestBanner();
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

    private void requestBanner() {
        if (getActivity() == null) return;
        TapsellPlus.requestStandardBannerAd(getActivity(), ZONE_BANNER,
                TapsellPlusBannerType.BANNER_320x50,
                new AdRequestCallback() {
                    @Override
                    public void response(TapsellPlusAdModel model) {
                        super.response(model);
                        bannerResponseId = model.getResponseId();
                    }

                    @Override
                    public void error(String message) {
                        Log.d(TAG, "banner request error: " + message);
                    }
                });
    }

    @PluginMethod
    public void showBanner(PluginCall call) {
        if (bannerResponseId == null || bannerContainer == null || getActivity() == null) {
            // Not loaded yet (or zone id not configured) — quietly skip,
            // JS doesn't check readiness before calling this.
            call.resolve();
            return;
        }
        final String responseId = bannerResponseId;
        getActivity().runOnUiThread(() -> TapsellPlus.showStandardBannerAd(getActivity(), responseId,
                bannerContainer,
                new AdShowListener() {
                    @Override
                    public void onError(TapsellPlusErrorModel error) {
                        super.onError(error);
                        Log.d(TAG, "banner show error: " + error);
                    }
                }));
        call.resolve();
    }

    @PluginMethod
    public void hideBanner(PluginCall call) {
        if (bannerResponseId != null && bannerContainer != null && getActivity() != null) {
            final String responseId = bannerResponseId;
            getActivity().runOnUiThread(() ->
                    TapsellPlus.destroyStandardBanner(getActivity(), responseId, bannerContainer));
        }
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
