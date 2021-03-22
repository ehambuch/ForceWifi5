package de.erichambuch.forcewifi5;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.MacAddress;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.ACCESS_WIFI_STATE;
import static android.Manifest.permission.CHANGE_WIFI_STATE;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;

/**
 * Service that executes the logic in the background.<p>
 * Starting with Android O, foreground services will be used.</p>
 */
public class WifiChangeService extends Service {
	/**
	 * Service connection for workaround of <pre>Context.startForegroundService() did not then call Service.startForeground()</pre> problem,
	 * were service.startForeground() is not started within 5 seconds due to Android scheduling.
	 * @see {https://stackoverflow.com/questions/44425584/context-startforegroundservice-did-not-then-call-service-startforeground}
	 */
	public static class WifiServiceConnection implements android.content.ServiceConnection {

		private final Context context;

		public WifiServiceConnection(Context context) {
			this.context = context;
		}

		@Override
		public void onServiceConnected(ComponentName name, IBinder service)
		{
			LocalBinder binder = (LocalBinder) service;
			WifiChangeService myService = binder.getService();

			if ( Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ) {
				PendingIntent pendingIntent =
						PendingIntent.getActivity(context, 0, new Intent(context, WifiChangeService.class), 0);

				Notification notification =
						new Notification.Builder(context, MainActivity.CHANNEL_ID)
								.setContentTitle(context.getText(R.string.app_name))
								.setContentText(context.getText(R.string.title_activation))
								.setSmallIcon(R.mipmap.ic_launcher)
								.setContentIntent(pendingIntent)
								.setTicker(context.getText(R.string.title_activation))
								.build();
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
					myService.startForeground(ONGOING_NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_LOCATION);
				} else {
					myService.startForeground(ONGOING_NOTIFICATION_ID, notification);
				}
				try {
					// Release the connection to prevent leaks. => may be skipped
					context.unbindService(this);
				} catch(Exception e) {
					Log.i(AppInfo.APP_NAME, "unBind failed.", e);
				}
			}
		}

		@Override
		public void onBindingDied(ComponentName name)
		{
			Log.w(AppInfo.APP_NAME, "Binding has dead.");
		}

		@Override
		public void onNullBinding(ComponentName name)
		{
			Log.w(AppInfo.APP_NAME, "Bind was null.");
		}

