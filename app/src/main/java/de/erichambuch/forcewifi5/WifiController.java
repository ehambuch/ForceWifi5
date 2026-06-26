package de.erichambuch.forcewifi5;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.ACCESS_WIFI_STATE;
import static android.Manifest.permission.CHANGE_NETWORK_STATE;
import static android.Manifest.permission.CHANGE_WIFI_STATE;
import static android.content.Context.WIFI_SERVICE;
import static de.erichambuch.forcewifi5.WifiChangeService.ONGOING_NOTIFICATION_ID;
import static de.erichambuch.forcewifi5.WifiChangeService.showNotificationMessage;
import static de.erichambuch.forcewifi5.WifiUtils.getBand;
import static de.erichambuch.forcewifi5.WifiUtils.getQuotationalSSID;
import static de.erichambuch.forcewifi5.WifiUtils.unquoteSSid;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresPermission;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Controller containing the business logic for Wi-Fi scanning and switching.
 * Can be used by both {@link WifiChangeService} and {@link WifiChangeWorker}.
 */
public class WifiController {

    private final Context context;

    public WifiController(@NonNull Context context) {
        this.context = context;
    }

    public boolean isActivated() {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(context.getString(R.string.prefs_activation), true);
    }

    /**
     * Main logic to update networks and suggest better frequencies.
     */
    @RequiresPermission(allOf = {ACCESS_FINE_LOCATION, CHANGE_WIFI_STATE, ACCESS_WIFI_STATE, CHANGE_NETWORK_STATE})
    public void updateNetworks() throws SecurityException {
        Log.d(AppInfo.APP_NAME, "WifiController: updateNetworks...");
        final WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(WIFI_SERVICE);

        if (wifiManager == null || !wifiManager.isWifiEnabled()) return;

        final WifiInfo activeWifi = wifiManager.getConnectionInfo();
        if (activeWifi != null && WifiUtils.isWrongFrequency(context, activeWifi.getFrequency())) {

            if (!checkLocationServices()) return;

            boolean reconnected = false;
            List<ScanResult> scanResults = wifiManager.getScanResults();
            int minimumSignalLevel = -1;
            int priority = 1;
            int networkId = -1;
            final boolean switchToOtherSSID = isSwitchToOtherSSID();
            final List<WifiNetworkSuggestion> suggestions = new ArrayList<>(5);
            final StringBuilder suggestionsString = new StringBuilder(256);

            for (ScanResult result : scanResults) {
                final int signalLevel = WifiUtils.calculateWifiLevel(wifiManager, result.level);
                if (WifiUtils.isWantedFrequency(context, result.frequency)
                        && (switchToOtherSSID || WifiUtils.isSameSSID(result, activeWifi))
                        && signalLevel >= getMinimumLevel()
                        && signalLevel > minimumSignalLevel) {

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        final WifiNetworkSuggestion.Builder suggestionBuilder = new WifiNetworkSuggestion.Builder();
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && result.getWifiSsid() != null) {
                            suggestionBuilder.setWifiSsid(result.getWifiSsid());
                        } else {
                            suggestionBuilder.setSsid(getQuotationalSSID(result.SSID));
                        }

                        if (result.BSSID != null) {
                            suggestionBuilder.setBssid(android.net.MacAddress.fromString(result.BSSID));
                        }

                        suggestionBuilder.setPriority(priority++);
                        suggestions.add(suggestionBuilder.build());
                        suggestionsString.append(unquoteSSid(result.SSID)).append(" recommended. ");
                        minimumSignalLevel = signalLevel;
                        reconnected = true;
                    } else {
                        // Legacy support for < Android 10
                        @SuppressLint("MissingPermission")
                        List<WifiConfiguration> configs = wifiManager.getConfiguredNetworks();
                        for (WifiConfiguration config : configs) {
                            if (getQuotationalSSID(config.SSID).equals(getQuotationalSSID(result.SSID))) {
                                reconnected = true;
                                networkId = config.networkId;
                                break;
                            }
                        }
                    }
                }
            }

            if (reconnected) {
                handleReconnect(wifiManager, suggestions, getBand(context.getApplicationContext()), networkId, suggestionsString.toString());
            } else {
                WifiChangeService.showError(context, R.string.error_5ghz_not_configured);
            }
        }
    }

    private void handleReconnect(WifiManager wifiManager, List<WifiNetworkSuggestion> suggestions, int band, int networkId, String logMsg) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !suggestions.isEmpty()) {
            showNotificationMessage(context, context.getText(R.string.info_switch_wifi_5ghz).toString());
            wifiManager.removeNetworkSuggestions(new ArrayList<>()); // Clear old
            wifiManager.addNetworkSuggestions(suggestions);
            WifiChangeService.showError(context, R.string.info_switch_wifi_5ghz_android10);

            if (isAggressive() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                WifiChangeService.aggressiveNetworkChange(context, suggestions.get(0), band);
            }
        } else if (networkId >= 0) {
            wifiManager.disconnect();
            wifiManager.enableNetwork(networkId, true);
        }

        // Broadcast to UI
        final Intent intent = new Intent(MainActivity.INTENT_WIFICHANGETEXT);
        intent.setPackage(context.getPackageName());
        intent.putExtra(MainActivity.EXTRA_WIFICHANGETEXT, logMsg);
        context.sendBroadcast(intent);
    }

    private boolean checkLocationServices() {
        if (!MainActivity.isLocationServicesEnabled(context)) {
            Intent locationIntent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            locationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, locationIntent, PendingIntent.FLAG_IMMUTABLE);

            Notification notification = new NotificationCompat.Builder(context, MainActivity.CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(context.getString(R.string.app_name))
                    .setContentText(context.getString(R.string.error_no_location_enabled))
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build();

            NotificationManagerCompat.from(context).notify(ONGOING_NOTIFICATION_ID, notification);
            return false;
        }
        return true;
    }

    public void showPermissionError() {
        Intent settingsIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        settingsIntent.setData(Uri.fromParts("package", context.getPackageName(), null));
        settingsIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, settingsIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(context, MainActivity.CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.error_permission_missing))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build();

        if(NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            try {
                NotificationManagerCompat.from(context).notify(ONGOING_NOTIFICATION_ID, notification);
            } catch(SecurityException e) {
                Crashlytics.recordException(e);
                showError(R.string.error_no_permissions_notification);
            }
        } else {
            showError(R.string.error_permission_missing);
        }
    }

    protected void showError(int stringId) {
        // a bit tricky to display a toast from a (background) service
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context, stringId, Toast.LENGTH_LONG).show());
    }

    private int getMinimumLevel() {
        return PreferenceManager.getDefaultSharedPreferences(context).getInt(context.getString(R.string.prefs_signallevel), 30);
    }

    private boolean isSwitchToOtherSSID() {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context.getString(R.string.prefs_switchnetwork), false);
    }

    private boolean isAggressive() {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context.getString(R.string.prefs_aggressive_change), true);
    }
}
