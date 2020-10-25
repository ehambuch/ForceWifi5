package de.erichambuch.forcewifi5;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.MacAddress;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;
import android.os.IBinder;
import android.widget.Toast;

import androidx.annotation.RequiresPermission;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.preference.PreferenceManager;

import java.util.Collections;
import java.util.List;

/**
 * Service, der alle Aktionen im Hintergrund ausführt.
 */
@SuppressWarnings("deprecation")
public class WifiChangeService extends Service {

	private List<WifiNetworkSuggestion> oldSuggestions;

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (isActivated()) {
			if (!MainActivity.isLocationServicesEnabled(this)) {
				// show notification that location services must be enabled to get list of scanned wifis
				Intent locationIntent = new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS);
				locationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
				PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, locationIntent, 0);
				NotificationCompat.Builder builder = new NotificationCompat.Builder(this, MainActivity.CHANNEL_ID)
						.setSmallIcon(R.drawable.ic_launcher_background)
						.setContentTitle(getString(R.string.app_name))
						.setContentText(getString(R.string.error_no_location_enabled))
						.setPriority(NotificationCompat.PRIORITY_DEFAULT)
						.setAutoCancel(true)
						.setContentIntent(pendingIntent);
				NotificationManagerCompat.from(this).notify(4711, builder.build());
			} else if(ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
				updateNetworks();
			} // else: Permission fehlt
		}
		return START_NOT_STICKY;
	}

	/**
	 * Switch Wifi network: if connected to 2.4 and another with same SSID is available
	 * on 5 Ghz, then try to re-connect.
	 */
	@RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
	private void updateNetworks() {
		WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
		if (isActivated() && wifiManager.isWifiEnabled()) {
			WifiInfo activeWifi = wifiManager.getConnectionInfo();
			if (activeWifi != null && isWrongFrequency(activeWifi.getFrequency())) {
				boolean reconnected = false;
				List<ScanResult> scanResults = wifiManager.getScanResults();
				for (ScanResult result : scanResults) {
					if (isWantedFrequency(result.frequency)
							&& result.SSID.equals(activeWifi.getSSID())
							&& WifiManager.calculateSignalLevel(result.level,100) >= getMinimumLevel()) {
						// found Wifi -> try to connect to it
						if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ) {
							// TODO: bei Repeatern: Access point mit stärkstem Signal suchen
							WifiNetworkSuggestion suggestion = new WifiNetworkSuggestion.Builder().setSsid(result.SSID)
									.setBssid(MacAddress.fromString(result.BSSID)).build();
							// remove previous ones
							if (oldSuggestions != null)
								wifiManager.removeNetworkSuggestions(oldSuggestions);
							wifiManager.addNetworkSuggestions(oldSuggestions = Collections.singletonList(suggestion));
							showError(R.string.info_switch_wifi_5ghz);
							reconnected = true;
							break;
						} else {
							// geht nur < Android 10
							@SuppressLint("MissingPermission") List<WifiConfiguration> configs = wifiManager.getConfiguredNetworks();
							for(WifiConfiguration config: configs) {
								if ( config.SSID.equals(result.SSID) && config.BSSID.equals(result.BSSID)) {
									// assume: thats the 5GHz point
									wifiManager.enableNetwork(config.networkId, true);
									reconnected = true;
									break;
								}
							}
							// probably other wifi config not saved yet...
							if(!reconnected)
								showError(R.string.error_5ghz_not_configured);
						}						
					}
				}
			} else
				showError(R.string.info_5ghz_active);
		} else
			showError(R.string.error_wifi_not_enabled);

	}

	private void showError(int stringId) {
		Toast.makeText(getApplicationContext(), stringId, Toast.LENGTH_LONG).show();
	}

	@Override
	public IBinder onBind(Intent arg0) {
		return null;
	}

	private boolean isActivated() {
		return PreferenceManager.getDefaultSharedPreferences(this).getBoolean(getString(R.string.prefs_activation), true);
	}

	private boolean is5GHzPreferred() {
		return ("1".equals(PreferenceManager.getDefaultSharedPreferences(this).getString(getString(R.string.prefs_2ghz5ghz), "1")));
	}

	private boolean isWrongFrequency(int freq) {
		final boolean preferred5Ghz = is5GHzPreferred();
		if (preferred5Ghz)
			return (freq < 3000);
		else
			return (freq >= 5000);
	}

	private boolean isWantedFrequency(int freq) {
		final boolean preferred5Ghz = is5GHzPreferred();
		if (preferred5Ghz)
			return (freq >= 5000);
		else
			return (freq < 3000);
	}

	/**
	 * Minimales Signallevel, damit Wechsel iniitiert wird.
	 * @return min (0-100)
	 */
	private int getMinimumLevel() {
		return PreferenceManager.getDefaultSharedPreferences(this).getInt(getString(R.string.prefs_signallevel), 30);
	}
}