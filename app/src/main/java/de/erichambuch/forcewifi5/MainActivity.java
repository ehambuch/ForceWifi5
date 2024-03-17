package de.erichambuch.forcewifi5;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.ACCESS_WIFI_STATE;
import static android.Manifest.permission.CHANGE_NETWORK_STATE;
import static android.Manifest.permission.CHANGE_WIFI_STATE;
import static android.Manifest.permission.POST_NOTIFICATIONS;
import static android.content.Intent.ACTION_UNINSTALL_PACKAGE;
import static android.text.Html.FROM_HTML_MODE_LEGACY;
import static de.erichambuch.forcewifi5.WifiChangeService.ONGOING_NOTIFICATION_ID;
import static de.erichambuch.forcewifi5.WifiUtils.normalizeSSID;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.appwidget.AppWidgetManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.MacAddress;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.BadParcelableException;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RequiresPermission;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.location.LocationManagerCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkManager;
import androidx.work.WorkRequest;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.initialization.InitializationStatus;
import com.google.android.gms.ads.initialization.OnInitializationCompleteListener;
import com.google.android.material.bottomappbar.BottomAppBar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.divider.MaterialDividerItemDecoration;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.ump.ConsentDebugSettings;
import com.google.android.ump.ConsentForm;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.UserMessagingPlatform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Main activity für die App.
 */
public class MainActivity extends AppCompatActivity {

	public static final String CHANNEL_ID = "ForceWifi5";

	public static final String INTENT_WIFICHANGETEXT = "de.erichambuch.forcewifi5.WIFICHANGETEXT";

	public static final String EXTRA_WIFICHANGETEXT = "de.erichambuch.forcewifi5.recommendation";

	private static final int REQUEST_CODE_LOCATION_SERVICES = 4567;
	private static final int REQUEST_CODE_PERMISSIONS = 5678;

	/**
	 * Circuit breaker - shared by different listeners.
	 */
	@NonNull
	protected static final AtomicLong lastNetworkCallback = new AtomicLong(0);

	@NonNull
	protected final List<AccessPointEntry> listNetworks = Collections.synchronizedList(new ArrayList<>());

	/**
	 * Flag whether to check for notification enabling or the user dismissed that check.
	 */
	protected volatile boolean checkForNotificationsEnabled = true;

	private final AtomicBoolean isMobileAdsInitializeCalled = new AtomicBoolean(false);
	private final AtomicBoolean initialAdLayoutComplete = new AtomicBoolean(false);

	private AdView adView;

	private ConsentInformation consentInformation;

	/**
	 * Broadcast receiver for WifiManager.NETWORK_STATE_CHANGED_ACTION.
	 * <p>This works due to Android background restrictions only up to Android 9. Above we won't receive any events.</p>
	 */
	static class NetworkStateChangedReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			Log.d(AppInfo.APP_NAME, "NetworkStateChangedReceiver called");
			final long lastTimestamp = System.currentTimeMillis();
			if (lastTimestamp > lastNetworkCallback.get() + (30 * 1000)) { // Circuit breaker
				lastNetworkCallback.set(lastTimestamp);
				final NetworkInfo networkInfo = (NetworkInfo) intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
				if (networkInfo == null || (networkInfo.isConnectedOrConnecting() && networkInfo.getType() == ConnectivityManager.TYPE_WIFI))
					startWifiService(context.getApplicationContext());
			} else
				Log.d(AppInfo.APP_NAME, "Skipped NetworkCallBack");

