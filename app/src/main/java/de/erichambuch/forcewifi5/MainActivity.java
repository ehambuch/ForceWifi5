package de.erichambuch.forcewifi5;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.ACCESS_WIFI_STATE;
import static android.Manifest.permission.CHANGE_NETWORK_STATE;
import static android.Manifest.permission.CHANGE_WIFI_STATE;
import static android.text.Html.FROM_HTML_MODE_LEGACY;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresPermission;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

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
	private static final int REQUEST_CODE_LOCATION_SERVICES = 4567;
	private static final int REQUEST_CODE_PERMISSIONS = 5678;

	private boolean infoDialogShown = false;


	/**
	 * Network callback: We use this on enabling of a network to initiate a change of the network if required.
	 */
	private class NetworkCallback extends ConnectivityManager.NetworkCallback {
		private volatile long lastNetworkCallback = 0;
		private volatile String lastNetwork = "unknownNetxyz";

		@Override
		public void onAvailable(Network network) {
			// small circuit breaker: if we are called twice within 60 seconds  - then ignore the call
			// so we break up an endless loop of connection failures
			final long lastTimestamp = System.currentTimeMillis();
			if ( lastTimestamp > lastNetworkCallback+ (60*1000) ) {
				lastNetworkCallback = lastTimestamp;
				lastNetwork = network.toString();
				startWifiService(MainActivity.this);
			} else
				Log.d(AppInfo.APP_NAME, "Skipped NetworkCallBack");
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
	 * Listener sobald Wifi Scan abgeschlossen. Dann wird die Liste aller Wifis angezeigt.
	 */
	public class ScanFinishedListener extends BroadcastReceiver {

		@Override
		public void onReceive(Context context, Intent intent) {
			if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction())) {
				boolean success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
				if (success) {
					// We could start the WifiSevice here again, but it's already started when a network is available (NetworkCallback)
					// therefore we do not call startWifiService(context); anymore
					try {
						MainActivity.this.listNetworks();
					} catch (SecurityException e) {
						Log.e(AppInfo.APP_NAME, "Error listing networks", e);
					}
				} else
					Toast.makeText(context, R.string.error_scan_failed, Toast.LENGTH_LONG).show();
			}
		}
	}

	private final NetworkCallback myNetworkCallback = new NetworkCallback();
	private final ScanFinishedListener scanFinishedListener = new ScanFinishedListener();

	@SuppressLint("MissingPermission")
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.activity_main);

		findViewById(R.id.floatingActionButton).setOnClickListener(v -> startActivity(new Intent(getApplicationContext(), SettingsActivity.class)));
		final SwipeRefreshLayout swipeRefreshLayout = ((SwipeRefreshLayout) findViewById(R.id.swiperefresh));
		swipeRefreshLayout.setOnRefreshListener(
				() -> {
					WifiManager wifiManager = getSystemService(WifiManager.class);
					if(wifiManager != null )
						wifiManager.startScan();
					listNetworks();
					swipeRefreshLayout.setRefreshing(false);
				}
		);

		createNotificationChannel(this);

		// register a listener to network changes
		ConnectivityManager connManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
		connManager.registerNetworkCallback(
				new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
				myNetworkCallback);

		// Get Permissions right away from the start
		if ((checkSelfPermission(ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) &&
				(checkSelfPermission(ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED) &&
				(checkSelfPermission(CHANGE_NETWORK_STATE) == PackageManager.PERMISSION_GRANTED)) {
			showInfoDialog();
		} else {
			requestPermissions(new String[]{
					ACCESS_FINE_LOCATION,
					ACCESS_WIFI_STATE,
					CHANGE_WIFI_STATE
			}, REQUEST_CODE_PERMISSIONS);
		}
	}

	protected void onStart() {
		super.onStart();

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
		if(!isLocationServicesEnabled(this))
			showError(R.string.error_no_location_enabled);

		registerReceiver(scanFinishedListener, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
		WifiManager wifiManager = getSystemService(WifiManager.class);
		try {
			wifiManager.startScan(); // startScan() will be deprecated (from API 28)?
		} catch(SecurityException e) {
			Log.w(AppInfo.APP_NAME, "startScan failed", e);
		}
		listNetworks();
		// and start service for the first time
		startWifiService(this);
	}

	public void onStop() {
		try {
			unregisterReceiver(scanFinishedListener); // may fail
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
	private void listNetworks() {
		WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
		List<ScanResult> scanResults = wifiManager.getScanResults();
		WifiInfo activeNetwork = wifiManager.getConnectionInfo();
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

	public void checkBatteryOptimizationsDisabled() {
		PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
		final String packageName = getPackageName();
		if(!pm.isIgnoringBatteryOptimizations(getPackageName())) {
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
							intent.setAction(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS); // kein REQUEST... nutzen, weil lt. Google Policy verboten
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
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
		}
	}

	/**
	 * Normalizes the SSID (remote quotation marks)
	 *
	 * @param ssid SSID
	 * @return normaoized SSID
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
