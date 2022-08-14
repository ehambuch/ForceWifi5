package de.erichambuch.forcewifi5;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.ACCESS_WIFI_STATE;
import static android.Manifest.permission.CHANGE_WIFI_STATE;

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

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
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
	    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
	        setPreferencesFromResource(R.xml.preferences, rootKey);
		}
	}
	
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
		if(Intent.ACTION_SENDTO.equals(getIntent().getAction())) {
			try {
				final Intent emailIntent = new Intent(Intent.ACTION_SENDTO);
				emailIntent.setDataAndType(Uri.parse("mailto:"),"*/*");
				emailIntent.putExtra(android.content.Intent.EXTRA_EMAIL, new String[]{getString(R.string.app_mailto)});
				emailIntent.putExtra(android.content.Intent.EXTRA_SUBJECT, getString(R.string.feedback_subject));
				if (emailIntent.resolveActivity(getPackageManager()) != null) {
					Bundle bundle = new Bundle();
					startActivity(Intent.createChooser(emailIntent, getString(R.string.feedback_subject)), bundle);
				}
				finish();
			} catch(ActivityNotFoundException e) {
				Log.e(AppInfo.APP_NAME, "Error sending email", e);
			}
		} else{
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
		final boolean is24Ghz = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || wifiManager.is24GHzBandSupported();
		final boolean is50Ghz = wifiManager.is5GHzBandSupported();
		final boolean is60Ghz = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && wifiManager.is60GHzBandSupported();

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
		if(!MainActivity.isLocationServicesEnabled(this))
			settingsFragment.findPreference(getString(R.string.prefs_location_settings)).setIcon(iconAlert);
		if(!pm.isIgnoringBatteryOptimizations(getPackageName()))
			settingsFragment.findPreference(getString(R.string.prefs_battery_settings)).setIcon(iconAlert);

		if(ActivityCompat.checkSelfPermission(this, ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
				|| ActivityCompat.checkSelfPermission(this, CHANGE_WIFI_STATE) != PackageManager.PERMISSION_GRANTED
				|| ActivityCompat.checkSelfPermission(this, ACCESS_WIFI_STATE) != PackageManager.PERMISSION_GRANTED)
			settingsFragment.findPreference(getString(R.string.prefs_permission_settings)).setIcon(iconAlert);
	}

    @Override
	public void onStop() {
		super.onStop();
		// Activate or de-activate service on exit of preferences
		final boolean actived = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(getString(R.string.prefs_activation), true);
		if (actived) {
			MainActivity.startWifiService(this);
		} else {
			WifiChangeService.removeSuggestions(this);
			MainActivity.stopWifiService(this);
		}
	}

}