			updateWidget(context.getApplicationContext());
		}

		private void updateWidget(Context context) {
			// and update widget if any
			final AppWidgetManager appWidgetManager = (AppWidgetManager) context.getSystemService(APPWIDGET_SERVICE);
			final int[] widgetIds = appWidgetManager.getAppWidgetIds(new ComponentName(context, ForceWifiAppWidget.class.getName()));
			if (widgetIds.length > 0) {
				final Intent widgetIntent = new Intent(context.getApplicationContext(), ForceWifiAppWidget.class);
				widgetIntent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
				widgetIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, widgetIds);
				context.sendBroadcast(widgetIntent);
			}
		}
	}

	/**
	 * Network callback: We use this on enabling of a network to initiate a change of the network if required.
	 */
	static class NetworkCallback extends ConnectivityManager.NetworkCallback {

		private final Context myContext;

		NetworkCallback(Context context) {
			myContext = context;
		}

		@Override
		public void onAvailable(@NonNull Network network) {
			Log.d(AppInfo.APP_NAME, "Networkcallback onAvailable");
			// small circuit breaker: if we are called twice within 60 seconds  - then ignore the call
			// so we break up an endless loop of connection failures
			final long lastTimestamp = System.currentTimeMillis();
			if (lastTimestamp > lastNetworkCallback.get() + (60 * 1000)) {
				lastNetworkCallback.set(lastTimestamp);
				// On Android 14+ we cannot start a foreground service anymore, so only send a notification
				if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
					if (ActivityCompat.checkSelfPermission(myContext, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
						NotificationManagerCompat.from(myContext).notify(ONGOING_NOTIFICATION_ID,
								WifiChangeService.createMessageNotification(myContext, R.string.message_forcewifi14_activated));
					}
				} else {
					startWifiService(myContext);
				}
			} else
				Log.d(AppInfo.APP_NAME, "Skipped NetworkCallBack");

			updateWidget();
		}

		@Override
		public void onLost(@NonNull Network network) {
			Log.d(AppInfo.APP_NAME, "Networkcallback onLost");
			updateWidget();
			try {
				NotificationManagerCompat.from(myContext).cancel(ONGOING_NOTIFICATION_ID);
			} catch (Exception e) {
				Log.w(AppInfo.APP_NAME, e);
			}
		}

		@Override
		public void onLinkPropertiesChanged(@NonNull android.net.Network network,
											@NonNull android.net.LinkProperties linkProperties) {
			Log.d(AppInfo.APP_NAME, "Networkcallback onLinkPropertiesChanged");
			// small circuit breaker: if we are called twice within 60 seconds  - then ignore the call
			// so we break up an endless loop of connection failures
			final long lastTimestamp = System.currentTimeMillis();
			if (lastTimestamp > lastNetworkCallback.get() + (60 * 1000)) {
				lastNetworkCallback.set(lastTimestamp);
				// On Android 14+ we cannot start a foreground service anymore, so only send a notification
				if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
					if (ActivityCompat.checkSelfPermission(myContext, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
						NotificationManagerCompat.from(myContext).notify(ONGOING_NOTIFICATION_ID,
								WifiChangeService.createMessageNotification(myContext, R.string.message_forcewifi14_activated));
					}
				} else {
					startWifiService(myContext);
				}
			} else
				Log.d(AppInfo.APP_NAME, "Skipped NetworkCallBack");

			updateWidget();
		}

		private void updateWidget() {
			// and update widget if any
			final AppWidgetManager appWidgetManager = (AppWidgetManager) myContext.getSystemService(APPWIDGET_SERVICE);
			final int[] widgetIds = appWidgetManager.getAppWidgetIds(new ComponentName(myContext, ForceWifiAppWidget.class.getName()));
			if (widgetIds.length > 0) {
				final Intent widgetIntent = new Intent(myContext.getApplicationContext(), ForceWifiAppWidget.class);
				widgetIntent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
				widgetIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, widgetIds);
				myContext.sendBroadcast(widgetIntent);
			}
		}
	}

	public static class NetworkEntry {
		final String name;
		final boolean connected;
		boolean is24ghz;
		boolean is5ghz;
		boolean is6ghz;
		final List<AccessPointEntry> accessPoints = new ArrayList<>(2);

		NetworkEntry(String name, boolean connected) {
			this.name = name;
			this.connected = connected;
		}

		@NonNull
		public String toString() {
			StringBuilder text = new StringBuilder();
			text.append("<b>");
			text.append(name);
			text.append("</b><small><br/>");
			for (AccessPointEntry entry : accessPoints) {
				if (entry.connected)
					text.append("->");
				if (entry.recommended)
					text.append(" * ");
				text.append(entry.bssid);
				text.append(" - ");
				text.append(entry.frequency);
				text.append(" GHz</small>");
				text.append("<br/>");
			}
			// letztes br löschen
			return Html.fromHtml(text.substring(0, text.length() - 5), FROM_HTML_MODE_LEGACY).toString();
		}

		void addAccessPoint(AccessPointEntry entry) {
			this.accessPoints.add(entry);
		}

		public boolean isSuggested() {
			for (AccessPointEntry entry : accessPoints) {
				if (entry.recommended)
					return true;
			}
			return false;
		}
	}

	public static class AccessPointEntry {
		final String name;
		final String bssid;
		final int frequency;
		final int signalLevel;
		final boolean connected;
		final boolean recommended;

		boolean selected;

		AccessPointEntry(String name, String bssid, int freq, int level, boolean connected, boolean suggested) {
			this.name = name;
			this.bssid = bssid;
			this.frequency = freq;
			this.signalLevel = level;
			this.connected = connected;
			this.recommended = suggested;
			this.selected = suggested;
		}

		public @NonNull
		String toString() {
			return this.bssid;
		}

		void setSelected(boolean s) { this.selected = s; }
	}

	/**
	 * Listener sobald Wifi Recommendation erledigt.
	 */
	public class RecommendationListener extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (INTENT_WIFICHANGETEXT.equals(intent.getAction())) {
				// Broadcast von WifiChangeService -> Recommended Wifi anzeigen
				String recommendation = intent.getStringExtra(EXTRA_WIFICHANGETEXT);
				if (recommendation != null) {
					showMessage(recommendation);
					//final TextView view = ((TextView) findViewById(R.id.recommandedwifitextview));
					//if (view != null)
					//	view.setText(Html.fromHtml(recommendation, Html.FROM_HTML_MODE_COMPACT));
				}
			}
		}
	}

	/**
	 * Listener sobald Wifi Scan abgeschlossen. Dann wird die Liste aller Wifis angezeigt.
	 */
	public class ScanFinishedListener extends BroadcastReceiver {

		@Override
		public void onReceive(Context context, Intent intent) {
			if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction())) {
				boolean success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
				Log.d(AppInfo.APP_NAME, "ScanFinished: " + success);
				if (success) {
					// We could start the WifiSevice here again, but it's already started when a network is available (NetworkCallback)
					// therefore we do not call startWifiService(context); anymore
					try {
						if (!MainActivity.this.isFinishing())
							MainActivity.this.listNetworks();
						Toast.makeText(context, R.string.error_scan_succesful, Toast.LENGTH_LONG).show();
					} catch (SecurityException e) {
						Log.e(AppInfo.APP_NAME, "Error listing networks", e);
					}
					// and update widget if any
					final AppWidgetManager appWidgetManager = (AppWidgetManager) context.getSystemService(APPWIDGET_SERVICE);
					final int[] widgetIds = appWidgetManager.getAppWidgetIds(new ComponentName(context, ForceWifiAppWidget.class.getName()));
					if (widgetIds.length > 0) {
						final Intent widgetIntent = new Intent(context.getApplicationContext(), ForceWifiAppWidget.class);
						widgetIntent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
						context.sendBroadcast(widgetIntent);
					}
				} else {
					// nevertheless display old results
					try {
						if (!MainActivity.this.isFinishing())
							MainActivity.this.listNetworks();
					} catch (SecurityException e) {
						Log.e(AppInfo.APP_NAME, "Error listing networks", e);
					}
					Toast.makeText(context, R.string.error_scan_failed_throttle, Toast.LENGTH_LONG).show();
				}
			}
		}
	}

	public static class AddWidgetActivity extends Activity {
		protected void onStart() {
			super.onStart();
			AppWidgetManager mAppWidgetManager = getSystemService(AppWidgetManager.class);
			ComponentName myProvider = new ComponentName(AddWidgetActivity.this, ForceWifiAppWidget.class);
			if (mAppWidgetManager.isRequestPinAppWidgetSupported()) {
				mAppWidgetManager.requestPinAppWidget(myProvider, new Bundle(), null);
			} else
				Toast.makeText(this, R.string.error_not_supported, Toast.LENGTH_LONG).show();
		}
	}

	private final ScanFinishedListener scanFinishedListener = new ScanFinishedListener();
	private final RecommendationListener recommendationListener = new RecommendationListener();

	@SuppressLint("MissingPermission")
	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.activity_main);

		final BottomAppBar appbar = ((BottomAppBar)findViewById(R.id.bottomAppBar));
		appbar.setOnMenuItemClickListener(this::onOptionsItemSelected);

		createNotificationChannel(this);

		findViewById(R.id.floatingButtonReload).setOnClickListener(v -> {
			WifiManager wifiManager = getSystemService(WifiManager.class);
			if (wifiManager != null) {
				boolean scanSuccessful = wifiManager.startScan(); // listNetworks is called by ScanFinishedListener
				if (!scanSuccessful)
					showError(R.string.error_scan_failed_throttle);
			}
		}
		);

		// register a listener to network changes (this may occure twice if already done in StartOnBootReceiver!)
		ConnectivityManager connManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
		connManager.registerNetworkCallback(
				new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
				new NetworkCallback(getApplicationContext()));

		if (Intent.ACTION_MAIN.equals(getIntent().getAction())) // show info dialog only on first start
		{
			if (!checkAndShowNotUsefulDialog())
				checkPermissionDialogs(true);
		} else {
			// Get Permissions right away from the start
			if ((checkSelfPermission(ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) &&
					(checkSelfPermission(ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED) &&
					(checkSelfPermission(CHANGE_NETWORK_STATE) == PackageManager.PERMISSION_GRANTED)) {
				// everything okay
			} else {
				requestMyPermissions(true);
			}
		}

		setUpAdMob();
	}

	private void setUpAdMob() {
		// Code for ads
		if(isAdMob()) {
			ConsentRequestParameters params = new ConsentRequestParameters
					.Builder()
					.setTagForUnderAgeOfConsent(false)
					.setConsentDebugSettings(new ConsentDebugSettings.Builder(this).addTestDeviceHashedId("689110FD20F811E9EE0320C70C769D5D").build())
					.build();
			consentInformation = UserMessagingPlatform.getConsentInformation(this);
			consentInformation.requestConsentInfoUpdate(
					this,
					params,
					(ConsentInformation.OnConsentInfoUpdateSuccessListener) () -> {
						UserMessagingPlatform.loadAndShowConsentFormIfRequired(
								this,
								(ConsentForm.OnConsentFormDismissedListener) loadAndShowError -> {
									if (loadAndShowError != null) {
										// Consent gathering failed.
										Log.w(AppInfo.APP_NAME, String.format("%s: %s",
												loadAndShowError.getErrorCode(),
												loadAndShowError.getMessage()));
									}
									// Consent has been gathered.
									if (consentInformation.canRequestAds()) {
										initializeMobileAdsSdk();
									}
								}
						);
					},
					(ConsentInformation.OnConsentInfoUpdateFailureListener) requestConsentError -> {
						// Consent gathering failed.
						Log.w(AppInfo.APP_NAME, String.format("%s: %s",
								requestConsentError.getErrorCode(),
								requestConsentError.getMessage()));
					});

			if(consentInformation.canRequestAds()) {
				initializeMobileAdsSdk();
			}
			// update AdView Container after full layout completed
			findViewById(R.id.ad_view_container)
					.getViewTreeObserver()
					.addOnGlobalLayoutListener(
							() -> {
								if (!initialAdLayoutComplete.getAndSet(true) && consentInformation.canRequestAds()) {
									loadBanner();
								}
							});

		} else {
			findViewById(R.id.ad_view_container).setVisibility(View.GONE);
		}
	}
	private void initializeMobileAdsSdk() {
		if (isMobileAdsInitializeCalled.getAndSet(true)) {
			return;
		}
		MobileAds.initialize(this, new OnInitializationCompleteListener() {
			@Override
			public void onInitializationComplete(@NonNull InitializationStatus initializationStatus) {
				Log.d(AppInfo.APP_NAME, "Mobile Ads initialized");
			}
		});
		if (initialAdLayoutComplete.get()) {
			loadBanner();
		}
	}

	private void loadBanner() {
		// Create a new ad view.
		adView = new AdView(this);
		adView.setAdUnitId(BuildConfig.DEBUG ? getString(R.string.admob_debug) : getString(R.string.admob_id));
		adView.setAdSize(getAdSize());

		// Replace ad container with new ad view.
		ViewGroup adContainerView = findViewById(R.id.ad_view_container);
		adContainerView.removeAllViews();
		adContainerView.addView(adView);

		// Start loading the ad in the background.
		AdRequest adRequest = new AdRequest.Builder().build();
		adView.loadAd(adRequest);
	}


	private boolean isAdMob() {
		return PreferenceManager.getDefaultSharedPreferences(this).getBoolean(getString(R.string.prefs_admob2), true);
	}

	@SuppressLint("MissingPermission")
	protected void onStart() {
		super.onStart();

		final BottomAppBar appbar = ((BottomAppBar)findViewById(R.id.bottomAppBar));
		appbar.getMenu().findItem(R.id.menu_wifi_suggestions).setEnabled((Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q));
		appbar.getMenu().findItem(R.id.menu_wifi_reset).setEnabled((Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q));
		appbar.getMenu().findItem(R.id.menu_wifi_save).setVisible(isManualMode());
		appbar.getMenu().findItem(R.id.menu_privacy).setEnabled(isPrivacyOptionsRequired());

		if ((missingPermissions().isEmpty())) {
			// starting with Android 6, Location services has to be enabled to list all wifis
			if (isLocationServicesEnabled(this)) {
				doStart();
			} else {
				showLocationServicesDialog();
			}
		} else {
			showPermissionsError();
			requestMyPermissions(true);
		}
	}
	@Override
	public void onPause() {
		if (adView != null) {
			adView.pause();
		}
		super.onPause();
	}

	/** Called when returning to the activity */
	@Override
	public void onResume() {
		super.onResume();
		if (adView != null) {
			adView.resume();
		}
	}

	/**
	 * Request the runtime permissions that are required by the app.
	 *
	 * @param showExplanation show explanation only in beginning to avoid cycles
	 */
	void requestMyPermissions(boolean showExplanation) {
		final List<String> missingPermissions = missingPermissions();
		if (!missingPermissions.isEmpty()) {
			for (String permission : missingPermissions)
				shouldShowRequestPermissionRationale(permission); // just call to satisfy Android, we should the rational anyway
			if (showExplanation) {
				new MaterialAlertDialogBuilder(this)
						.setTitle(getString(R.string.app_name))
						.setPositiveButton("I got it", (dialog1, which) -> {
							requestPermissions((String[]) missingPermissions.toArray(new String[0]), REQUEST_CODE_PERMISSIONS);
						})
						.setMessage(Html.fromHtml(getString(R.string.message_requestpermission_rationale), Html.FROM_HTML_MODE_COMPACT))
						.show();
			} else {
				requestPermissions((String[]) missingPermissions.toArray(new String[0]), REQUEST_CODE_PERMISSIONS);
			}
		}
	}

	/**
	 * Check if we have all the necessary permissions.
	 * @return list of missing
	 */
	List<String> missingPermissions() {
		List<String> permissions = new ArrayList<>();
		permissions.add(ACCESS_FINE_LOCATION);
		permissions.add(ACCESS_WIFI_STATE);
		permissions.add(CHANGE_WIFI_STATE);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) // with Android 13: new permission
			permissions.add(POST_NOTIFICATIONS);
		List<String> missing = new ArrayList<>();
		for (String p : permissions) {
			if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED)
				missing.add(p);
		}
		return missing;
	}

	/**
	 * Shows the Information Dialog (first time on app start).
	 * <p>
	 *     Then follows:
	 *     <ol>
	 *         <li>Permission Dialog (for Wifi, Location services etc.)</li>
	 *         <li>Location Services (GPS or Network etc.)</li>
	 *         <li>Battery Optimizations (Android 12+)</li>
	 *         <li>Notifications (Android 13+)</li>
	 *     </ol>
	 * </p>
	 */
	protected void checkPermissionDialogs(boolean showExplanations) {
		final List<String> missingPermssions = missingPermissions();
		if (!missingPermssions.isEmpty())
			requestMyPermissions(showExplanations);

		// here we catch the flow that Location settings are not enabled (which blocks the check in onStart())
		if (missingPermssions.isEmpty() && !isLocationServicesEnabled(MainActivity.this)) {
			showLocationServicesDialog();
		}
		// for Android 12: we have to go for an exception from the "don't start foreground service from background"
		// so we ask the user to exempt the app from battery optimizations
		if (missingPermssions.isEmpty() && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)) {
			checkBatteryOptimizationsDisabled();
		}
		// Android 13: Notifications
		if (missingPermssions.isEmpty() && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)) {
			checkNotificationsEnalbed();
		}
	}

	/**
	 * Starting with Android 13 the user may disable notifications.
	 */
	private void checkNotificationsEnalbed() {
		if (checkForNotificationsEnabled && !getSystemService(NotificationManager.class).areNotificationsEnabled()) {
			new MaterialAlertDialogBuilder(this)
					.setTitle(getString(R.string.app_name))
					.setNegativeButton(R.string.text_cancel, new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							checkForNotificationsEnabled = false; // user dismissed
							dialog.cancel();
						}
					})
					.setPositiveButton(R.string.text_settings, new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							Intent settingsIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
							settingsIntent.setData(Uri.parse("package:" + getPackageName()));
							settingsIntent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
							startActivity(settingsIntent);
							dialog.dismiss();
						}
					})
					.setMessage(Html.fromHtml(getString(R.string.message_activate_notifications), Html.FROM_HTML_MODE_COMPACT))
					.show();
		}

	}

	protected boolean checkAndShowNotUsefulDialog() {
		final WifiManager wifiManager = (WifiManager) getSystemService(WIFI_SERVICE);
		final int is24Ghz = (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || wifiManager.is24GHzBandSupported()) ? 1 : 0;
		final int is50Ghz = wifiManager.is5GHzBandSupported() ? 1 : 0;
		final int is60Ghz = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && wifiManager.is6GHzBandSupported()) ? 1 : 0;
		if (!BuildConfig.DEBUG && (is24Ghz + is50Ghz + is60Ghz) < 2) {
			new MaterialAlertDialogBuilder(this)
					.setTitle(getString(R.string.app_name))
					.setNegativeButton(R.string.text_cancel, new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							dialog.cancel();
						}
					})
					.setPositiveButton(R.string.text_ok, new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) { // force deinstallation
							Intent intent = new Intent(ACTION_UNINSTALL_PACKAGE);
							intent.setData(Uri.parse("package:" + getPackageName()));
							intent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
							startActivity(intent);
							dialog.dismiss();
						}
					})
					.setMessage(Html.fromHtml(getString(R.string.message_nosupport), Html.FROM_HTML_MODE_COMPACT))
					.show();
			return true;
		}
		return false;
	}

	protected void showLocationServicesDialog() {
		new MaterialAlertDialogBuilder(this)
				.setTitle(getString(R.string.app_name))
				.setNegativeButton(R.string.text_cancel, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						dialog.cancel(); // accept das app is not running in full mode
					}
				})
				.setPositiveButton(R.string.text_settings, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						dialog.dismiss();
						// here we catch the flow that Location settings are not enabled (which blocks the check in onStart())
						if (!isLocationServicesEnabled(MainActivity.this)) {
							Intent settingsIntent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
							settingsIntent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
							startActivity(settingsIntent);
						}
					}
				})
				.setMessage(Html.fromHtml(getString(R.string.message_activate_locationservices), Html.FROM_HTML_MODE_COMPACT))
				.show();
	}

	@RequiresPermission(allOf = {ACCESS_WIFI_STATE, ACCESS_FINE_LOCATION})
	private void doStart() {
		// check prerequisites on every start and inform the user
		if (!isLocationServicesEnabled(this))
			showError(R.string.error_no_location_enabled);

		registerReceiver(scanFinishedListener, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
		LocalBroadcastManager.getInstance(this).registerReceiver(recommendationListener, new IntentFilter(INTENT_WIFICHANGETEXT));


		WifiManager wifiManager = getSystemService(WifiManager.class);
		try {
			boolean scanSuccessful = wifiManager.startScan(); // startScan() will be deprecated (from API 28)?
			if (!scanSuccessful)
				showError(R.string.error_scan_failed_wifi);
		} catch (SecurityException e) {
			Log.w(AppInfo.APP_NAME, "startScan failed", e);
		}
		findViewById(R.id.mainFragment).post(this::listNetworks);
		// and start service for the first time
		startWifiService(this);
	}

	public void onStop() {
		try {
			unregisterReceiver(scanFinishedListener); // may fail
			LocalBroadcastManager.getInstance(this).unregisterReceiver(recommendationListener); // may fail
		} catch (Exception e) {
			Log.w(AppInfo.APP_NAME, e);
		}
		super.onStop();
	}

	protected void onDestroy() {
		// do not call connManager.unregisterNetworkCallback(myNetworkCallback); as we want to continue receiving events
		if (adView != null) {
			adView.destroy();
		}
		super.onDestroy();
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (requestCode == REQUEST_CODE_PERMISSIONS && permissions.length >= 1) {
			for (int grant : grantResults) {
				if (grant != PackageManager.PERMISSION_GRANTED)
					return;
			}
			try {
				// ensure that in all the permission flows etc. the dialog is shown
				checkPermissionDialogs(false);
				doStart();
			} catch (SecurityException e) {
				Log.e(AppInfo.APP_NAME, "Error starting scan", e);
				showError(R.string.error_no_location_enabled);
			}
		}
	}

	@Override
	public boolean onOptionsItemSelected(@NonNull MenuItem item) {
		final int id = item.getItemId();
		if( id == R.id.menu_wifionoff) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // Inline Settings with Android 10+
				startActivity(new Intent(Settings.Panel.ACTION_WIFI));
			} else {
				startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
			}
			return true;
		} else if (id == R.id.menu_settings) {
			startActivity(new Intent(getApplicationContext(), SettingsActivity.class));
			return true;
		} else if (id == R.id.menu_about) {
			showAbout();
			return true;
		} else if (id == R.id.menu_wifi_suggestions) {
			showSuggestions();
			return true;
		} else if (id == R.id.menu_wifi_reset) {
			removeAllSuggestions();
			return true;
		} else if (id == R.id.menu_wifi_save) {
			saveSuggestions();
			return true;
		} else if (id == R.id.menu_info) {
			showContact();
			return true;
		} else if (id == R.id.menu_privacy) {
			UserMessagingPlatform.showPrivacyOptionsForm(
					this,
					formError -> {
						if (formError != null) {
							showError(getString(R.string.error_loading_privacy) + ":" + formError.getMessage());
						}
					}
			);
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void saveSuggestions() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			final List<WifiNetworkSuggestion> suggestionList = new ArrayList<>();
			for(AccessPointEntry entry : this.listNetworks) {
				if(entry.selected) {
					final WifiNetworkSuggestion.Builder builder=
						new WifiNetworkSuggestion.Builder().
								setBssid(MacAddress.fromString(entry.bssid)).
								setSsid(normalizeSSID(entry.name)).
								setPriority(1);
					suggestionList.add(builder.build());
					if(!normalizeSSID(entry.name).equals(entry.name)) {
						builder.setSsid(entry.name);
						suggestionList.add(builder.build()); // and add without normalized SSID
					}
				}
			}
			new MaterialAlertDialogBuilder(this)
					.setTitle(getString(R.string.text_save_suggestions))
					.setNegativeButton(R.string.text_cancel, new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							dialog.cancel();
						}
					})
					.setPositiveButton(R.string.text_ok, new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							dialog.dismiss();
							WifiChangeService.provideSuggestions(MainActivity.this, suggestionList);
							showMessage(R.string.text_suggestions_saved);
							// TODO try automatically
							/*
							ConnectivityManager connectivityManager = getSystemService(ConnectivityManager.class);
									NetworkSpecifier networkSpecifier  = new WifiNetworkSpecifier.Builder()
									.setSsid(suggestionList.get(0).getSsid())
									.build();
							NetworkRequest networkRequest  = new NetworkRequest.Builder()
									.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
									.setNetworkSpecifier(networkSpecifier)
									.build();
							connectivityManager.requestNetwork(networkRequest, );
							*/
							// Here we try to force a re-connect of the Wifi via Internet connectivity
							try {
								startActivity(WifiChangeService.getWifiIntent(getApplicationContext()));
							} catch(ActivityNotFoundException e) {
								Log.w(AppInfo.APP_NAME, e);  // TODO: try another setting
							}
						}
					})
					.setMessage(Html.fromHtml(getString(R.string.message_save_suggestions), Html.FROM_HTML_MODE_COMPACT))
					.show();
		} else {
			showError(R.string.error_save_suggestions);
		}

	}

	private void removeAllSuggestions() {
		new MaterialAlertDialogBuilder(this)
				.setTitle(getString(R.string.text_reset_suggestions))
				.setNegativeButton(R.string.text_cancel, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						dialog.cancel();
					}
				})
				.setPositiveButton(R.string.text_ok, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						dialog.dismiss();
						WifiChangeService.removeSuggestions(getApplicationContext());
						Snackbar.make(MainActivity.this.findViewById(R.id.mainFragment), R.string.message_remove_suggestions, Snackbar.LENGTH_LONG).show();
					}
				})
				.setMessage(Html.fromHtml(getString(R.string.message_reset_suggestions), Html.FROM_HTML_MODE_COMPACT))
				.show();
	}
	private void showSuggestions() {
		final WifiManager wifiManager = getSystemService(WifiManager.class);
		if(wifiManager != null) {
			List<WifiNetworkSuggestion> list = WifiChangeService.getActualSuggestions(wifiManager);
			StringBuilder builder = new StringBuilder();
			if( list != null && !list.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
				builder.append("<ol>");
				for(WifiNetworkSuggestion s : list) {
						builder.append("<li> Prio ").append(s.getPriority()).append(": ").append(s.getSsid()).append(" [").append(s.getBssid()).append("]").append("</li>");
				}
				builder.append("</ol>");
			} else {
				builder.append(getString(R.string.text_nowifirecommended));
			}
			new MaterialAlertDialogBuilder(this)
					.setTitle(getString(R.string.text_listsuggestions))
					.setPositiveButton(getString(R.string.text_continue), new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							dialog.cancel();
						}
					})
					.setMessage(Html.fromHtml(builder.toString(), Html.FROM_HTML_MODE_COMPACT)).show();
		}
	}

	public void onActivityResult(int requestCode, int resultCode, Intent resultIntent) {
		if (requestCode == REQUEST_CODE_LOCATION_SERVICES) {
			if (isLocationServicesEnabled(this)) {  // avoid endless loop if not acticated
				try {
					listNetworks();
				} catch (SecurityException e) {
					Log.e(AppInfo.APP_NAME, "Error starting scan", e);
					showError(R.string.error_no_location_enabled);
				}
			}
		} else
			super.onActivityResult(requestCode, resultCode, resultIntent);
	}

	/**
	 * Show a list of all detected network with provide both: 2.4 and 5 GHz.
	 */
	@RequiresPermission(allOf = {ACCESS_WIFI_STATE, ACCESS_FINE_LOCATION})
	void listNetworks() {
		WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
		List<ScanResult> scanResults = wifiManager.getScanResults();
		WifiInfo activeNetwork = wifiManager.getConnectionInfo();
		// set active and connected wifis
		final boolean activeWifi = (activeNetwork != null && wifiManager.isWifiEnabled() && activeNetwork.getSSID() != null);
		//((TextView) findViewById(R.id.actualwifitextview)).setText(activeWifi
		//		? (activeNetwork.getSSID() + " - " + activeNetwork.getBSSID() + " at " + activeNetwork.getFrequency() + " MHz") : getString(R.string.text_nowifi));
		// only with Android 11+ we get a list of all suggestions
		final List<WifiNetworkSuggestion> suggestionList = new ArrayList<>();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			try {
				suggestionList.addAll(wifiManager.getNetworkSuggestions());
			} catch (BadParcelableException e) { // on OnePlus...
				Log.e(AppInfo.APP_NAME, "Error on getNetworkSuggestions", e);
			}
		}
		// build up list with all SSID supporting 2.4 and 5 GHz
		// we also always add the connected network (even if only on one frequency) to display completeness to user
		listNetworks.clear();
		Map<String, NetworkEntry> map245Ghz = new HashMap<>();
		for (ScanResult result : scanResults) {
			if (result.SSID != null && result.SSID.length() > 0 && result.BSSID != null) {
				boolean connected = activeNetwork != null && normalizeSSID(result.SSID).equals(normalizeSSID(activeNetwork.getSSID()));
				NetworkEntry isThere = map245Ghz.get(result.SSID);
				if (isThere == null)
					map245Ghz.put(result.SSID, isThere = new NetworkEntry(result.SSID, connected));
				if (result.frequency >= 2000 && result.frequency <= 2999)
					isThere.is24ghz = true;
				if (result.frequency >= 5000 && result.frequency <= 5999)
					isThere.is5ghz = true;
				if (result.frequency >= 6000 && result.frequency <= 6999)
					isThere.is6ghz = true;
				final boolean suggested = isInSuggestedWifis(result, suggestionList);
				isThere.addAccessPoint(
						new AccessPointEntry(
								result.SSID, result.BSSID, result.frequency, result.level,
								connected && result.BSSID.equals(activeNetwork.getBSSID()),
								suggested));
			}
		}
		// filter out all networks that are qualified for switch (ALL if in manual mode)
		final boolean manualMode = isManualMode();
		for (NetworkEntry entry : map245Ghz.values()) {
			if ((entry.is24ghz && (entry.is5ghz || entry.is6ghz))
					|| entry.connected || entry.isSuggested() || manualMode) {
				listNetworks.addAll(entry.accessPoints);
			}
		}
		// for testing on Emulator
		if(BuildConfig.DEBUG) {
			for(int i =1;i<=20;i++) {
				listNetworks.add(new AccessPointEntry("SSID "+i, "BSSID"+i, 999, 50, false, false));
			}
		}

		final RecyclerView listView = findViewById(R.id.listview);
		listView.setAdapter(new CustomWifiListAdapter(listNetworks, wifiManager, isManualMode()));
		listView.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
		listView.addItemDecoration(new MaterialDividerItemDecoration(this, MaterialDividerItemDecoration.VERTICAL));
		listView.setHasFixedSize(true);
		if (!listNetworks.isEmpty()) {
			((MaterialCardView)findViewById(R.id.nowificardview)).setCardBackgroundColor(getResources().getColor(android.R.color.holo_green_dark, getTheme()));
			final TextView noWifiTextview = findViewById(R.id.nowifitextview);
			if(isManualMode()) {
				noWifiTextview.setText(R.string.text_wifimanualmode);
			} else {
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
					noWifiTextview.setText(R.string.error_not_android10);
				} else {
					noWifiTextview.setText(R.string.text_wififound);
				}
			}
		} else {
			// we did not get a result: probably permission missing?
			if (checkSelfPermission(ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
				showPermissionsError();
			} else { // otherwise: we found networks, but not the appropriate (2.4/5)
				((MaterialCardView)findViewById(R.id.nowificardview)).setCardBackgroundColor(getResources().getColor(R.color.design_default_color_primary_dark, getTheme()));
				((TextView) findViewById(R.id.nowifitextview)).setText(R.string.text_nowififound);
			}
		}
	}

	private boolean isInSuggestedWifis(@NonNull ScanResult result, @NonNull List<WifiNetworkSuggestion> suggestionList) {
		final String ssid = normalizeSSID(result.SSID);
		for (WifiNetworkSuggestion suggestion : suggestionList) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
				if (ssid.equals(normalizeSSID(suggestion.getSsid())) && result.BSSID.equals(String.valueOf(suggestion.getBssid()))) // TODO: stimmt das Format immer ueberein?
					return true;
			}
		}
		return false;
	}

	void showMessage(@StringRes int id) {
		Snackbar.make(MainActivity.this.findViewById(R.id.mainFragment), id, Snackbar.LENGTH_LONG).show(); // otherwise use Toast
	}

	void showMessage(String msg) {
		Snackbar.make(MainActivity.this.findViewById(R.id.mainFragment), msg, Snackbar.LENGTH_LONG).show(); // otherwise use Toast
	}

	/**
	 * Show recommendations, this actually works due to API restrictions only on latest versions
	 * <p>On OnePlus/Realme we get a strange BadParcelableException/ClassNotFoundException from WifiNetworkSuggestion$1.createFromParcel (com.android.server.wifi.OplusWifiConfiguration)
	 * I cannot determinate a real reason behind it, maybe Chinese changes to the Android standard frameworks?</p>
	 *
	 * @param suggestionList
	 * @param activeWifi
	 * @deprecated remove soon
	 */
	private void showCurrentSuggestions(List<WifiNetworkSuggestion> suggestionList, boolean activeWifi) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			//final TextView recommendationView = ((TextView) findViewById(R.id.recommandedwifitextview));
			try {
				if (!suggestionList.isEmpty()) {
					StringBuilder builder = new StringBuilder();
					for (WifiNetworkSuggestion suggestion : suggestionList) {
						builder.append(suggestion.getSsid()).append(" - ").append(suggestion.getBssid()).append(" recommended at prio ").append(suggestion.getPriority()).append("<br/>");
					}
					builder.delete(builder.length() - 5, builder.length()); // br am Ende entfernen
					showMessage(builder.toString());
					//recommendationView.setText(Html.fromHtml(builder.toString(), Html.FROM_HTML_MODE_COMPACT));
				} else {
					// omit the message "text_nowifirecommended" as this would result into a loop
				}
			} catch (BadParcelableException e) { // on OnePlus...
				Log.e(AppInfo.APP_NAME, "Error on getNetworkSuggestions", e);
				showError(R.string.error_cannot_display_recommended_wifi);
			}
		}
	}
	public static boolean isLocationServicesEnabled(@NonNull Context context) {
		LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
		return LocationManagerCompat.isLocationEnabled(locationManager);
	}

	/**
	 * Show the permission error in the card on top of the main screen.
	 */
	private void showPermissionsError() {
		((MaterialCardView)findViewById(R.id.nowificardview)).setCardBackgroundColor(getResources().getColor(android.R.color.holo_red_dark, getTheme()));
		((TextView) findViewById(R.id.nowifitextview)).setText(R.string.error_no_permissions_provived);
	}

	private void showAbout() {
		final AlertDialog dialog = new MaterialAlertDialogBuilder(this)
				.setTitle(getString(R.string.app_name))
				.setNeutralButton("Continue", new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						dialog.cancel();
					}
				})
				.setMessage(Html.fromHtml(getString(R.string.message_welcome), Html.FROM_HTML_MODE_COMPACT)).create();
		TextView view = (TextView) dialog.findViewById(android.R.id.message);
		if (view != null)
			view.setMovementMethod(LinkMovementMethod.getInstance()); // make links clickable
		dialog.show();
	}

	private void showContact() {
		final MaterialAlertDialogBuilder dialogBuilder = new MaterialAlertDialogBuilder(this)
				.setTitle(getString(R.string.app_name))
				.setNeutralButton("Continue", new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						dialog.cancel();
					}
				})
				.setMessage(Html.fromHtml(BuildConfig.IMPRESSUM, Html.FROM_HTML_MODE_COMPACT)); // Impressum is taken from local.properties
		final AlertDialog dialog = dialogBuilder.create();
		TextView view = (TextView) dialog.findViewById(android.R.id.message);
		if (view != null)
			view.setMovementMethod(LinkMovementMethod.getInstance()); // make links clickable
		dialog.show();
	}

	@RequiresApi(api = Build.VERSION_CODES.P)
	public void checkBatteryOptimizationsDisabled() {
		PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
		ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
		final boolean batteryOptimizationIgnored = pm.isIgnoringBatteryOptimizations(getPackageName());
		final boolean backgroundRestricted = am.isBackgroundRestricted();
		boolean checkForBatteryOptimization = PreferenceManager.getDefaultSharedPreferences(this).
				getBoolean(getString(R.string.prefs_check_battery_optims), true);
		if (checkForBatteryOptimization && (!batteryOptimizationIgnored || backgroundRestricted)) {
			new MaterialAlertDialogBuilder(this)
					.setTitle(getString(R.string.app_name))
					.setPositiveButton(R.string.action_ignore, new DialogInterface.OnClickListener() {
								@Override
								public void onClick(DialogInterface dialog, int which) { // user does not want
									PreferenceManager.getDefaultSharedPreferences(MainActivity.this).edit().
											putBoolean(getString(R.string.prefs_check_battery_optims), false).apply();
									dialog.cancel();
								}
							}
					)
					.setNeutralButton(R.string.action_settings, new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							Intent intent = new Intent();
							if (!batteryOptimizationIgnored) {
								// kein REQUEST... nutzen, weil lt. Google Policy verboten
								intent.setAction(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
								intent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
							} else { // Background Restriction -> Open App settings
								intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
								intent.setPackage(MainActivity.this.getPackageName());
								intent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
							}
							startActivity(intent);
							dialog.dismiss();
						}
					})
					.setMessage(Html.fromHtml(getString(R.string.message_batteryoptimizations), Html.FROM_HTML_MODE_COMPACT))
					.show();
		}
	}

	void showError(@StringRes int stringId) {
		Toast.makeText(this, stringId, Toast.LENGTH_LONG).show();
	}

	void showError(String string) {
		Toast.makeText(this, string, Toast.LENGTH_LONG).show();
	}

	protected boolean isPrivacyOptionsRequired() {
		return consentInformation != null && consentInformation.getPrivacyOptionsRequirementStatus()
				== ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED;
	}

	static void createNotificationChannel(Context context) {
		CharSequence name = context.getString(R.string.app_name);
		String description = context.getString(R.string.app_description);
		int importance = NotificationManager.IMPORTANCE_DEFAULT;
		NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
		channel.setDescription(description);
		NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
		notificationManager.createNotificationChannel(channel);
	}

	/**
	 * Runs the {@link WifiChangeService} as a foreground task starting with Android 9.
	 *
	 * @param  context the context
	 */
	static void startWifiService(Context context) {
		Log.i(AppInfo.APP_NAME, "startWifiService");
		if (PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context.getString(R.string.prefs_activation), true)) {
			final Intent intent = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ?
					new Intent(context.getApplicationContext(), WifiChangeService14.class) : new Intent(context.getApplicationContext(), WifiChangeService.class);
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
				// Android 14 forbids all foreground Work Requests - so we try to start the service and hope that our activity is still in foregroud...
				try {
					context.startService(new Intent(context.getApplicationContext(), WifiChangeService14.class));
				} catch (SecurityException | IllegalStateException e) {
					Log.w(AppInfo.APP_NAME, "Error starting WifiChangeService on Android14+");
					// we send a notification
					NotificationManagerCompat.from(context).notify(ONGOING_NOTIFICATION_ID,
							WifiChangeService.createMessageNotification(context, R.string.message_forcewifi14_activated));
				}
			} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
				// With Android 12 we cannot start a Foreground service anymore, so we have to use a WorkManager
				Constraints constraints = new Constraints.Builder() // requires INTERNET permission
						.setRequiredNetworkType(NetworkType.CONNECTED).build();
				WorkRequest wifiWorkRequest =
						new OneTimeWorkRequest.Builder(WifiChangeWorker.class).
								setConstraints(constraints).
								setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).
								addTag(AppInfo.APP_NAME).
								build();
				WorkManager.getInstance(context).enqueue(wifiWorkRequest);
				Log.i(AppInfo.APP_NAME, "Scheduled WorkManager for Android 12+");
			}
			else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				Log.i(AppInfo.APP_NAME, "Start Service for Android 9+");
				try {
					context.getApplicationContext().bindService(intent, new WifiChangeService.WifiServiceConnection(context.getApplicationContext()),
							Context.BIND_AUTO_CREATE);
					context.getApplicationContext().startForegroundService(intent);
					// Due to necessary workaround: Replacement for
					// context.startForegroundService(new Intent(context.getApplicationContext(), WifiChangeService.class));
				} catch (RuntimeException e) {
					// If call comes from a BroadcastReceiver (see Javadoc of Bindservice), e.g StartOnBootReiver: try again without bind()
					context.getApplicationContext().startForegroundService(intent);
				}
			} else {
				context.startService(intent); // on Android 8 and below
			}
		}
	}

	static void stopWifiService(Context context) {
		Log.i(AppInfo.APP_NAME, "stopWifiService");
		if(!PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context.getString(R.string.prefs_activation), true)) {
			final Intent intent = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ?
					new Intent(context.getApplicationContext(), WifiChangeService14.class) : new Intent(context.getApplicationContext(), WifiChangeService.class);
			context.getApplicationContext().stopService(intent);
			WorkManager.getInstance(context).cancelAllWorkByTag(AppInfo.APP_NAME);
		}
	}

	/**
	 * Check if manual network suggestion mode is a) enabled and b) supported by Android version
	 * @return true if manual mode
	 */
	private boolean isManualMode() {
		return !PreferenceManager.getDefaultSharedPreferences(this).getBoolean(getString(R.string.prefs_activation), false)
				&& (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q);
	}

	private AdSize getAdSize() {
		// Determine the screen width (less decorations) to use for the ad width.
		Display display = getWindowManager().getDefaultDisplay();
		DisplayMetrics outMetrics = new DisplayMetrics();
		display.getMetrics(outMetrics);

		float density = outMetrics.density;

		float adWidthPixels = adView.getWidth();

		// If the ad hasn't been laid out, default to the full screen width.
		if (adWidthPixels == 0) {
			adWidthPixels = outMetrics.widthPixels;
		}

		int adWidth = (int) (adWidthPixels / density);
		return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this, adWidth);
	}
}
