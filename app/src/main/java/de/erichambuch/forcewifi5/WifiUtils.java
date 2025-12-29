package de.erichambuch.forcewifi5;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.ACCESS_WIFI_STATE;
import static android.Manifest.permission.CHANGE_NETWORK_STATE;
import static android.Manifest.permission.NEARBY_WIFI_DEVICES;
import static android.Manifest.permission.POST_NOTIFICATIONS;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import java.util.Collections;
import java.util.Set;

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && manager.getMaxSignalLevel() > 0) {
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
    /**
     * Get list of required Wifi permissions for app.
     * @return the list of required permissions
     * @see <a href="https://developer.android.com/develop/connectivity/wifi/wifi-permissions?hl=de">...</a>
     */
    public static @NonNull String[] getRequiredAppPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{
                    ACCESS_WIFI_STATE, CHANGE_NETWORK_STATE, NEARBY_WIFI_DEVICES, ACCESS_FINE_LOCATION, POST_NOTIFICATIONS
            };
        } else {
            return new String[]{
                    ACCESS_WIFI_STATE, CHANGE_NETWORK_STATE, ACCESS_FINE_LOCATION
            };
        }
    }
}
