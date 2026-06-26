package de.erichambuch.forcewifi5.test;

import static android.net.wifi.ScanResult.WIFI_BAND_24_GHZ;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import android.net.MacAddress;
import android.net.wifi.WifiNetworkSpecifier;
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
        Assert.assertEquals("\"WLAN-TEST\"", WifiUtils.getQuotationalSSID("WLAN-TEST"));
        Assert.assertEquals("\"WLAN-TEST\"", WifiUtils.getQuotationalSSID("\"WLAN-TEST\""));
        Assert.assertEquals("WLAN-TEST", WifiUtils.unquoteSSid("\"WLAN-TEST\""));
        Assert.assertEquals("WLAN-TEST", WifiUtils.unquoteSSid("WLAN-TEST"));
        Assert.assertEquals("\"", WifiUtils.unquoteSSid("\""));
    }

    @Test
    public void testWifiNetworkSpecifier() {
        WifiNetworkSpecifier.Builder specificerBuild = new WifiNetworkSpecifier.Builder();
        specificerBuild.setBssid(MacAddress.fromString("00:13:10:85:fe:01"));
        specificerBuild.setSsid("AndroidWifi");
        assertNotNull(specificerBuild.build());
    }

    @Test
    public void testWifiNetworkSpecifier_withband() {
        WifiNetworkSpecifier.Builder specificerBuild = new WifiNetworkSpecifier.Builder();
        specificerBuild.setBssid(MacAddress.fromString("00:13:10:85:fe:01"));
        specificerBuild.setSsid("AndroidWifi");
        specificerBuild.setBand(WIFI_BAND_24_GHZ);
        assertNotNull(specificerBuild.build());
    }

    /**
     * This test is due to an Android flaw in the code.
     */
    @Test
    public void testWifiNetworkSpecifier_redact() {
        WifiNetworkSpecifier.Builder specificerBuild = new WifiNetworkSpecifier.Builder();
        specificerBuild.setBssid(MacAddress.fromString("00:13:10:85:fe:01"));
        specificerBuild.setSsid("AndroidWifi");
        try {
            assertNotNull(specificerBuild.build().redact());
            fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            // expected: one of setSsidPattern/...
        }
    }
}
