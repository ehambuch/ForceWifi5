package de.erichambuch.forcewifi5;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceFragmentCompat;

/**
 * Activity for Preferences screen.
 */
public class SettingsActivity extends AppCompatActivity {
	
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
                .replace(android.R.id.content, new MySettingsFragment())
                .commit();
    }

}
