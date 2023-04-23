package de.erichambuch.forcewifi5;

import android.net.wifi.WifiInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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
        if (ssid.startsWith("\"") && ssid.endsWith("\"")){
            return ssid.substring(1, ssid.length()-1);
        } else
            return ssid;
    }

    public static boolean hasNormalizedSSID(@Nullable String ssid) {
        return (ssid != null && ssid.startsWith("\"") && ssid.endsWith("\""));
    }
}
