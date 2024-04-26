package de.erichambuch.forcewifi5;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.ACCESS_WIFI_STATE;
import static android.Manifest.permission.CHANGE_NETWORK_STATE;
import static android.Manifest.permission.CHANGE_WIFI_STATE;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
import static de.erichambuch.forcewifi5.WifiUtils.getPreferredNetworkFrequencies;
import static de.erichambuch.forcewifi5.WifiUtils.hasNormalizedSSID;
import static de.erichambuch.forcewifi5.WifiUtils.is5GHzPreferred;
import static de.erichambuch.forcewifi5.WifiUtils.normalizeSSID;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.MacAddress;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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
								.setSilent(true)
								.setContentIntent(PendingIntent.getActivity(context, 0,
										new Intent(Intent.ACTION_VIEW, null, context.getApplicationContext(), MainActivity.class),
										PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE))
								.setCategory(Notification.CATEGORY_SERVICE)
								.setTicker(context.getText(R.string.title_activation));
				try {
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
						// with Android 14 we cannot start a Foreground Service anymore
						myService.startService(new Intent(myService.getApplicationContext(), WifiChangeService14.class));
						if (NotificationManagerCompat.from(context).areNotificationsEnabled())
							if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
								showError(this.context, R.string.error_no_permissions_notification);
							} else
								NotificationManagerCompat.from(context).notify(ONGOING_NOTIFICATION_ID, builder.build());
					} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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

	static class ChangeNetworkCallback extends ConnectivityManager.NetworkCallback {

		private final Context ctx;
		ChangeNetworkCallback(@NonNull Context context) {
			this.ctx = context;
		}
		@Override
		public void onAvailable(@NonNull Network network) {
			Log.i(AppInfo.APP_NAME, "Available requested network: "+network);
			showNotificationMessage(ctx, ctx.getString(R.string.text_wifichange_successful));
		}
		@Override
		public void onUnavailable() {
			Log.w(AppInfo.APP_NAME, "Unavailabled requested network");
			showNotificationMessage(ctx, ctx.getString(R.string.error_suggestion_not_taken));
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
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
				this.getForegroundServiceType() != FOREGROUND_SERVICE_TYPE_LOCATION) {
			startForeground(ONGOING_NOTIFICATION_ID, createMessageNotification(this, R.string.title_activation), FOREGROUND_SERVICE_TYPE_LOCATION);
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
	@RequiresPermission(allOf = {ACCESS_FINE_LOCATION, CHANGE_WIFI_STATE, ACCESS_WIFI_STATE, CHANGE_NETWORK_STATE})
	protected void updateNetworks() throws SecurityException {
		Log.d(AppInfo.APP_NAME, "Started updateNetworks...");
		final WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
		if (isActivated() && wifiManager.isWifiEnabled()) {
			final WifiInfo activeWifi = wifiManager.getConnectionInfo();
			if (activeWifi != null && WifiUtils.isWrongFrequency(this, activeWifi.getFrequency())) {
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
					final int signalLevel = WifiUtils.calculateWifiLevel(wifiManager, result.level);
					if (WifiUtils.isWantedFrequency(this, result.frequency)
							&& (switchToOtherSSID || result.SSID.equals(normalizeSSID(activeWifi.getSSID())))
							&& signalLevel >= getMinimumLevel()
							&& signalLevel > minimumSignalLevel) {
						// found Wifi -> try to connect to it
						if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
							final WifiNetworkSuggestion.Builder suggestionBuilder = new WifiNetworkSuggestion.Builder();
							suggestionBuilder.setSsid(normalizeSSID(result.SSID));
							suggestionsString.append(normalizeSSID(result.SSID)).append(" - ");
							if (result.BSSID != null) {
								suggestionBuilder.setBssid(MacAddress.fromString(result.BSSID));
								suggestionsString.append(result.BSSID);
							}

							suggestionsString.append(" recommended at prio ").append(priority).append(". Please disable and re-enable your Wifi.");
							suggestionBuilder.setPriority(priority++);
							suggestions.add(suggestionBuilder.build());
							// special case: wir haben ein Netzwerk mit normalisierter SSID, versuchen wir dazuzufügen
							if (hasNormalizedSSID(result.SSID)) {
								Log.d(AppInfo.APP_NAME, "Adding normalized SSID "+result.SSID);
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
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !suggestions.isEmpty()) {
						// show suggestion in Notification
						showNotificationMessage(getApplicationContext(), this.getText(R.string.info_switch_wifi_5ghz) + " " + suggestionToString(suggestions.get(0)));
						// Starting with API 33, Android allows to use setWifiEnabled in certain cases (device owner, etc.), so we give a try
						// disconnect geht nicht mehr: https://issuetracker.google.com/issues/128554616
						if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ) {
							try {
								wifiManager.setWifiEnabled(false);
							} catch (Exception e) {
								Log.w(AppInfo.APP_NAME, "Wifi disabling/enabled failed", e);
							}
						}

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
								// Starting with API 33, Android allows to use setWifiEnabled in certain cases (device owner, etc.), so we give a try
								// disconnect geht nicht mehr: https://issuetracker.google.com/issues/128554616
								if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ) {
									boolean changed = false;
									try {
										changed = wifiManager.setWifiEnabled(true);
									} catch (Exception e) {
										Log.w(AppInfo.APP_NAME, "Wifi disabling/enabled failed", e);
									} finally {
										// try another way, for Android Q+ the flag changed is always "false" as per spec
										if(!changed && !isAggressive()) {
											try {
												startActivity(getWifiIntent(getApplicationContext()));
											} catch(Exception e2) {
												Crashlytics.recordException(e2);
											}
										}
									}
								}
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
						// Aggressive request of a specific network (from Android 12)
						if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isAggressive() && !suggestions.isEmpty()) {
							aggressiveNetworkChange(this, suggestions.get(0));
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

	/**
	 * Force an aggressive network change by requesting a specific network.
	 * @param context my context
	 * @param suggestion the wifi suggestion
	 */
	static void aggressiveNetworkChange(@NonNull Context context, @NonNull WifiNetworkSuggestion suggestion) {
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			try {
				final WifiNetworkSpecifier.Builder specificerBuild = new WifiNetworkSpecifier.Builder();
				if(suggestion.getBssid() != null) // only one setter is allowed for WifiSpecifier
					specificerBuild.setBssid(suggestion.getBssid());
				else
					specificerBuild.setSsid(suggestion.getSsid());
				specificerBuild.setBand(getBand(context)); // this is required to avoid a copying of the request wher only the band is transferred internally
				// experimental feature to define dedicated channels, not available on all devices
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
					int[] freqs = getPreferredNetworkFrequencies(context);
					if(freqs.length > 0)
						specificerBuild.setPreferredChannelsFrequenciesMhz(freqs);
				}
				final NetworkRequest request = new NetworkRequest.Builder().
						addTransportType(NetworkCapabilities.TRANSPORT_WIFI).
						setIncludeOtherUidNetworks(true).  // we also want the system Wifis
						setNetworkSpecifier(specificerBuild.build()).
						build();
				Log.i(AppInfo.APP_NAME, "Requesting "+request);
				context.getSystemService(ConnectivityManager.class).requestNetwork(request, new ChangeNetworkCallback(context.getApplicationContext()));
				return;
			} catch(Exception e) {
				Crashlytics.recordException(e);
			}
		}
	}
	@RequiresPermission(ACCESS_WIFI_STATE)
	static List<WifiNetworkSuggestion> getActualSuggestions(WifiManager wifiManager) {
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

	@RequiresPermission(value = "android.permission.CHANGE_WIFI_STATE")
	public static int provideSuggestions(@NonNull Context context, @NonNull List<WifiNetworkSuggestion> suggestionList) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			WifiManager wifiManager = (WifiManager) context.getSystemService(WIFI_SERVICE);
			wifiManager.removeNetworkSuggestions(Collections.emptyList());
			int rc = wifiManager.addNetworkSuggestions(suggestionList);
			Log.i(AppInfo.APP_NAME, "Provided new network suggestings from app: " + rc);
			return rc;
		}
		return 0;
	}

	private void showError(int stringId) {
		showError(getApplicationContext(), stringId, false);
	}
	static void showError(Context context, int stringId) {
		showError(context, stringId, false);
	}

	private static void showError(Context context, int stringId, boolean forceToast) {
		if (!forceToast && NotificationManagerCompat.from(context).areNotificationsEnabled()) {
			try {
				NotificationManagerCompat.from(context).notify(ONGOING_NOTIFICATION_ID, createMessageNotification(context, stringId));
			} catch (SecurityException e) {
				showError(context, R.string.error_no_permissions_notification, forceToast);
			}
		} else {
			// a bit tricky to display a toast from a (background) service
			new Handler(Looper.getMainLooper()).post(new Runnable() {

				@Override
				public void run() {
					Toast.makeText(context, stringId, Toast.LENGTH_LONG).show();
				}
			});
		}
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
	protected static Notification createMessageNotification(Context context, int resourceId) {
		NotificationCompat.Builder builder =
				new NotificationCompat.Builder(context, MainActivity.CHANNEL_ID)
						.setContentTitle(context.getText(R.string.app_name))
						.setContentText(context.getText(resourceId))
						.setSmallIcon(R.mipmap.ic_launcher)
						.setAutoCancel(false)
						.setSilent(true)
						.setContentIntent(PendingIntent.getActivity(context
										.getApplicationContext(), 0,
								new Intent(Intent.ACTION_VIEW, null, context.getApplicationContext(), MainActivity.class),
								PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE))
						.setCategory(Notification.CATEGORY_MESSAGE)
						.setTicker(context.getText(resourceId));
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
			builder.setOngoing(true);
		}
		return builder.build();
	}

	static void showNotificationMessage(Context context, String msg) {
		final Notification notification =
				new NotificationCompat.Builder(context, MainActivity.CHANNEL_ID)
						.setContentTitle(context.getText(R.string.app_name))
						.setContentText(msg)
						.setSmallIcon(R.mipmap.ic_launcher)
						.setAutoCancel(true)
						.setSilent(true)
						.setContentIntent(PendingIntent.getActivity(context.getApplicationContext(), 0,
								new Intent("android.settings.panel.action.INTERNET_CONNECTIVITY"),
								PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE))
						.setCategory(Notification.CATEGORY_MESSAGE)
						.setTicker(msg)
						.build();
		if(NotificationManagerCompat.from(context).areNotificationsEnabled()) {
			try {
				NotificationManagerCompat.from(context).notify(ONGOING_NOTIFICATION_ID, notification);
			} catch(SecurityException e) {
				Log.e(AppInfo.APP_NAME, context.getString(R.string.error_no_permissions_notification), e);
			}
		}
	}

	protected boolean isActivated() {
		return PreferenceManager.getDefaultSharedPreferences(this).getBoolean(getString(R.string.prefs_activation), true);
	}

	private boolean isAggressive() {
		return PreferenceManager.getDefaultSharedPreferences(this).getBoolean(getString(R.string.prefs_aggressive_change), true);
	}

	@RequiresApi(api = Build.VERSION_CODES.S)
	public static int getBand(@NonNull Context context) {
		if(WifiUtils.is60GHzPreferred(context))
			return ScanResult.WIFI_BAND_60_GHZ;
		else if(WifiUtils.is6GHzPreferred(context))
			return ScanResult.WIFI_BAND_6_GHZ;
		else if(is5GHzPreferred(context))
			return ScanResult.WIFI_BAND_5_GHZ;
		else
			return ScanResult.WIFI_BAND_24_GHZ;
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
	 * Returns the best Intent to switch Wifis off and on again.
	 * <p>Depending on the Android version and device different actions are supported.</p>
	 * @param context
	 * @return the intent
	 * @see <a href="https://stackoverflow.com/questions/63124728/connect-to-wifi-in-android-q-programmatically">Stack Overflow Question</a>
	 */
	@NonNull
	public static Intent getWifiIntent(@NonNull Context context) {
		Intent intent = new Intent("android.settings.panel.action.INTERNET_CONNECTIVITY");
		if(intent.resolveActivity(context.getPackageManager()) != null)
			return intent;
		intent = new Intent("android.settings.panel.action.WIFI");
		if(intent.resolveActivity(context.getPackageManager()) != null)
			return intent;
		return new Intent(Settings.ACTION_WIFI_SETTINGS);
	}
}