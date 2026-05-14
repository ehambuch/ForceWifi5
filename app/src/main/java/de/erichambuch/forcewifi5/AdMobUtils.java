package de.erichambuch.forcewifi5;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.google.ads.mediation.admob.AdMobAdapter;
import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.RequestConfiguration;
import com.google.android.ump.ConsentDebugSettings;
import com.google.android.ump.ConsentForm;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.UserMessagingPlatform;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Helper object to handle AdMob ads.
 * <p>Keep this object during runtime!</p>
 */
public class AdMobUtils {

    /**
     * Variablen für Ads.
     */
    private final AtomicLong lastLoadAdRequest = new AtomicLong(0);
    final AtomicBoolean adSizeSet = new AtomicBoolean(false);
    final AtomicBoolean adMobInitialized = new AtomicBoolean(false);
    volatile AdRequest mAdRequest;
    volatile AdView mAdView;
    private final AtomicBoolean consentGiven = new AtomicBoolean(true);

    private final Activity mainActivity;

    public AdMobUtils(Activity mainActivity) {
        this.mainActivity = mainActivity;
    }

    public AdView getAdMobView() {
        return mAdView;
    }

    public void requestAdConsent() {
        // Set tag for underage of consent. false means users are not underage.
        ConsentRequestParameters params = new ConsentRequestParameters
                .Builder()
                .setTagForUnderAgeOfConsent(false)
                .setConsentDebugSettings(new ConsentDebugSettings.Builder(mainActivity).addTestDeviceHashedId("B0214BFF74ADA800F378286E92A9D8C3").build())
                .build();

        final ConsentInformation consentInformation = UserMessagingPlatform.getConsentInformation(mainActivity);
        consentInformation.requestConsentInfoUpdate(
                mainActivity,
                params,
                () -> {
                    if (consentInformation.isConsentFormAvailable()) {
                        loadConsentForm();
                    }
                },
                formError -> {
                    Log.w(AppInfo.APP_NAME, "Consent failed "+formError.getMessage()+", "+formError.getErrorCode());
                    consentGiven.set(false);
                });

    }

    protected void loadConsentForm() {
        UserMessagingPlatform.loadAndShowConsentFormIfRequired(
                mainActivity,
                (ConsentForm.OnConsentFormDismissedListener) loadAndShowError -> {
                    if (loadAndShowError != null) {
                        Log.w(AppInfo.APP_NAME, "Consent failed "+loadAndShowError.getMessage()+", "+loadAndShowError.getErrorCode());
                        showError(R.string.error_constent);
                    }
                }
        );
    }

    void showPrivacyOptions() {
        UserMessagingPlatform.loadConsentForm(mainActivity, consentForm -> {
            UserMessagingPlatform.showPrivacyOptionsForm(
                    mainActivity,
                    formError -> {
                        if (formError != null) {
                            showError(mainActivity.getString(R.string.error_constent) + ": " + formError.getMessage());
                        }
                    }
            );
        }, consentGiven -> {
            showError(mainActivity.getString(R.string.error_constent) + ": " + consentGiven.getMessage());
        });
    }


    void setUpAds() {
        Bundle adMobExtras = new Bundle(); // nur unpersonalisierte Werbung ausliefern (DSGVO))
        mAdRequest = new AdRequest.Builder()
                .addNetworkExtrasBundle(AdMobAdapter.class, adMobExtras)
                .build();
        mAdView = new AdView(mainActivity);
        mAdView.setAdListener(new AdListener() {
            @Override
            public void onAdFailedToLoad(@NonNull LoadAdError adError) {
                lastLoadAdRequest.set(0); // force reload
                Log.w(AppInfo.APP_NAME, "Ad failed to load: "+adError);
            }
        });
        if(BuildConfig.DEBUG)
            mAdView.setAdUnitId(mainActivity.getString(R.string.banner_ad_unit_id_test));
        else
            mAdView.setAdUnitId(BuildConfig.ADMOB_ID); // we read the ADMOB_ID from (secret) local properties

        // gem. Empfehlung starten wir die Initialisierung per separaten Thread
        try {
            new Thread(() -> {
                RequestConfiguration requestConfiguration = MobileAds.getRequestConfiguration()
                        .toBuilder()
                        .setTestDeviceIds(Arrays.asList(AdRequest.DEVICE_ID_EMULATOR, "4AF077D943AD0039A97A401A198C31E1", "D485D71F04DA4B5772EAE7F2605149C9", "67FAAD19B41F7E0B07D2A95A82DAD25A")) // S8
                        .build();
                MobileAds.setRequestConfiguration(requestConfiguration);
                MobileAds.initialize(mainActivity, initializationStatus -> adMobInitialized.set(true));
            }).start();

            // Ad erst nach vollständigem Layout laden, um korrekte Größe zu ermitteln
            final FrameLayout adViewContainer = mainActivity.findViewById(R.id.adViewContainer);
            adViewContainer.getViewTreeObserver().addOnGlobalLayoutListener(
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            if (!adSizeSet.get()) {
                                final FrameLayout adViewContainer = mainActivity.findViewById(R.id.adViewContainer);
                                mAdView.setAdSize(getAdSize());
                                adSizeSet.set(true);
                                adViewContainer.removeAllViews();
                                adViewContainer.addView(mAdView);
                            }
                            loadAd();
                        }
                    });
        } catch (RuntimeException e) {
            Crashlytics.recordException(e);
        }
    }

    /**
     * Must be executed in Main Thread for {@link AdView#loadAd}.
     */
    protected void loadAd() {
        try {
            final long current = System.currentTimeMillis();
            if (((current - this.lastLoadAdRequest.get()) >= 60 * 1000)
                    && adMobInitialized.get()) { // min 60 secs and only load if Admob is finally initialized
                mAdView.loadAd(mAdRequest);
                this.lastLoadAdRequest.set(current);
            }
        } catch(IllegalStateException e) {
            adSizeSet.set(false); // re-set AdSize next time
        } catch(RuntimeException e) {
            Crashlytics.recordException(e);
        }
    }

    /**
     * Berechnung für Adaptive Banner size.
     * @return size
     */
    AdSize getAdSize() {
        return AdSize.BANNER;
        // AdSize adSize =  AdSize.getLargeAnchoredAdaptiveBannerAdSize(mainActivity, 360);
        // return (adSize == null || adSize == AdSize.INVALID) ? AdSize.BANNER : adSize;
    }

    void showError(@StringRes int stringId) {
        Toast.makeText(mainActivity, stringId, Toast.LENGTH_LONG).show();
    }

    void showError(String string) {
        Toast.makeText(mainActivity, string, Toast.LENGTH_LONG).show();
    }
}
