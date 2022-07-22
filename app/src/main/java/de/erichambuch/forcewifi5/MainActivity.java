package de.erichambuch.forcewifi5;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.ACCESS_WIFI_STATE;
import static android.Manifest.permission.CHANGE_NETWORK_STATE;
import static android.Manifest.permission.CHANGE_WIFI_STATE;
import static android.text.Html.FROM_HTML_MODE_LEGACY;
import static de.erichambuch.forcewifi5.WifiUtils.normalizeSSID;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.annotation.RequiresPermission;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkManager;
import androidx.work.WorkRequest;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Main activity für die App.
 */
public class MainActivity extends AppCompatActivity {

	public static final String CHANNEL_ID = "ForceWifi5";

	public static final String INTENT_WIFICHANGETEXT = "de.erichambuch.forcewifi5.WIFICHANGETEXT";

	public static final String EXTRA_WIFICHANGETEXT = "de.erichambuch.forcewifi5.recommendation";

	private static final int REQUEST_CODE_LOCATION_SERVICES = 4567;
	private static final int REQUEST_CODE_PERMISSIONS = 5678;


	private boolean infoDialogShown = false;

	/**
	 * Circuit breaker - shared by different listeners.
	 */
	protected static volatile long lastNetworkCallback = 0;

	/**
	 * Broadcast receiver for WifiManager.NETWORK_STATE_CHANGED_ACTION.
	 * <p>This works due to Android background restrictions only up to Android 9. Above we won't receive any events.</p>
	 */
	static class NetworkStateChangedReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			Log.d(AppInfo.APP_NAME, "NetworkStateChangedReceiver called");
			final long lastTimestamp = System.currentTimeMillis();
			if ( lastTimestamp > lastNetworkCallback+ (30*1000)) { // Circuit breaker
				lastNetworkCallback = lastTimestamp;
				final NetworkInfo networkInfo = (NetworkInfo) intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
				if(networkInfo == null || (networkInfo.isConnectedOrConnecting() && networkInfo.getType() == ConnectivityManager.TYPE_WIFI) )
					startWifiService(context.getApplicationContext());
			} else
				Log.d(AppInfo.APP_NAME, "Skipped NetworkCallBack");

