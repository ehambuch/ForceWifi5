package de.erichambuch.forcewifi5.test;

import android.net.MacAddress;
import android.net.wifi.WifiNetworkSuggestion;

import androidx.test.filters.SdkSuppress;

import org.junit.Assert;
import org.junit.Test;

import de.erichambuch.forcewifi5.WifiUtils;

public class NetworkAPITests {

    /**
     * Check if equals works on WifiSuggestions.
     */
    @Test
    @SdkSuppress(minSdkVersion = 30)
    public void testNetworkSuggestionEquals() {
        final WifiNetworkSuggestion.Builder suggestionBuilder = new WifiNetworkSuggestion.Builder();
        suggestionBuilder.setSsid("WLAN-TEST");
        suggestionBuilder.setBssid(MacAddress.fromString("04:33:C2:23:D9:C1"));
        suggestionBuilder.setPriority(1);
        WifiNetworkSuggestion suggestion1 = suggestionBuilder.build();

        suggestionBuilder.setSsid("WLAN-TEST");
        suggestionBuilder.setBssid(MacAddress.fromString("04:33:C2:23:D9:C1"));
        suggestionBuilder.setPriority(2);
        WifiNetworkSuggestion suggestion2 = suggestionBuilder.build();

        Assert.assertEquals(suggestion1, suggestion2);
    }

    @Test
    @SdkSuppress(minSdkVersion = 30)
    public void testNetworkSuggestionNotEquals() {
        final WifiNetworkSuggestion.Builder suggestionBuilder = new WifiNetworkSuggestion.Builder();
        suggestionBuilder.setSsid("WLAN-TEST");
        suggestionBuilder.setBssid(MacAddress.fromString("04:33:C2:23:D9:C1"));
        suggestionBuilder.setPriority(1);
        WifiNetworkSuggestion suggestion1 = suggestionBuilder.build();

        suggestionBuilder.setSsid("WLAN-TEST");
        suggestionBuilder.setBssid(MacAddress.fromString("04:33:C2:23:D9:C2"));
        suggestionBuilder.setPriority(1);
        WifiNetworkSuggestion suggestion2 = suggestionBuilder.build();

        Assert.assertNotEquals(suggestion1, suggestion2);
    }

    @Test
    public void testWifiNormalizer() {
        Assert.assertEquals("WLAN-TEST", WifiUtils.normalizeSSID("WLAN-TEST"));
        Assert.assertEquals("WLAN-TEST", WifiUtils.normalizeSSID("\"WLAN-TEST\""));
        Assert.assertFalse(WifiUtils.hasNormalizedSSID("WLAN-TEST"));
        Assert.assertTrue(WifiUtils.hasNormalizedSSID("\"WLAN-TEST\""));
    }
}
