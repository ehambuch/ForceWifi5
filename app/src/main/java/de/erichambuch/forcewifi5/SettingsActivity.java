package de.erichambuch.forcewifi5;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.ACCESS_WIFI_STATE;
import static android.Manifest.permission.CHANGE_WIFI_STATE;

import android.Manifest;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Activity for Preferences screen.
 */
public class SettingsActivity extends AppCompatActivity {

	private MySettingsFragment settingsFragment;

	public static class MySettingsFragment extends PreferenceFragmentCompat {
	    @Override
	    public void onCreatePreferences(@NonNull Bundle savedInstanceState, @NonNull String rootKey) {
	        setPreferencesFromResource(R.xml.preferences, rootKey);
		}
	}
	
    @Override
    protected void onCreate(@NonNull Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
		if(AppInfo.INTENT_SHOW_DATAPROCTECTION.equals(getIntent().getAction())) {
			try {
				final Intent emailIntent = new Intent(Intent.ACTION_VIEW);
				emailIntent.setData(Uri.parse(getString(R.string.app_url)));
				if (emailIntent.resolveActivity(getPackageManager()) != null) {
					Bundle bundle = new Bundle();
					startActivity(Intent.createChooser(emailIntent, getString(R.string.action_info)), bundle);
				} else
					Toast.makeText(this, R.string.error_not_supported, Toast.LENGTH_LONG).show();
				finish();
			} catch(ActivityNotFoundException e) {
				Log.e(AppInfo.APP_NAME, "Error opening browser", e);
				Toast.makeText(this, R.string.error_not_supported, Toast.LENGTH_LONG).show();
			}
		} else if (AppInfo.INTENT_SHOW_MARKET.equals(getIntent().getAction())) {
			try {
				final Intent emailIntent = new Intent(Intent.ACTION_VIEW);
				emailIntent.setData(Uri.parse("market://details?id="+getPackageName()));
				if (emailIntent.resolveActivity(getPackageManager()) != null) {
					Bundle bundle = new Bundle();
					startActivity(Intent.createChooser(emailIntent, getString(R.string.feedback_subject)), bundle);
				} else
					Toast.makeText(this, R.string.error_not_supported, Toast.LENGTH_LONG).show();
				finish();
			} catch(ActivityNotFoundException e) {
				Log.e(AppInfo.APP_NAME, "Error opening browser", e);
				Toast.makeText(this, R.string.error_not_supported, Toast.LENGTH_LONG).show();
			}

		} else {
			getSupportFragmentManager()
					.beginTransaction()
					.replace(android.R.id.content, settingsFragment = new MySettingsFragment())
					.commit();
		}
	}

	protected void onStart() {
		super.onStart();
		enableValidGhz();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			setWarningIcons();
		}
	}

	private void enableValidGhz() {
		final WifiManager wifiManager = (WifiManager) getSystemService(WIFI_SERVICE);
		final boolean is24Ghz = (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) || wifiManager.is24GHzBandSupported();
		final boolean is50Ghz = wifiManager.is5GHzBandSupported();
		final boolean is60Ghz = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) && wifiManager.is6GHzBandSupported();

		final ListPreference listPreference = settingsFragment.findPreference(getString(R.string.prefs_2ghz5ghz));
		final List<String> entries = new ArrayList<>();
		final List<String> entryValues = new ArrayList<>();
		final String[] entriesDefault = getResources().getStringArray(R.array.entries25ghz);
		final String[] entryValuesDefault = getResources().getStringArray(R.array.values25ghz);
		if(is24Ghz) {
			entries.add(entriesDefault[0]);
			entryValues.add(entryValuesDefault[0]);
		}
		if(is50Ghz) {
			entries.add(entriesDefault[1]);
			entryValues.add(entryValuesDefault[1]);
		}
		if(is60Ghz) {
			entries.add(entriesDefault[2]);
			entryValues.add(entryValuesDefault[2]);
		}
		listPreference.setEntries(entries.toArray(new String[0]));
		listPreference.setEntryValues(entryValues.toArray(new String[0]));
	}

	/**
	 * Set warning icons next to preference settings if necessary.
	 */
	@RequiresApi(api = Build.VERSION_CODES.P)
	private void setWarningIcons() {
		final PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
		final ActivityManager am = (ActivityManager)getSystemService(ACTIVITY_SERVICE);
		final int iconAlert = android.R.drawable.ic_dialog_alert;
		if(am.isBackgroundRestricted())
			settingsFragment.findPreference(getString(R.string.prefs_app_settings)).setIcon(iconAlert);
		if(!isNotificationsEnabled())
			settingsFragment.findPreference(getString(R.string.prefs_notification_settings)).setIcon(iconAlert);
		if(!MainActivity.isLocationServicesEnabled(this))
			settingsFragment.findPreference(getString(R.string.prefs_location_settings)).setIcon(iconAlert);
		if(!pm.isIgnoringBatteryOptimizations(getPackageName()))
			settingsFragment.findPreference(getString(R.string.prefs_battery_settings)).setIcon(iconAlert);

		if(ActivityCompat.checkSelfPermission(this, ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
				|| ActivityCompat.checkSelfPermission(this, CHANGE_WIFI_STATE) != PackageManager.PERMISSION_GRANTED
				|| ActivityCompat.checkSelfPermission(this, ACCESS_WIFI_STATE) != PackageManager.PERMISSION_GRANTED)
			settingsFragment.findPreference(getString(R.string.prefs_permission_settings)).setIcon(iconAlert);
	}

	private boolean isNotificationsEnabled() {
		if (!NotificationManagerCompat.from(this).areNotificationsEnabled())
			return false;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			return ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
		} else
			return true;
	}

	@Override
	public void onStop() {
		super.onStop();
		// Activate or de-activate service on exit of preferences
		final boolean activated = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(getString(R.string.prefs_activation), true);
		if (activated) {
			MainActivity.startWifiService(this);
		} else {
			WifiChangeService.removeSuggestions(this);
			MainActivity.stopWifiService(this);
		}
	}

}