			updateWidget(context.getApplicationContext());
		}

		private void updateWidget(Context context) {
			// and update widget if any
			final AppWidgetManager appWidgetManager = (AppWidgetManager) context.getSystemService(APPWIDGET_SERVICE);
			final int[] widgetIds = appWidgetManager.getAppWidgetIds(new ComponentName(context, ForceWifiAppWidget.class.getName()));
			if(widgetIds.length > 0) {
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
		public void onAvailable(Network network) {
			Log.d(AppInfo.APP_NAME, "Networkcallback onAvailable");
			// small circuit breaker: if we are called twice within 60 seconds  - then ignore the call
			// so we break up an endless loop of connection failures
			final long lastTimestamp = System.currentTimeMillis();
			if ( lastTimestamp > lastNetworkCallback+ (60*1000)) {
				lastNetworkCallback = lastTimestamp;
				startWifiService(myContext);
			} else
				Log.d(AppInfo.APP_NAME, "Skipped NetworkCallBack");

			updateWidget();
		}

		@Override
		public void onLost(Network network) {
			Log.d(AppInfo.APP_NAME, "Networkcallback onLost");
			updateWidget();
		}

		@Override
		public void onLinkPropertiesChanged(android.net.Network network,
														android.net.LinkProperties linkProperties) {
			Log.d(AppInfo.APP_NAME, "Networkcallback onLinkPropertiesChanged");
			updateWidget();
		}

		private void updateWidget() {
			// and update widget if any
			final AppWidgetManager appWidgetManager = (AppWidgetManager) myContext.getSystemService(APPWIDGET_SERVICE);
			final int[] widgetIds = appWidgetManager.getAppWidgetIds(new ComponentName(myContext, ForceWifiAppWidget.class.getName()));
			if(widgetIds.length > 0) {
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
	}

	public static class AccessPointEntry {
		final String bssid;
		final int frequency;
		final int signalLevel;
		final boolean connected;

		AccessPointEntry(String bssid, int freq, int level, boolean connected) {
			this.bssid = bssid;
			this.frequency = freq;
			this.signalLevel = level;
			this.connected = connected;
		}

		public @NonNull
		String toString() {
			return this.bssid;
		}
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
					final TextView view = ((TextView) findViewById(R.id.recommandedwifitextview));
					if (view != null)
						view.setText(Html.fromHtml(recommendation, Html.FROM_HTML_MODE_COMPACT));
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
				Log.d(AppInfo.APP_NAME, "ScanFinished: "+success);
				if (success) {
					// We could start the WifiSevice here again, but it's already started when a network is available (NetworkCallback)
					// therefore we do not call startWifiService(context); anymore
					try {
						if(!MainActivity.this.isFinishing())
							MainActivity.this.listNetworks();
						Toast.makeText(context, R.string.error_scan_succesful, Toast.LENGTH_LONG).show();
					} catch (SecurityException e) {
						Log.e(AppInfo.APP_NAME, "Error listing networks", e);
					}
					// and update widget if any
					final AppWidgetManager appWidgetManager = (AppWidgetManager) context.getSystemService(APPWIDGET_SERVICE);
					final int[] widgetIds = appWidgetManager.getAppWidgetIds(new ComponentName(context, ForceWifiAppWidget.class.getName()));
					if(widgetIds.length > 0) {
						final Intent widgetIntent = new Intent(context.getApplicationContext(), ForceWifiAppWidget.class);
						widgetIntent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
						context.sendBroadcast(widgetIntent);
					}
				} else
					Toast.makeText(context, R.string.error_scan_failed, Toast.LENGTH_LONG).show();
			}
		}
	}

	public static class AddWidgetActivity extends Activity {
		protected void onStart() {
			super.onStart();
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				AppWidgetManager mAppWidgetManager = getSystemService(AppWidgetManager.class);
				ComponentName myProvider = new ComponentName(AddWidgetActivity.this, ForceWifiAppWidget.class);
				if (mAppWidgetManager.isRequestPinAppWidgetSupported()) {
					mAppWidgetManager.requestPinAppWidget(myProvider, new Bundle(), null);
				} else
					Toast.makeText(this, R.string.error_not_supported, Toast.LENGTH_LONG).show();
			} else
				Toast.makeText(this, R.string.error_not_supported, Toast.LENGTH_LONG).show();
		}
	}

	private final ScanFinishedListener scanFinishedListener = new ScanFinishedListener();
	private final RecommendationListener recommendationListener = new RecommendationListener();

	@SuppressLint("MissingPermission")
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.activity_main);

		findViewById(R.id.floatingActionButton).setOnClickListener(v -> startActivity(new Intent(getApplicationContext(), SettingsActivity.class)));
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // Inline Settings with Android 10+
			findViewById(R.id.floatingWifiToggleButton).setOnClickListener(v -> startActivity(new Intent(Settings.Panel.ACTION_WIFI)));
		} else {
			findViewById(R.id.floatingWifiToggleButton).setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));
		}
		final SwipeRefreshLayout swipeRefreshLayout = ((SwipeRefreshLayout) findViewById(R.id.swiperefresh));
		swipeRefreshLayout.setOnRefreshListener(
				() -> {
					WifiManager wifiManager = getSystemService(WifiManager.class);
					if(wifiManager != null )
						wifiManager.startScan(); // listNetworks is called by ScanFinishedListener
					swipeRefreshLayout.setRefreshing(false);
				}
		);

		createNotificationChannel(this);

		// register a listener to network changes (this may occure twice if already done in StartOnBootReceiver!)
		ConnectivityManager connManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
		connManager.registerNetworkCallback(
				new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
				new NetworkCallback(getApplicationContext()));

		// Get Permissions right away from the start
		if ((checkSelfPermission(ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) &&
				(checkSelfPermission(ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED) &&
				(checkSelfPermission(CHANGE_NETWORK_STATE) == PackageManager.PERMISSION_GRANTED)) {
			if(Intent.ACTION_MAIN.equals(getIntent().getAction())) // show info dialog only on first start
				showInfoDialog();
			else
				infoDialogShown = true;
		} else {
			requestMyPermissions();
		}
	}

	protected void onStart() {
		super.onStart();

		// Start from Widget: do not show info dialog
		if(Intent.ACTION_VIEW.equals(getIntent().getAction()))
			infoDialogShown = true;

		if ((checkSelfPermission(ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) &&
				(checkSelfPermission(ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED) &&
				(checkSelfPermission(CHANGE_NETWORK_STATE) == PackageManager.PERMISSION_GRANTED)) {
			// starting with Android 6, Location services has to be enabled to list all wifis
			if (isLocationServicesEnabled(this)) {
				doStart();
			} else {
				showError(R.string.error_no_location_enabled);
				Intent settingsIntent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
				startActivity(settingsIntent);
			}
		} else {
			showPermissionsError();
			requestMyPermissions();
		}
	}

	/**
	 * Request the runtime permissions that are required by the app.
	 */
	private void requestMyPermissions() {
		// TODO: stimmt im flow noch nicht.
		if((checkSelfPermission(ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) ||
				shouldShowRequestPermissionRationale(ACCESS_FINE_LOCATION)) {
			AlertDialog dialog = new MaterialAlertDialogBuilder(this)
					.setTitle(getString(R.string.app_name))
					.setPositiveButton("I got it", (dialog1, which) -> {
						dialog1.cancel();
						requestPermissions(new String[]{
								ACCESS_FINE_LOCATION,
								ACCESS_WIFI_STATE,
								CHANGE_WIFI_STATE
						}, REQUEST_CODE_PERMISSIONS);
					})
					.setMessage(Html.fromHtml(getString(R.string.message_requestpermission_rationale), Html.FROM_HTML_MODE_COMPACT))
					.show();
		} else {
			requestPermissions(new String[]{
					ACCESS_FINE_LOCATION,
					ACCESS_WIFI_STATE,
					CHANGE_WIFI_STATE
			}, REQUEST_CODE_PERMISSIONS);
		}
	}

	private void showInfoDialog() {
		AlertDialog dialog = new MaterialAlertDialogBuilder(this)
				.setTitle(getString(R.string.app_name))
				.setPositiveButton("Continue", new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						dialog.cancel();
						// here we catch the flow that Location settings are not enabled (which blocks the check in onStart())
						if(!isLocationServicesEnabled(MainActivity.this)) {
							showError(R.string.error_no_location_enabled);
							Intent settingsIntent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
							startActivity(settingsIntent);
						}
						// for Android 12: we have to go for an exception from the "don't start foreground service from background"
						// so we ask the user to exempt the app from battery optimizations
						else if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)) {
							checkBatteryOptimizationsDisabled();
						}
					}
				})
				.setMessage(Html.fromHtml(getString(R.string.message_welcome), Html.FROM_HTML_MODE_COMPACT))
				.show();
		infoDialogShown = true;
		TextView view = (TextView) dialog.findViewById(android.R.id.message);
		if (view != null)
			view.setMovementMethod(LinkMovementMethod.getInstance()); // make links clickable
	}

	@RequiresPermission(allOf = {ACCESS_WIFI_STATE, ACCESS_FINE_LOCATION})
	private void doStart() {
		// check prerequisites on every start and inform the user
		if(!isLocationServicesEnabled(this))
			showError(R.string.error_no_location_enabled);

		registerReceiver(scanFinishedListener, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
		LocalBroadcastManager.getInstance(this).registerReceiver(recommendationListener, new IntentFilter(INTENT_WIFICHANGETEXT));

		WifiManager wifiManager = getSystemService(WifiManager.class);
		try {
			boolean scanSuccessful = wifiManager.startScan(); // startScan() will be deprecated (from API 28)?
			if(!scanSuccessful)
				showError(R.string.error_scan_failed);
		} catch(SecurityException e) {
			Log.w(AppInfo.APP_NAME, "startScan failed", e);
		}
		findViewById(R.id.mainFragment).post(() -> listNetworks());
		// and start service for the first time
		startWifiService(this);
	}

	public void onStop() {
		try {
			unregisterReceiver(scanFinishedListener); // may fail
			LocalBroadcastManager.getInstance(this).unregisterReceiver(recommendationListener); // may fail
		} catch(Exception e) {
			Log.w(AppInfo.APP_NAME, e);
		}
		super.onStop();
	}
	
	protected void onDestroy() {
		// do not call connManager.unregisterNetworkCallback(myNetworkCallback); as we want to continue receiving events
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
				if(!infoDialogShown)
					showInfoDialog();
				doStart();
			} catch (SecurityException e) {
				Log.e(AppInfo.APP_NAME, "Error starting scan", e);
				showError(R.string.error_no_location_enabled);
			}
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.menu_main, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int id = item.getItemId();
		if (id == R.id.action_settings) {
			startActivity(new Intent(getApplicationContext(), SettingsActivity.class));
			return true;
		}
		if (id == R.id.action_info) {
			Intent intent = new Intent(Intent.ACTION_VIEW);
			intent.setData(Uri.parse(getString(R.string.app_url)));
			startActivity(intent);
			return true;
		}
		return super.onOptionsItemSelected(item);
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
		}
		else
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
		((TextView)findViewById(R.id.actualwifitextview)).setText(activeWifi
				? (activeNetwork.getSSID()+" - "+activeNetwork.getBSSID()+" at "+activeNetwork.getFrequency()+" GHz") : getString(R.string.text_nowifi));
		// build up list with all SSID supporting 2.4 and 5 GHz
		List<NetworkEntry> listNetworks = new ArrayList<>();
		Map<String, NetworkEntry> map245Ghz = new HashMap<>();
		for(ScanResult result : scanResults) {
			if (result.SSID != null && result.SSID.length() > 0 && result.BSSID != null) {
				boolean connected = activeNetwork != null && normalizeSSID(result.SSID).equals(normalizeSSID(activeNetwork.getSSID()));
				NetworkEntry isThere = map245Ghz.get(result.SSID);
				if ( isThere == null ) 
					map245Ghz.put(result.SSID, isThere = new NetworkEntry(result.SSID, connected));
				if ( result.frequency >= 2000 && result.frequency <= 2999 )
					isThere.is24ghz = true;
				if ( result.frequency >= 5000 && result.frequency <= 5999 )
					isThere.is5ghz = true;
				if ( result.frequency >= 6000 && result.frequency <= 6999 )
					isThere.is6ghz = true;
				isThere.addAccessPoint(new AccessPointEntry(result.BSSID, result.frequency, result.level,
						connected && result.BSSID.equals(activeNetwork.getBSSID())));
			}
		}
		ListView view = (ListView) findViewById(R.id.listview);
		for(NetworkEntry entry : map245Ghz.values()) {
			if(entry.is24ghz && (entry.is5ghz || entry.is6ghz))
				listNetworks.add(entry);
		}
		if (listNetworks.size() > 0) {
			view.setAdapter(new ArrayAdapter<>(this, R.layout.list_view_entry, listNetworks));
			findViewById(R.id.nowificardview).setBackgroundColor(getResources().getColor(android.R.color.holo_green_dark, getTheme()));
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				((TextView)findViewById(R.id.nowifitextview)).setText(R.string.error_not_android10);
			} else {
				((TextView) findViewById(R.id.nowifitextview)).setText(R.string.text_wififound);
			}
		} else {
			// we did not get a result: probably permission missing?
			if(checkSelfPermission(ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
				showPermissionsError();
			}
			else { // otherwise: we found networks, but not the appropriate (2.4/5)
				view.setAdapter(new ArrayAdapter<>(this, R.layout.list_view_entry, new ArrayList<>(map245Ghz.values())));
				findViewById(R.id.nowificardview).setBackgroundColor(getResources().getColor(R.color.colorPrimaryDark, getTheme()));
				((TextView) findViewById(R.id.nowifitextview)).setText(R.string.text_nowififound);
			}
		}

		// Show recommendations, this actually works due to API restrictions only on latest versions
		// On OnePlus/Realme we get a strange BadParcelableException/ClassNotFoundException from WifiNetworkSuggestion$1.createFromParcel
		// I cannot determinate a real reason behind it, maybe Chinese changes to the Android standard frameworks?
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
			List<WifiNetworkSuggestion> suggestionList = wifiManager.getNetworkSuggestions();
			final TextView recommendationView = ((TextView) findViewById(R.id.recommandedwifitextview));
			if(suggestionList != null && suggestionList.size()> 0) {
				StringBuilder builder = new StringBuilder();
				for(WifiNetworkSuggestion suggestion : suggestionList) {
					builder.append(suggestion.getSsid()).append(" - ").append(suggestion.getBssid()).append(" recommended at prio ").append(suggestion.getPriority()).append("<br/>");
				}
				builder.delete(builder.length() - 5, builder.length()); // br am Ende entfernen
				if (recommendationView != null)
					recommendationView.setText(Html.fromHtml(builder.toString(), Html.FROM_HTML_MODE_COMPACT));
			} else {
				recommendationView.setText(R.string.text_nowifirecommended);
			}
		}

		// color Card if frequency is correct, so feedback to user
		View recommendationView = findViewById(R.id.recommandedwificard);
		if (activeWifi) {
			recommendationView.setBackgroundColor(
					isWantedFrequency(activeNetwork.getFrequency()) ? getResources().getColor(android.R.color.holo_green_dark, getTheme()) :
							getResources().getColor(android.R.color.holo_orange_dark, getTheme()));
		} else {
			recommendationView.setBackgroundColor(getResources().getColor(android.R.color.white, getTheme()));
		}
	}

	public static boolean isLocationServicesEnabled(Context context) {
		// TODO use androidx.appcompat:appcompat:1.2.0
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			// This is new method provided in API 28
			LocationManager lm = (LocationManager) context.getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
			return lm != null && lm.isLocationEnabled();
		} else {
			// This is Deprecated in API 28
			int mode = Settings.Secure.getInt(context.getApplicationContext().getContentResolver(), Settings.Secure.LOCATION_MODE,
					Settings.Secure.LOCATION_MODE_OFF);
			return  (mode != Settings.Secure.LOCATION_MODE_OFF);
		}
	}

	/**
	 * Show the permission error in the card on top of the main screen.
	 */
	private void showPermissionsError() {
		findViewById(R.id.nowificardview).setBackgroundColor(getResources().getColor(android.R.color.holo_red_dark, getTheme()));
		((TextView)findViewById(R.id.nowifitextview)).setText(R.string.error_no_permissions_provived);
	}

	@RequiresApi(api = Build.VERSION_CODES.P)
	public void checkBatteryOptimizationsDisabled() {
		PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
		ActivityManager am = (ActivityManager)getSystemService(ACTIVITY_SERVICE);
		final boolean batteryOptimizationIgnored = pm.isIgnoringBatteryOptimizations(getPackageName());
		final boolean backgroundRestricted = am.isBackgroundRestricted();
		if(!batteryOptimizationIgnored || backgroundRestricted) {
			new MaterialAlertDialogBuilder(this)
					.setTitle(getString(R.string.app_name))
					.setNegativeButton(R.string.action_ignore, new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							dialog.cancel();}
						}
						)
					.setPositiveButton(R.string.action_settings, new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							dialog.cancel();
							Intent intent = new Intent();
							if(!batteryOptimizationIgnored) {
								// kein REQUEST... nutzen, weil lt. Google Policy verboten
								intent.setAction(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
							} else { // Background Restriction -> Open App settings
								intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
								intent.setPackage(MainActivity.this.getPackageName());
							}
							startActivity(intent);
						}
					})
					.setMessage(Html.fromHtml(getString(R.string.message_batteryoptimizations), Html.FROM_HTML_MODE_COMPACT))
					.show();
		}
	}

	private void showError(int stringId) {
		Toast.makeText(this, stringId, Toast.LENGTH_LONG).show();
	}

	static void createNotificationChannel(Context context) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			CharSequence name = context.getString(R.string.app_name);
			String description = context.getString(R.string.app_description);
			int importance = NotificationManager.IMPORTANCE_LOW;
			NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
			channel.setDescription(description);
			NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
			notificationManager.createNotificationChannel(channel);
		}
	}

	/**
	 * Runs the {@link WifiChangeService} as a foreground task starting with Android 9.
	 *
	 * @param  context the context
	 */
	static void startWifiService(Context context) {
		Log.i(AppInfo.APP_NAME, "startWifiService");
		if(PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context.getString(R.string.prefs_activation), true)) {
			final Intent intent = new Intent(context.getApplicationContext(), WifiChangeService.class);
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
				// With Android 12 we cannot start a Foreground service anymore, so we have to use a WorkManager
				Constraints constraints = new Constraints.Builder()
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
			final Intent intent = new Intent(context.getApplicationContext(), WifiChangeService.class);
			context.getApplicationContext().stopService(intent);
			WorkManager.getInstance(context).cancelAllWorkByTag(AppInfo.APP_NAME);
		}
	}



	// TODO: duplicated code!!!
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
}
