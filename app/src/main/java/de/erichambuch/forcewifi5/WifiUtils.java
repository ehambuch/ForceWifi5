package de.erichambuch.forcewifi5;

import android.net.wifi.WifiInfo;

public class WifiUtils {

    /**
     * Normalizes the SSID (remote quotation marks)
     *
     * @param ssid SSID
     * @return normaoized SSID
     * @see WifiInfo#getSSID()
     */
    public static String normalizeSSID(String ssid) {
        if (ssid == null )
            return "";
        if (ssid.startsWith("\"") && ssid.endsWith("\"")){
            return ssid.substring(1, ssid.length()-1);
        } else
            return ssid;
    }

    public static boolean hasNormalizedSSID(String ssid) {
        return (ssid != null && ssid.startsWith("\"") && ssid.endsWith("\""));
    }
}
