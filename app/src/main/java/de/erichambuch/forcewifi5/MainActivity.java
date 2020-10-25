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
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Html;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
	        startService(new Intent(MainActivity.this, WifiChangeService.class));
	    }
	}
	
	public static class NetworkEntry {
		String name;
		boolean is24ghz;
		boolean is5ghz;
		List<AccessPointEntry> accessPoints = new ArrayList<>(2);
		
		NetworkEntry(String name) {
			this.name = name;
		}
		@NonNull
		public String toString() {
			StringBuilder text = new StringBuilder();
			text.append("<b>");
			text.append(name);
			text.append("</b><small><br/>");
			for(AccessPointEntry entry : accessPoints) {
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
		String bssid;
		int frequency;
		int signalLevel;

		AccessPointEntry(String bssid, int freq, int level) {
			this.bssid = bssid;
			this.frequency = freq;
			this.signalLevel = level;
		}
		public String toString() { return this.bssid; }
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
				context.startService(new Intent(context.getApplicationContext(), WifiChangeService.class));
				MainActivity.this.listNetworks();
			}
		}
	}
	
	private final NetworkCallback myNetworkCallback = new NetworkCallback();
	private final ScanFinishedListener scanFinishedListener = new ScanFinishedListener();
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
		setContentView(R.layout.activity_main);

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

		if ((checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) &&
				(checkSelfPermission(android.Manifest.permission.CHANGE_NETWORK_STATE) == PackageManager.PERMISSION_GRANTED)) {
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
					android.Manifest.permission.ACCESS_FINE_LOCATION,
					android.Manifest.permission.CHANGE_WIFI_STATE
			}, REQUEST_CODE_PERMISSIONS);
		}
	}
	private void doStart() {
		if(!isLocationServicesEnabled(this))
			showError(R.string.error_no_location_enabled);
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { // as startScan() is deprecated from API 28
			listNetworks();
			startService(new Intent(getApplicationContext(), WifiChangeService.class));
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
	public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
		if (requestCode == REQUEST_CODE_PERMISSIONS && permissions.length >= 1) {
			for(int grant : grantResults) {
				if ( grant != PackageManager.PERMISSION_GRANTED)
					return;
			}
			doStart();
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
		if (requestCode == REQUEST_CODE_LOCATION_SERVICES)
			if (isLocationServicesEnabled(this))  // avoid endless loop if not acticated
				listNetworks();
		else
			super.onActivityResult(requestCode, resultCode, resultIntent);
	}

	/**
	 * Show a list of all detected network with provide both: 2.4 and 5 GHz.
	 */
	private void listNetworks() {
		WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
		List<ScanResult> scanResults = wifiManager.getScanResults();
		// build up list with all SSID supporting 2.4 and 5 GHz
		List<NetworkEntry> listNetworks = new ArrayList<>();
		Map<String, NetworkEntry> map245Ghz = new HashMap<>();
		for(ScanResult result : scanResults) {
			if (result.SSID != null && result.SSID.length() > 0 ) {
				NetworkEntry isThere = map245Ghz.get(result.SSID);
				if ( isThere == null ) 
					map245Ghz.put(result.SSID, isThere = new NetworkEntry(result.SSID));
				if ( result.frequency >= 2000 && result.frequency <= 2999 )
					isThere.is24ghz = true;
				if ( result.frequency >= 5000 && result.frequency <= 5999 )
					isThere.is5ghz = true;
				isThere.addAccessPoint(new AccessPointEntry(result.BSSID, result.frequency, result.level));
			}
		}
		ListView view = (ListView) findViewById(R.id.listview);
		for(NetworkEntry entry : map245Ghz.values()) {
			if(entry.is24ghz && entry.is5ghz)
				listNetworks.add(entry);
		}
		view.setAdapter(new ArrayAdapter<>(this, R.layout.list_view_entry, listNetworks));
	}
	

	public static boolean isLocationServicesEnabled(Context context) {
		// TODO use androidx.appcompat:appcompat:1.2.0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        	// This is new method provided in API 28
            LocationManager lm = (LocationManager) context.getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
            return lm != null && lm.isLocationEnabled();
        } else {
        // This is Deprecated in API 28
            @SuppressWarnings("deprecation")
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
			int importance = NotificationManager.IMPORTANCE_DEFAULT;
			NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
			channel.setDescription(description);
			NotificationManager notificationManager = getSystemService(NotificationManager.class);
			notificationManager.createNotificationChannel(channel);
		}
	}
}
