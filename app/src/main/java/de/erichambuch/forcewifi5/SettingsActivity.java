package de.erichambuch.forcewifi5;

import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
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
        getSupportFragmentManager()
                .beginTransaction()
                .replace(android.R.id.content, settingsFragment = new MySettingsFragment())
                .commit();
	}

	protected void onStart() {
		super.onStart();
		enableValidGhz();
	}

	private void enableValidGhz() {
		final WifiManager wifiManager = (WifiManager) getSystemService(WIFI_SERVICE);
		final boolean is24Ghz = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ? wifiManager.is24GHzBandSupported() : true;
		final boolean is50Ghz = wifiManager.is5GHzBandSupported();
		final boolean is60Ghz = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ? wifiManager.is60GHzBandSupported() : false;

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
