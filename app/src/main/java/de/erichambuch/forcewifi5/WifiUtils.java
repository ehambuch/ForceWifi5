package de.erichambuch.forcewifi5;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

public class WifiUtils {

    /**
     * Normalizes the SSID (remote quotation marks)
     *
     * @param ssid SSID
     * @return normaoized SSID
     * @see WifiInfo#getSSID()
     */
    @NonNull
    public static String normalizeSSID(@Nullable String ssid) {
        if (ssid == null )
            return "";
        if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
            Log.d(AppInfo.APP_NAME, "Normlized SSID "+ssid);
            return ssid.substring(1, ssid.length()-1);
        } else
            return ssid;
    }

    public static boolean hasNormalizedSSID(@Nullable String ssid) {
        return (ssid != null && ssid.startsWith("\"") && ssid.endsWith("\""));
    }

    /**
     * Calculates signal level in percent.
     * @param signalRssi in dBm
     * @return 0 to 100
     */
    public static int calculateWifiLevel(@NonNull WifiManager manager, int signalRssi) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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

    public static boolean is5GHzPreferred(@NonNull Context context) {
        return ("1".equals(PreferenceManager.getDefaultSharedPreferences(context).getString(context.getString(R.string.prefs_2ghz5ghz), "1")));
    }

    public static boolean is6GHzPreferred(@NonNull Context context) {
        return ("2".equals(PreferenceManager.getDefaultSharedPreferences(context).getString(context.getString(R.string.prefs_2ghz5ghz), "1")));
    }

    public static boolean isWantedFrequency(@NonNull Context context, int freq) {
        if (is6GHzPreferred(context)) {
            return (freq >= 5925);
        } else if (is5GHzPreferred(context))
            return (freq >= 5000 && freq <= 5920);
        else
            return (freq < 3000);
    }
}
