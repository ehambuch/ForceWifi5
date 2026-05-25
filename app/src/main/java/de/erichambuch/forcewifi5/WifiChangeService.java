package de.erichambuch.forcewifi5;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.ACCESS_WIFI_STATE;
import static android.Manifest.permission.CHANGE_WIFI_STATE;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
import static de.erichambuch.forcewifi5.WifiUtils.getPreferredNetworkFrequencies;
import static de.erichambuch.forcewifi5.WifiUtils.is5GHzPreferred;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.ScanResult;
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
import androidx.core.app.ServiceCompat;
import androidx.preference.PreferenceManager;

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

		public WifiServiceConnection(@NonNull Context context) {
			this.context = context;
		}

		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			final LocalBinder binder = (LocalBinder) service;
			final WifiChangeService myService = binder.getService();
			myService.connection = this;

			// do not show anything in manual mode
			if(!isAutomaticMode(context))
				return;

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
					myService.startService(new Intent(myService.getApplicationContext(), WifiChangeService.class));
					if (NotificationManagerCompat.from(context).areNotificationsEnabled())
						if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
							showError(this.context, R.string.error_no_permissions_notification);
						} else
							NotificationManagerCompat.from(context).notify(ONGOING_NOTIFICATION_ID, builder.build());
				} else {
					builder.setOngoing(true);
					ServiceCompat.startForeground(myService, ONGOING_NOTIFICATION_ID, builder.build(), FOREGROUND_SERVICE_TYPE_LOCATION);
				}
				// Release the connection to prevent leaks. => may be skipped
				context.unbindService(this);
			} catch (Exception e) {
				Log.i(AppInfo.APP_NAME, "startForeground or unBind failed.", e);
				Crashlytics.recordException(e);
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
			// do not show anything in manual mode
			if(!isAutomaticMode(ctx))
				return;
			showNotificationMessage(ctx, ctx.getString(R.string.text_wifichange_successful));
		}
		@Override
		public void onUnavailable() {
			Log.w(AppInfo.APP_NAME, "Unavailabled requested network");
			// do not show anything in manual mode
			if(!isAutomaticMode(ctx))
				return;
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
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
					this.getForegroundServiceType() != FOREGROUND_SERVICE_TYPE_LOCATION) {
				startForeground(ONGOING_NOTIFICATION_ID, createMessageNotification(this, R.string.title_activation), FOREGROUND_SERVICE_TYPE_LOCATION);
			}
			WifiController controller = new WifiController(this);
			if (controller.isActivated()) {
				if (ActivityCompat.checkSelfPermission(this, ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
						&& ActivityCompat.checkSelfPermission(this, CHANGE_WIFI_STATE) == PackageManager.PERMISSION_GRANTED
						&& ActivityCompat.checkSelfPermission(this, ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED) {
					try {
						// Android 9 and above forces handling of foreground services, esp. if using location services
						// we create the foreground service during ServiceConnection creating due to scheduling bugs in Android
						// Workaround from Stackoverflow https://stackoverflow.com/questions/44425584/context-startforegroundservice-did-not-then-call-service-startforegrounds
						controller.updateNetworks();
					} catch (Exception e) {
						Log.e(AppInfo.APP_NAME, "updateNetworks", e);
						Crashlytics.recordException(e);
						controller.showPermissionError();
					} finally {
						if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
							stopForeground(true);  // Notification and Service ended
					}
				} else {
					Log.e(AppInfo.APP_NAME, "Permissions missing");
					controller.showPermissionError();
				}
			}
		} catch(Exception e) {
			Crashlytics.recordException(e);
		}
		return START_NOT_STICKY;
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
				else {
					specificerBuild.setSsid(suggestion.getSsid());
				}
				// this is required to avoid a copying of the request where only the band is transferred internally
				// experimental feature to define dedicated channels, not available on all devices
				// WARNING: setting Band AND Channels togehter results in an IllegalStateException!
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
					int[] freqs = getPreferredNetworkFrequencies(context);
					if(freqs.length > 0)
						specificerBuild.setPreferredChannelsFrequenciesMhz(freqs);
					else
						specificerBuild.setBand(getBand(context));
				} else
					specificerBuild.setBand(getBand(context));
				final NetworkRequest request = new NetworkRequest.Builder().
						addTransportType(NetworkCapabilities.TRANSPORT_WIFI).
						setIncludeOtherUidNetworks(true).  // we also want the system Wifis
						setNetworkSpecifier(specificerBuild.build()).
						build();
				Log.i(AppInfo.APP_NAME, "Requesting "+request);
				context.getSystemService(ConnectivityManager.class).requestNetwork(request, new ChangeNetworkCallback(context.getApplicationContext()));
			} catch(Exception e) {
				Crashlytics.recordException(e);
			}
		}
	}
	private String suggestionToString(WifiNetworkSuggestion suggestion) {
		StringBuilder builder = new StringBuilder(32);
		builder.append(suggestion.getSsid()).append(" [").append(suggestion.getBssid()).append("]");
		return builder.toString();
	}

	/**
	 * Removes all network suggestions from app. This may be called during switch-off or deinstallation (will be done by Android).
	 */
	@RequiresPermission(value = "android.permission.CHANGE_WIFI_STATE")
	public static void removeSuggestions(@NonNull Context context) {
		WifiManager wifiManager = (WifiManager) context.getSystemService(WIFI_SERVICE);
		int rc = wifiManager.removeNetworkSuggestions(Collections.emptyList());
		Log.i(AppInfo.APP_NAME, "Removing all network suggestings from app: " + rc);
	}

	@RequiresPermission(value = "android.permission.CHANGE_WIFI_STATE")
	public static int provideSuggestions(@NonNull Context context, @NonNull List<WifiNetworkSuggestion> suggestionList) {
		WifiManager wifiManager = (WifiManager) context.getSystemService(WIFI_SERVICE);
		wifiManager.removeNetworkSuggestions(Collections.emptyList());
		int rc = wifiManager.addNetworkSuggestions(suggestionList);
		Log.i(AppInfo.APP_NAME, "Provided new network suggestings from app: " + rc);
		return rc;
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
	 * Create a generic message notification, intent to open the app.
	 * @param context my context
	 * @param resourceId text to show
	 * @return notification to display
	 */
	@NonNull
	protected static Notification createMessageNotification(@NonNull Context context, int resourceId) {
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
						.setCategory(Notification.CATEGORY_SERVICE)
						.setTicker(context.getText(resourceId))
						.setStyle(new NotificationCompat.BigTextStyle()
								.bigText(context.getText(resourceId)));
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
			builder.setOngoing(true);
		}
		return builder.build();
	}

	/**
	 * Show a notification for a changed Wifi, with an Intent to open the Wifi settings.
	 * @param context my context
	 * @param msg message to show
	 */
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
						.setCategory(Notification.CATEGORY_SERVICE)
						.setTicker(msg)
						.setStyle(new NotificationCompat.BigTextStyle()
								.bigText(msg))
						.build();
		if(NotificationManagerCompat.from(context).areNotificationsEnabled()) {
			try {
				NotificationManagerCompat.from(context).notify(ONGOING_NOTIFICATION_ID, notification);
			} catch(SecurityException e) {
				Log.e(AppInfo.APP_NAME, context.getString(R.string.error_no_permissions_notification), e);
			}
		}
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
	 * Returns the best Intent to switch Wifis off and on again.
	 * <p>Depending on the Android version and device different actions are supported.</p>
	 * @param context
	 * @return the intent
	 * @see <a href="https://stackoverflow.com/questions/63124728/connect-to-wifi-in-android-q-programmatically">Stack Overflow Question</a>
	 */
	@NonNull
	public static Intent getWifiIntent(@NonNull Context context) {
		Intent intent = new Intent("android.settings.panel.action.INTERNET_CONNECTIVITY");
		intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		if(intent.resolveActivity(context.getPackageManager()) != null)
			return intent;
		intent = new Intent("android.settings.panel.action.WIFI");
		intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		if(intent.resolveActivity(context.getPackageManager()) != null)
			return intent;
		return new Intent(Settings.ACTION_WIFI_SETTINGS).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
	}

	static boolean isAutomaticMode(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context.getString(R.string.prefs_activation), false);
	}
}