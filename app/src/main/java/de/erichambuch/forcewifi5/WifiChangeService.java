package de.erichambuch.forcewifi5;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.ACCESS_WIFI_STATE;
import static android.Manifest.permission.CHANGE_WIFI_STATE;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
import static de.erichambuch.forcewifi5.WifiUtils.hasNormalizedSSID;
import static de.erichambuch.forcewifi5.WifiUtils.normalizeSSID;

import android.app.Notification;
import android.app.NotificationManager;
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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RequiresPermission;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
		private Notification notification;

		public WifiServiceConnection(Context context) {
			this.context = context;
		}

		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			final LocalBinder binder = (LocalBinder) service;
			final WifiChangeService myService = binder.getService();
			myService.connection = this;

			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				NotificationCompat.Builder builder =
						new NotificationCompat.Builder(context, MainActivity.CHANNEL_ID)
								.setContentTitle(context.getText(R.string.app_name))
								.setContentText(context.getText(R.string.title_activation))
								.setSmallIcon(R.mipmap.ic_launcher)
								.setAutoCancel(false)
								.setCategory(Notification.CATEGORY_SERVICE)
								.setTicker(context.getText(R.string.title_activation));
				try {
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
						// With Android 12+ we could get an android.app.ForegroundServiceStartNotAllowedException due to new restrictions
						builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
						builder.setOngoing(true);
						myService.startForeground(ONGOING_NOTIFICATION_ID, builder.build(), FOREGROUND_SERVICE_TYPE_LOCATION);
					} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
						myService.startForeground(ONGOING_NOTIFICATION_ID, builder.build(), FOREGROUND_SERVICE_TYPE_LOCATION);
					} else {
						myService.startForeground(ONGOING_NOTIFICATION_ID, builder.build());
					}
					// Release the connection to prevent leaks. => may be skipped
					context.unbindService(this);
				} catch (Exception e) {
					Log.i(AppInfo.APP_NAME, "startForeground or unBind failed.", e);
				}
			}
		}

		@Override
		public void onBindingDied(ComponentName name) {
			Log.w(AppInfo.APP_NAME, "Binding has dead.");
		}

		@Override
		public void onNullBinding(ComponentName name) {
			Log.w(AppInfo.APP_NAME, "Bind was null.");
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			Log.w(AppInfo.APP_NAME, "Service is disconnected..");
		}
	}

	public static final int ONGOING_NOTIFICATION_ID = 123;

	public class LocalBinder extends Binder {
		@NonNull
		public WifiChangeService getService() {
			return WifiChangeService.this;
		}
	}

	private final LocalBinder binder = new LocalBinder();

	WifiServiceConnection connection = null;

	protected WifiChangeService(@NonNull Context context) {
		super();
		attachBaseContext(context);
	}

	public WifiChangeService() {
		super();
	}

	/**
	 * Solution for ANR problem according to {@see https://stackoverflow.com/questions/44425584/context-startforegroundservice-did-not-then-call-service-startforeground}
	 * @param intent
	 * @return the service instance
	 */
	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return binder;
	}

	/**
	 Disabled as leeds to many exceptions:
	 @Override public void onDestroy() {
	 try {
	 if (connection != null)
	 getApplicationContext().unbindService(connection);
	 } catch(Exception e) {
	 Log.i(AppInfo.APP_NAME, "Unbinding service failed", e);
	 }
	 super.onDestroy();
	 }
	 */

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Log.d(AppInfo.APP_NAME, "Starting WifiChangeService");
		// As of Android 12+ foreground service start is not possible in many cases. We try another way to display a Notification
		// in case we are still in background. We can perform a test by using adb with:
		// adb shell device_config put activity_manager default_fgs_starts_restriction_notification_enabled true
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && this.getForegroundServiceType() != FOREGROUND_SERVICE_TYPE_LOCATION) {
			startForeground(ONGOING_NOTIFICATION_ID, createMessageNotification(R.string.title_activation), FOREGROUND_SERVICE_TYPE_LOCATION);
		}

		if (isActivated()) {
			if (ActivityCompat.checkSelfPermission(this, ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
					&& ActivityCompat.checkSelfPermission(this, CHANGE_WIFI_STATE) == PackageManager.PERMISSION_GRANTED
					&& ActivityCompat.checkSelfPermission(this, ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED) {
				try {
					// Android 9 and above forces handling of foreground services, esp. if using location services
					// we create the foreground service during ServiceConnection creating due to scheduling bugs in Android
					// Workaround from Stackoverflow https://stackoverflow.com/questions/44425584/context-startforegroundservice-did-not-then-call-service-startforegrounds
					updateNetworks();
				} catch (Exception e) {
					Log.e(AppInfo.APP_NAME, "updateNetworks", e);
					showPermissionError();
				} finally {
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
						stopForeground(true);  // Notification and Service ended
				}
			} else {
				Log.e(AppInfo.APP_NAME, "Permissions missing");
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
	@RequiresPermission(allOf = {ACCESS_FINE_LOCATION, CHANGE_WIFI_STATE, ACCESS_WIFI_STATE})
	protected void updateNetworks() throws SecurityException {
		Log.d(AppInfo.APP_NAME, "Started updateNetworks...");
		final WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
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
				final StringBuilder suggestionsString = new StringBuilder(256); // wir müssen parallel noch das als String mitführen
				for (ScanResult result : scanResults) {
					final int signalLevel = WifiManager.calculateSignalLevel(result.level, 100);
					if (isWantedFrequency(result.frequency)
							&& (switchToOtherSSID || result.SSID.equals(normalizeSSID(activeWifi.getSSID())))
							&& signalLevel >= getMinimumLevel()
							&& signalLevel > minimumSignalLevel) {
						// found Wifi -> try to connect to it
						if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
							final WifiNetworkSuggestion.Builder suggestionBuilder = new WifiNetworkSuggestion.Builder();
							suggestionBuilder.setSsid(normalizeSSID(result.SSID));
							suggestionsString.append(normalizeSSID(result.SSID)).append(" - ");
							if (result.BSSID != null) {
								suggestionBuilder.setBssid(MacAddress.fromString(result.BSSID));
								suggestionsString.append(result.BSSID);
							}

							suggestionsString.append(" recommended at prio ").append(priority);
							suggestionBuilder.setPriority(priority++);
							suggestions.add(suggestionBuilder.build());
							// special case: wir haben ein Netzwerk mit normalisierter SSID, versuchen wir dazuzufügen
							if (hasNormalizedSSID(result.SSID)) {
								suggestionBuilder.setSsid(result.SSID); // mit ""
								suggestions.add(suggestionBuilder.build()); // und zweite Suggestion
							}
							minimumSignalLevel = signalLevel;
							reconnected = true;
							// for Repeaters with different access points - we try to find a stronger signal, so don't break: continue;
						} else {
							// geht nur < Android 10: Vorsicht: die BSSID kann NULL sein und wir bekommen in der Liste der Netzwerke
							// nur die SSIDs, nicht zwigend die verschiedenen BSSIDs, so dass wir diese unterscheiden können.
							List<WifiConfiguration> configs = wifiManager.getConfiguredNetworks();
							for (WifiConfiguration config : configs) {
								if (normalizeSSID(config.SSID).equals(result.SSID) && (config.BSSID == null || config.BSSID.equals(result.BSSID))) {
									// assume: thats the 5GHz point - we cannot be sure, but give a try; some Android versions keep the same Network 2/5 GhZ
									// under the same networkId, so we don't have a chance to distinguish them
									reconnected = true;
									minimumSignalLevel = signalLevel;
									networkId = config.networkId;

									suggestionsString.append(result.SSID).append(" - ").append(result.BSSID).append(" recommended with ID ").append(config.networkId);
									break;
								}
							}

						}
					}
				}
				if (reconnected) {
					Log.d(AppInfo.APP_NAME, "Try to reconnect");
					if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && suggestions.size() > 0) {
						// show suggestion in Notification
						final Notification notification =
								new NotificationCompat.Builder(this, MainActivity.CHANNEL_ID)
										.setContentTitle(this.getText(R.string.app_name))
										.setContentText(this.getText(R.string.title_activation))
										.setSmallIcon(R.mipmap.ic_launcher)
										.setAutoCancel(true)
										.setCategory(Notification.CATEGORY_MESSAGE)
										.setTicker(this.getText(R.string.info_switch_wifi_5ghz) + " " + suggestionToString(suggestions.get(0)))
										.build();
						NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
						notificationManager.notify(ONGOING_NOTIFICATION_ID, notification);

						// disconnect geht nicht mehr: https://issuetracker.google.com/issues/128554616
						final List<WifiNetworkSuggestion> actualSuggestions = getActualSuggestions(wifiManager);
						if (actualSuggestions.equals(suggestions)) {
							Log.i(AppInfo.APP_NAME, "Suggestions already given: " + actualSuggestions);
							showError(R.string.error_suggestion_not_taken);
							// suggestions have already been set - no change to change anything
							return;
						} else {
							int returnCode = wifiManager.removeNetworkSuggestions(actualSuggestions);
							Log.i(AppInfo.APP_NAME, "removeNetworks, RC=" + returnCode);
						}
						final int returnCode = wifiManager.addNetworkSuggestions(suggestions);
						Log.i(AppInfo.APP_NAME, "Switch to Wifis: " + suggestions + " rc=" + returnCode);
						switch (returnCode) {
							case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE:
								showError(R.string.error_permission_duplicate);
								break;
							case WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS:
								showError(R.string.info_switch_wifi_5ghz_android10);
								break;
							case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED:
								showError(R.string.error_permission_missing);
								break;
							case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_EXCEEDS_MAX_PER_APP:
							case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_INVALID:
							case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_NOT_ALLOWED:
							case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_INTERNAL:
							case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_REMOVE_INVALID:
							case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_RESTRICTED_BY_ADMIN:
							default:
								showError(R.string.error_switch_wifi_android10);
								break;
						}

					} else if (networkId >= 0) {
						// Check auf networkId != activeWifi.getNetworkId() bringt nichts, weil Android unter derselben networkId
						// ein und dasselbe Netzwerk mit 2/5 GHz führt, dass kann zu nervigen Endlosschleifen führen
						wifiManager.disconnect(); // kein disable, sonst geht evtl. gar nichts mehr
						wifiManager.enableNetwork(networkId, true); // lt. Javadoc geht das mit TargetAPI = 29 nicht mehr
						Log.i(AppInfo.APP_NAME, "Switch to Wifi: " + networkId);
						showError(R.string.info_switch_wifi_5ghz);
					}

					// and display by sending a broadcast to MainActivity
					final Intent intent = new Intent(MainActivity.INTENT_WIFICHANGETEXT);
					intent.putExtra(MainActivity.EXTRA_WIFICHANGETEXT, suggestionsString.toString());
					LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
				} else
					showError(R.string.error_5ghz_not_configured);
			} else {
				showError(R.string.info_5ghz_active);
			}
		}
	}

	@RequiresPermission(ACCESS_WIFI_STATE)
	private List<WifiNetworkSuggestion> getActualSuggestions(WifiManager wifiManager) {
		return (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ?
				wifiManager.getNetworkSuggestions() : Collections.emptyList();
	}

	private String suggestionToString(WifiNetworkSuggestion suggestion) {
		StringBuilder builder = new StringBuilder(32);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			builder.append(suggestion.getSsid()).append(" [").append(suggestion.getBssid()).append("]");
		}
		return builder.toString();
	}

	/**
	 * Removes all network suggestions from app. This may be called during switch-off or deinstallation (will be done by Android).
	 */
	@RequiresPermission(value = "android.permission.CHANGE_WIFI_STATE")
	public static void removeSuggestions(@NonNull Context context) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			WifiManager wifiManager = (WifiManager) context.getSystemService(WIFI_SERVICE);
			int rc = wifiManager.removeNetworkSuggestions(Collections.emptyList());
			Log.i(AppInfo.APP_NAME, "Removing all network suggestings from app: " + rc);
		}
	}

	private void showError(int stringId) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && NotificationManagerCompat.from(this).areNotificationsEnabled()) {
			try {
				NotificationManagerCompat.from(this).notify(ONGOING_NOTIFICATION_ID, createMessageNotification(stringId));
			} catch(SecurityException e) {
				showError(R.string.error_no_permissions_notification);
			}
		} else
			Toast.makeText(getApplicationContext(), stringId, Toast.LENGTH_LONG).show();
	}


	/**
	 * Check if Location Services are enabled. If not, raise a notification to the user.
	 * @return <var>true</var> if enabled
	 */
	private boolean checkLocationServices() {
		if (!MainActivity.isLocationServicesEnabled(this)) {
			// show notification that location services must be enabled to get list of scanned wifis
			Intent locationIntent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
			locationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
			PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, locationIntent, PendingIntent.FLAG_IMMUTABLE);
			NotificationCompat.Builder builder = new NotificationCompat.Builder(this, MainActivity.CHANNEL_ID)
					.setSmallIcon(R.mipmap.ic_launcher)
					.setContentTitle(getString(R.string.app_name))
					.setContentText(getString(R.string.error_no_location_enabled))
					.setPriority(NotificationCompat.PRIORITY_DEFAULT)
					.setAutoCancel(true)
					.setContentIntent(pendingIntent);
			if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
				try {
					NotificationManagerCompat.from(this).notify(ONGOING_NOTIFICATION_ID, builder.build());
				} catch(SecurityException e) {
					showError(R.string.error_no_permissions_notification);
				}
			} else {
				showError(R.string.error_no_location_enabled);
			}
			return false;
		}
		return true;
	}

	/**
	 * Show notification to user if permissions are missing.
	 */
	protected void showPermissionError() {
		Intent locationIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
		locationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
		locationIntent.setData(Uri.fromParts("package", getPackageName(), null));
		PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, locationIntent, PendingIntent.FLAG_IMMUTABLE);
		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, MainActivity.CHANNEL_ID)
				.setSmallIcon(R.mipmap.ic_launcher)
				.setContentTitle(getString(R.string.app_name))
				.setContentText(getString(R.string.error_permission_missing))
				.setPriority(NotificationCompat.PRIORITY_DEFAULT)
				.setAutoCancel(true)
				.setContentIntent(pendingIntent);
		if(NotificationManagerCompat.from(this).areNotificationsEnabled()) {
			try {
				NotificationManagerCompat.from(this).notify(ONGOING_NOTIFICATION_ID, builder.build());
			} catch(SecurityException e) {
				showError(R.string.error_no_permissions_notification);
			}
		} else {
			showError(R.string.error_permission_missing);
		}
	}

	@NonNull
	@RequiresApi(api = Build.VERSION_CODES.O)
	protected Notification createMessageNotification(int resourceId) {
		NotificationCompat.Builder builder =
				new NotificationCompat.Builder(this, MainActivity.CHANNEL_ID)
						.setContentTitle(getText(R.string.app_name))
						.setContentText(getText(resourceId))
						.setSmallIcon(R.mipmap.ic_launcher)
						.setAutoCancel(false)
						.setCategory(Notification.CATEGORY_MESSAGE)
						.setTicker(getText(resourceId));
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
			builder.setOngoing(true);
		}
		return builder.build();
	}

	protected boolean isActivated() {
		return PreferenceManager.getDefaultSharedPreferences(this).getBoolean(getString(R.string.prefs_activation), true);
	}

	private boolean is5GHzPreferred() {
		return ("1".equals(PreferenceManager.getDefaultSharedPreferences(this).getString(getString(R.string.prefs_2ghz5ghz), "1")));
	}

	private boolean is6GHzPreferred() {
		return ("2".equals(PreferenceManager.getDefaultSharedPreferences(this).getString(getString(R.string.prefs_2ghz5ghz), "1")));
	}

	private boolean isWrongFrequency(int freq) {
		return !isWantedFrequency(freq);
	}

	public boolean isWantedFrequency(int freq) {
		if (is6GHzPreferred()) {
			return (freq >= 5925);
		} else if (is5GHzPreferred())
			return (freq >= 5000 && freq <= 5920);
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
}