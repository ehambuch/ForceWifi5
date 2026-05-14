package de.erichambuch.forcewifi5;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.net.wifi.WifiSsid;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import java.util.Collections;
import java.util.Set;

public class WifiUtils {

    public static boolean isWifiConnected(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE); if (cm == null) return false;
        Network activeNetwork = cm.getActiveNetwork();
        if (activeNetwork == null) return false;

        NetworkCapabilities capabilities = cm.getNetworkCapabilities(activeNetwork);
        return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }
    public static boolean isWifiEnabled(Context context) {
        WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        return wifiManager != null && wifiManager.isWifiEnabled();
    }

    /**
     * Normalizes the SSID (remote quotation marks).
     * <p>Note: some Wifi operations require the quotation marks, otherweise they think is binary.</p>
     *
     * @param ssid SSID
     * @return normaoized SSID
     * @see WifiInfo#getSSID()
     */
    @NonNull
    public static String unquoteSSid(@Nullable String ssid) {
        if (ssid == null )
            return "";
        if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
            return ssid.substring(1, ssid.length()-1);
        } else
            return ssid;
    }

    public static boolean hasQuotedSSID(@Nullable String ssid) {
        return (ssid != null && ssid.startsWith("\"") && ssid.endsWith("\""));
    }

    /**
     * Ensures that SSID contains quotes for UTF-8 characters.
     * @param ssid original SSID (with or without quotes).
     * @return the quoted SSID
     */
    public static String getQuotationalSSID(@Nullable String ssid) { // TODO: does not support binary SSID
        if(ssid == null)
            return "";
        if(!ssid.startsWith("\"")) {
            return "\"" + ssid + "\"";
        } else
            return ssid;
    }

    /**
     * Calculates signal level in percent.
     * @param signalRssi in dBm
     * @return 0 to 100
     */
    public static int calculateWifiLevel(@NonNull WifiManager manager, int signalRssi) {
        if (manager.getMaxSignalLevel() > 0) {
            return manager.calculateSignalLevel(signalRssi) * 100 / manager.getMaxSignalLevel();
        } else {
            if(signalRssi <= -100)
                return 0;
            else if(signalRssi >= -50)
                return 100;
            else
                return 2 * (signalRssi + 100);
        }
    }

    public static boolean is24GHzPreferred(@NonNull Context context) {
        return ("0".equals(PreferenceManager.getDefaultSharedPreferences(context).getString(context.getString(R.string.prefs_2ghz5ghz), "0")));
    }

    public static boolean is5GHzPreferred(@NonNull Context context) {
        return ("1".equals(PreferenceManager.getDefaultSharedPreferences(context).getString(context.getString(R.string.prefs_2ghz5ghz), "1")));
    }

    public static boolean is6GHzPreferred(@NonNull Context context) {
        return ("2".equals(PreferenceManager.getDefaultSharedPreferences(context).getString(context.getString(R.string.prefs_2ghz5ghz), "1")));
    }
    public static boolean is60GHzPreferred(@NonNull Context context) {
        return ("3".equals(PreferenceManager.getDefaultSharedPreferences(context).getString(context.getString(R.string.prefs_2ghz5ghz), "1")));
    }

    public static boolean isWantedFrequency(@NonNull Context context, int freq) {
        if(is60GHzPreferred(context)) {
            return (freq >= 57000); //  57 bis 66 GHz
        }
        else if (is6GHzPreferred(context)) {
            return (freq >= 5925 && freq <= 6999);
        } else if (is5GHzPreferred(context))
            return (freq >= 5000 && freq <= 5920);
        else
            return (freq < 3000);
    }

    public static boolean isWrongFrequency(@NonNull Context context, int freq) {
        return !isWantedFrequency(context, freq);
    }

    @NonNull
    public static int[] getPreferredNetworkFrequencies(@NonNull Context context) {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // feature only available Android14+ and on some devices
            try {
                Set<String> selectedFreqsSet =
                        PreferenceManager.getDefaultSharedPreferences(context).getStringSet(context.getString(R.string.prefs_selectchannels),
                                Collections.emptySet());
                int[] freq = new int[selectedFreqsSet.size()];
                int i = 0;
                for(String s : selectedFreqsSet) {
                    freq[i++] = Integer.parseInt(s);
                }
                return freq;
            } catch (Exception e) {
                Crashlytics.recordException(e);
            }
        }
        return new int[0];
    }

    public static String getSsid(WifiNetworkSuggestion suggestion) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return returnNotNull(suggestion.getWifiSsid());
        } else {
            return returnNotNull(suggestion.getSsid());
        }
    }

    /**
     * Compactibility method for retrieving SSID.
     * @param suggestion
     * @return may be null or new SSID
     */
    @Nullable
    public static WifiSsid getWifiSsid(@NonNull WifiNetworkSuggestion suggestion) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return suggestion.getWifiSsid();
        } else {
           return null;
        }
    }

    /**
     * Compactibility method for retrieving SSID.
     * @param result
     * @return may be null or new SSID
     */
    @Nullable
    public static WifiSsid getWifiSsid(@NonNull ScanResult result) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return result.getWifiSsid();
        } else {
            return null;
        }
    }

    public static String getBssid(WifiNetworkSuggestion suggestion) {
        return returnNotNull(suggestion.getBssid());
    }

    private static String returnNotNull(String s) {
        return s != null ? s : "";
    }

    private static String returnNotNull(Object s) {
        return s != null ? s.toString() : "";
    }

    public static boolean isEmulator() {
        return (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || Build.PRODUCT.contains("sdk_google")
                || Build.PRODUCT.contains("google_sdk")
                || Build.PRODUCT.contains("sdk")
                || Build.PRODUCT.contains("sdk_x86")
                || Build.PRODUCT.contains("vbox86p")
                || Build.PRODUCT.contains("emulator")
                || Build.PRODUCT.contains("simulator");
    }

    public static boolean isSameSSID(ScanResult result, WifiInfo activeWifi) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            final String wifissid = result.getWifiSsid() != null ? result.getWifiSsid().toString() : "";
            return (wifissid.equals(activeWifi.getSSID()) || wifissid.equals(getQuotationalSSID(activeWifi.getSSID())));
        } else {
            return (getQuotationalSSID(result.SSID).equals(getQuotationalSSID(activeWifi.getSSID())));
        }
    }
}