		@Override
		public void onServiceDisconnected(ComponentName name)
		{
			Log.w(AppInfo.APP_NAME, "Service is disconnected..");
		}
	}

	public static final int ONGOING_NOTIFICATION_ID = 123;

	public class LocalBinder extends Binder
	{
		public WifiChangeService getService()
		{
			return WifiChangeService.this;
		}
	}

	private final LocalBinder binder = new LocalBinder();

	/**
	 * Solution for ANR problem according to {@see https://stackoverflow.com/questions/44425584/context-startforegroundservice-did-not-then-call-service-startforeground}
	 * @param intent
	 * @return the service instance
	 */
	@Nullable
	@Override
	public IBinder onBind(Intent intent)
	{
		return binder;
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Log.d(AppInfo.APP_NAME, "Starting WifiChangeService");
		if (isActivated()) {
			if(ActivityCompat.checkSelfPermission(this, ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
				&& ActivityCompat.checkSelfPermission(this, CHANGE_WIFI_STATE) == PackageManager.PERMISSION_GRANTED
				&& ActivityCompat.checkSelfPermission(this, ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED) {
				try {
					// Android 9 and above forces handling of foreground services, esp. if using location services
					// we create the foreground service during ServiceConnection creating due to scheduling bugs in Android
					// Workaround from Stackoverflow https://stackoverflow.com/questions/44425584/context-startforegroundservice-did-not-then-call-service-startforegrounds
					updateNetworks();
				} catch(Exception e) {
					Log.e(AppInfo.APP_NAME, "updateNetworks", e);
					showPermissionError();
				} finally {
					if ( Build.VERSION.SDK_INT >= Build.VERSION_CODES.O )
						stopForeground(true);
				}
			} else {
				showPermissionError();
			}
		}
		return START_NOT_STICKY;
	}

	/**
	 * Switch Wifi network: if connected to 2.4 and another with same SSID is available
	 * on 5 Ghz, then try to re-connect. Another option is to switch completely as long as
	 * @throws SecurityException on errors
	 */
	@RequiresPermission(allOf = {ACCESS_FINE_LOCATION, CHANGE_WIFI_STATE, ACCESS_WIFI_STATE })
	private void updateNetworks() throws SecurityException {
		WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
		if (isActivated() && wifiManager.isWifiEnabled()) {
			WifiInfo activeWifi = wifiManager.getConnectionInfo();
			if (activeWifi != null && isWrongFrequency(activeWifi.getFrequency())) {
				// at latest here we need enabled location services to get proper results of Wifi data.
				// otherwise getScanResults() and WifiInfo would not contain the sufficient information
				if (!checkLocationServices())
					return;

				boolean reconnected = false;
				List<ScanResult> scanResults = wifiManager.getScanResults();
				int minimumSignalLevel = -1;
				int priority = 1;
				int networkId = -1;
				final boolean switchToOtherSSID = isSwitchToOtherSSID();
				final List<WifiNetworkSuggestion> suggestions = new ArrayList<>(5);
				for (ScanResult result : scanResults) {
					final int signalLevel = WifiManager.calculateSignalLevel(result.level, 100);
					if (isWantedFrequency(result.frequency)
							&& (switchToOtherSSID || result.SSID.equals(normalizeSSID(activeWifi.getSSID())))
							&& signalLevel >= getMinimumLevel()
							&& signalLevel > minimumSignalLevel) {
						// found Wifi -> try to connect to it
						if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
							final WifiNetworkSuggestion.Builder suggestionBuilder = new WifiNetworkSuggestion.Builder().setSsid(result.SSID);
							if ( result.BSSID != null )
								suggestionBuilder.setBssid(MacAddress.fromString(result.BSSID));
							suggestionBuilder.setPriority(priority++);
							suggestions.add(suggestionBuilder.build());
							minimumSignalLevel = signalLevel;
							reconnected = true;
							// for Repeaters with different access points - we try to find a stronger signal, so don't break
							continue;
						} else {
							// geht nur < Android 10: Vorsicht: die BSSID kann NULL sein und wir bekommen in der Liste der Netzwerke
							// nur die SSIDs, nicht zwigend die verschiedenen BSSIDs, so dass wir diese unterscheiden können.
							List<WifiConfiguration> configs = wifiManager.getConfiguredNetworks();
							for (WifiConfiguration config : configs) {
								if (normalizeSSID(config.SSID).equals(result.SSID) && (config.BSSID == null || config.BSSID.equals(result.BSSID))) {
									// assume: thats the 5GHz point - we cannot be sure, but give a try
									reconnected = true;
									minimumSignalLevel = signalLevel;
									continue;
								}
							}
						}
					}
				}
				if (reconnected) {
					if ( android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && suggestions.size() > 0 ) {
						// disconnect geht nicht mehr: https://issuetracker.google.com/issues/128554616
						wifiManager.disconnect();
						wifiManager.removeNetworkSuggestions(Collections.emptyList());
						wifiManager.addNetworkSuggestions(suggestions);
						Log.i(AppInfo.APP_NAME, "Switch to Wifis: "+suggestions);
						showError(R.string.info_switch_wifi_5ghz_android10);
					} else if ( networkId != -1 ){
						wifiManager.disconnect(); // kein disable, sonst geht evtl. gar nichts mehr
						wifiManager.enableNetwork(networkId, true);
						Log.i(AppInfo.APP_NAME, "Switch to Wifi: "+networkId);
						showError(R.string.info_switch_wifi_5ghz);
					}

				}
				else
					showError(R.string.error_5ghz_not_configured);
			} else {
				showError(R.string.info_5ghz_active);
			}
		}
	}

	private void showError(int stringId) {
		Toast.makeText(getApplicationContext(), stringId, Toast.LENGTH_LONG).show();
	}

	/**
	 * Check if Location Services are enabled. If not, raise a notification to the user.
	 * @return <var>true</var> if enabled
	 */
	private boolean checkLocationServices() {
		if (!MainActivity.isLocationServicesEnabled(this)) {
			// show notification that location services must be enabled to get list of scanned wifis
			Intent locationIntent = new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS);
			locationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
			PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, locationIntent, 0);
			NotificationCompat.Builder builder = new NotificationCompat.Builder(this, MainActivity.CHANNEL_ID)
					.setSmallIcon(R.mipmap.ic_launcher)
					.setContentTitle(getString(R.string.app_name))
					.setContentText(getString(R.string.error_no_location_enabled))
					.setPriority(NotificationCompat.PRIORITY_DEFAULT)
					.setAutoCancel(true)
					.setContentIntent(pendingIntent);
			NotificationManagerCompat.from(this).notify(4711, builder.build());
			return false;
		}
		return true;
	}

	/**
	 * Show notification to user if permissions are missing.
	 */
	private void showPermissionError() {
		Intent locationIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
		locationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
		locationIntent.setData(Uri.fromParts("package", getPackageName(), null));
		PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, locationIntent, 0);
		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, MainActivity.CHANNEL_ID)
				.setSmallIcon(R.mipmap.ic_launcher)
				.setContentTitle(getString(R.string.app_name))
				.setContentText(getString(R.string.error_no_location_enabled))
				.setPriority(NotificationCompat.PRIORITY_DEFAULT)
				.setAutoCancel(true)
				.setContentIntent(pendingIntent);
		NotificationManagerCompat.from(this).notify(4711, builder.build());
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

	private boolean isSwitchToOtherSSID() {
		return PreferenceManager.getDefaultSharedPreferences(this).getBoolean(getString(R.string.prefs_switchnetwork), false);
	}

	/**
	 * Normalizes the SSID (remote quotation marks)
	 *
	 * @param ssid SSID
	 * @return normalized SSID
	 * @see WifiInfo#getSSID()
	 */
	private static String normalizeSSID(String ssid) {
		if (ssid == null )
			return "";
		if (ssid.startsWith("\"") && ssid.endsWith("\"")){
			return ssid.substring(1, ssid.length()-1);
		} else
			return ssid;
	}
}