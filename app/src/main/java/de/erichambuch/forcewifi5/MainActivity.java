package de.erichambuch.forcewifi5;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
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
import android.provider.Settings;
import android.text.Html;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresPermission;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.ACCESS_WIFI_STATE;
import static android.Manifest.permission.CHANGE_NETWORK_STATE;
import static android.Manifest.permission.CHANGE_WIFI_STATE;
import static android.text.Html.FROM_HTML_MODE_LEGACY;

/**
 * Main activity für die App.
 */
public class MainActivity extends AppCompatActivity {

	public static final String CHANNEL_ID = "ForceWifi5";
	private static final int REQUEST_CODE_LOCATION_SERVICES = 4567;
	private static final int REQUEST_CODE_PERMISSIONS = 5678;

	private class NetworkCallback extends ConnectivityManager.NetworkCallback {
	    @Override
	    public void onAvailable(Network network) {
	        startWifiService(MainActivity.this);
	    }
	}

	public static class NetworkEntry {
		final String name;
		final boolean connected;
		boolean is24ghz;
		boolean is5ghz;
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
			for(AccessPointEntry entry : accessPoints) {
				if ( entry.connected )
					text.append("->");
				text.append(entry.bssid);
				text.append(" - ");
				text.append(entry.frequency);
				text.append(" GHz</small>");
				text.append("<br/>");
			}
			// letztes br löschen
			return Html.fromHtml(text.substring(0,text.length()-5), FROM_HTML_MODE_LEGACY).toString();
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
		public @NonNull String toString() { return this.bssid; }
	}

	/**
	 * Listener sobald Wifi Scan abgeschlossen. Dann wird die Liste aller Wifis angezeigt und
	 * versucht das Wifi ggf. zu wechseln.
	 * @deprecated nur für API &lt; 28
	 */
	@Deprecated
	public class ScanFinishedListener extends BroadcastReceiver {

		@Override
		public void onReceive(Context context, Intent intent) {
			if (intent.getAction().equals(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {
				boolean success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
				if (!success)
					Toast.makeText(context, R.string.error_scan_failed, Toast.LENGTH_LONG).show();
				startWifiService(context);
				try {
					MainActivity.this.listNetworks();
				}
				catch (SecurityException e) {
					Log.e(AppInfo.APP_NAME, "Error listing networks", e);
				}
			}
		}
	}

	private final NetworkCallback myNetworkCallback = new NetworkCallback();
	private final ScanFinishedListener scanFinishedListener = new ScanFinishedListener();
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
		setContentView(R.layout.activity_main);
		findViewById(R.id.floatingActionButton).setOnClickListener(v -> startActivity(new Intent(getApplicationContext(), SettingsActivity.class)));

		// register a listener to network changes
		ConnectivityManager connManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
		connManager.registerNetworkCallback(
				new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
				myNetworkCallback);

		createNotificationChannel();
	}

	@SuppressLint("ObsoleteSdkInt")
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
			requestPermissions(new String[]{
					ACCESS_FINE_LOCATION,
					ACCESS_WIFI_STATE,
					CHANGE_WIFI_STATE
			}, REQUEST_CODE_PERMISSIONS);
		}
	}

	@RequiresPermission(allOf = {ACCESS_WIFI_STATE, ACCESS_FINE_LOCATION})
	private void doStart() {
		if(!isLocationServicesEnabled(this))
			showError(R.string.error_no_location_enabled);

		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { // as startScan() is deprecated from API 28
			listNetworks();
			startWifiService(this);
		} else {
			registerReceiver(scanFinishedListener, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
			WifiManager wifiManager = getSystemService(WifiManager.class);
			wifiManager.startScan();
			// initial call and update will be trigged by finishing of scan in ScanFinishedListener
		}
	}

	public void onStop() {
		//unregisterReceiver(scanFinishedListener); // may fail
		super.onStop();
	}
	
	protected void onDestroy() {
		// do not call connManager.unregisterNetworkCallback(myNetworkCallback); as we want to continue receiving events
		super.onDestroy();
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		if (requestCode == REQUEST_CODE_PERMISSIONS && permissions.length >= 1) {
			for(int grant : grantResults) {
				if ( grant != PackageManager.PERMISSION_GRANTED)
					return;
			}
			try {
				doStart();
			}
			catch(SecurityException e) {
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
				isThere.addAccessPoint(new AccessPointEntry(result.BSSID, result.frequency, result.level,
						connected && result.BSSID.equals(activeNetwork.getBSSID())));
			}
		}
		ListView view = (ListView) findViewById(R.id.listview);
		for(NetworkEntry entry : map245Ghz.values()) {
			if(entry.is24ghz && entry.is5ghz)
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
			view.setAdapter(new ArrayAdapter<>(this, R.layout.list_view_entry, new ArrayList<>(map245Ghz.values())));
			findViewById(R.id.nowificardview).setBackgroundColor(getResources().getColor(R.color.colorPrimaryDark, getTheme()));
			((TextView)findViewById(R.id.nowifitextview)).setText(R.string.text_nowififound);
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
	
	private void showError(int stringId) {
		Toast.makeText(this, stringId, Toast.LENGTH_LONG).show();
	}

	private void createNotificationChannel() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			CharSequence name = getString(R.string.app_name);
			String description = getString(R.string.app_description);
			int importance = NotificationManager.IMPORTANCE_LOW;
			NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
			channel.setDescription(description);
			NotificationManager notificationManager = getSystemService(NotificationManager.class);
			notificationManager.createNotificationChannel(channel);
		}
	}

	/**
	 * Runs the {@link WifiChangeService} as a foreground task starting with Android 9.
	 *
	 * @param  context the context
	 */
	public static void startWifiService(Context context) {
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
					// If call comes from a BroadcastReceiver (see Javadoc of Bindservice). try again without bind()
					context.getApplicationContext().startForegroundService(intent);
				}
			} else {
				context.startService(intent); // on Android 8 and below
			}
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
