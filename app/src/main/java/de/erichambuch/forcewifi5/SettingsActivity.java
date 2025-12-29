package de.erichambuch.forcewifi5;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.ACCESS_WIFI_STATE;
import static android.Manifest.permission.CHANGE_WIFI_STATE;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.net.wifi.WifiAvailableChannel;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RequiresPermission;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.preference.ListPreference;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;

import de.erichambuch.forcewifi5.databinding.ActivitySettingsBinding;

/**
 * Activity for Preferences screen.
 */
public class SettingsActivity extends AppCompatActivity {

	private MySettingsFragment settingsFragment;

	private ActivitySettingsBinding binding;

	public static class MySettingsFragment extends PreferenceFragmentCompat {
		@Override
		public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
			setPreferencesFromResource(R.xml.preferences, rootKey);
		}
	}

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		binding = ActivitySettingsBinding.inflate(getLayoutInflater());
		setContentView(binding.getRoot());

		ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), new OnApplyWindowInsetsListener() {
			@Override
			public @NonNull WindowInsetsCompat onApplyWindowInsets(@NonNull View v, @NonNull WindowInsetsCompat insets) {
				Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
				v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
				return insets;
			}});

		if (AppInfo.INTENT_SHOW_DATAPROCTECTION.equals(getIntent().getAction())) {
			try {
				final Intent emailIntent = new Intent(Intent.ACTION_VIEW);
				emailIntent.setData(Uri.parse(getString(R.string.app_dataprotection_url)));
				if (emailIntent.resolveActivity(getPackageManager()) != null) {
					Bundle bundle = new Bundle();
					startActivity(Intent.createChooser(emailIntent, getString(R.string.action_privacy)), bundle);
				} else
					Toast.makeText(this, R.string.error_not_supported, Toast.LENGTH_LONG).show();
				finish();
			} catch (ActivityNotFoundException e) {
				Log.e(AppInfo.APP_NAME, "Error opening browser", e);
				Toast.makeText(this, R.string.error_not_supported, Toast.LENGTH_LONG).show();
			}
		} else if (AppInfo.INTENT_SHOW_FAQ.equals(getIntent().getAction())) {
			try {
				final Intent emailIntent = new Intent(Intent.ACTION_VIEW);
				emailIntent.setData(Uri.parse(getString(R.string.app_faq_url)));
				if (emailIntent.resolveActivity(getPackageManager()) != null) {
					Bundle bundle = new Bundle();
					startActivity(Intent.createChooser(emailIntent, getString(R.string.action_info)), bundle);
				} else
					Toast.makeText(this, R.string.error_not_supported, Toast.LENGTH_LONG).show();
				finish();
			} catch (ActivityNotFoundException e) {
				Log.e(AppInfo.APP_NAME, "Error opening browser", e);
				Toast.makeText(this, R.string.error_not_supported, Toast.LENGTH_LONG).show();
			}
		} else if (AppInfo.INTENT_SHOW_MARKET.equals(getIntent().getAction())) {
			try {
				final Intent emailIntent = new Intent(Intent.ACTION_VIEW);
				emailIntent.setData(Uri.parse("market://details?id=" + getPackageName()));
				if (emailIntent.resolveActivity(getPackageManager()) != null) {
					Bundle bundle = new Bundle();
					startActivity(Intent.createChooser(emailIntent, getString(R.string.feedback_subject)), bundle);
				} else
					Toast.makeText(this, R.string.error_not_supported, Toast.LENGTH_LONG).show();
				finish();
			} catch (ActivityNotFoundException e) {
				Log.e(AppInfo.APP_NAME, "Error opening browser", e);
				Toast.makeText(this, R.string.error_not_supported, Toast.LENGTH_LONG).show();
			}
		} else if (AppInfo.INTENT_SHOW_OVERLAY.equals(getIntent().getAction())) {
			StringBuilder overlayParams = logOverlayParameters();
			overlayParams.append("<p>Vendor: ").append(Build.MANUFACTURER).append("/").append(Build.MODEL).append("/").append(Build.VERSION.SDK_INT).append("</p>");
			final MaterialAlertDialogBuilder dialogBuilder = new MaterialAlertDialogBuilder(this)
					.setTitle(getString(R.string.app_name))
					.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							dialog.cancel();
							finish();
						}
					})
					.setNeutralButton(android.R.string.copy, new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							ClipboardManager clipboardManager = getSystemService(ClipboardManager.class);
							clipboardManager.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), overlayParams.toString()));
							dialog.cancel();
							finish();
						}
					})
					.setMessage(Html.fromHtml(overlayParams.toString(), Html.FROM_HTML_MODE_COMPACT));
			final AlertDialog dialog = dialogBuilder.create();
			TextView view = (TextView) dialog.findViewById(android.R.id.message);
			if (view != null)
				view.setMovementMethod(LinkMovementMethod.getInstance());
			dialog.show();
		} else
			getSupportFragmentManager()
					.beginTransaction()
					.replace(android.R.id.content, settingsFragment = new MySettingsFragment())
					.commit();
	}

	protected void onStart() {
		super.onStart();
		if (settingsFragment != null) { // if null, then we handle the different other actions
			enableValidGhz();
			settingsFragment.findPreference(getString(R.string.prefs_aggressive_change)).setEnabled(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S);
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
				setWarningIcons();
			}

			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
					ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED) {
				settingsFragment.findPreference(getString(R.string.prefs_selectchannels)).setEnabled(true);
				setListOfAvailableChannels();
			} else
				settingsFragment.findPreference(getString(R.string.prefs_selectchannels)).setEnabled(false);
		}
	}

	private void enableValidGhz() {
		final WifiManager wifiManager = (WifiManager) getSystemService(WIFI_SERVICE);
		final boolean is24Ghz = (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) || wifiManager.is24GHzBandSupported();
		final boolean is50Ghz = wifiManager.is5GHzBandSupported();
		final boolean is60Ghz = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) && wifiManager.is6GHzBandSupported();
		final boolean is600Ghz = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) && wifiManager.is60GHzBandSupported();

		final ListPreference listPreference = settingsFragment.findPreference(getString(R.string.prefs_2ghz5ghz));
		final List<String> entries = new ArrayList<>();
		final List<String> entryValues = new ArrayList<>();
		final String[] entriesDefault = getResources().getStringArray(R.array.entries25ghz);
		final String[] entryValuesDefault = getResources().getStringArray(R.array.values25ghz);
		if (is24Ghz) {
			entries.add(entriesDefault[0]);
			entryValues.add(entryValuesDefault[0]);
		}
		if (is50Ghz) {
			entries.add(entriesDefault[1]);
			entryValues.add(entryValuesDefault[1]);
		}
		if (is60Ghz) {
			entries.add(entriesDefault[2]);
			entryValues.add(entryValuesDefault[2]);
		}
		if (is600Ghz) {
			entries.add(entriesDefault[3]);
			entryValues.add(entryValuesDefault[3]);
		}
		listPreference.setEntries(entries.toArray(new String[0]));
		listPreference.setEntryValues(entryValues.toArray(new String[0]));
	}

	/**
	 * Set list of available channels (frequency) for the experimental feature.
	 * <p>No not supported, deactivates setting.</p>
	 */
	@RequiresPermission(value = "android.permission.NEARBY_WIFI_DEVICES")
	protected void setListOfAvailableChannels() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			final MultiSelectListPreference multiSelectListPreference = settingsFragment.findPreference(getString(R.string.prefs_selectchannels));
			try {
				final WifiManager wifiManager = getSystemService(WifiManager.class);
				int freqSelector = 0;
				if (WifiUtils.is24GHzPreferred(this))
					freqSelector = 1; // WifiScanner.WIFI_BAND_24_GHZ;
				else if (WifiUtils.is5GHzPreferred(this))
					freqSelector = 6; // WIFI_BAND_5_GHZ_WITH_DFS
				else if (WifiUtils.is6GHzPreferred(this))
					freqSelector = 8; // WIFI_BAND_6_GHZ=8
				else if (WifiUtils.is60GHzPreferred(this))
					freqSelector = 16;
				List<WifiAvailableChannel> channels = wifiManager.getAllowedChannels(freqSelector, 0);
				final List<String> entries = new ArrayList<>();
				final List<String> entriesValues = new ArrayList<>();
				for(WifiAvailableChannel c : channels) {
					entries.add(c.getFrequencyMhz() + " MHz");
					entriesValues.add(String.valueOf(c.getFrequencyMhz()));
				}

				multiSelectListPreference.setEntries(entries.toArray(new String[0]));
				multiSelectListPreference.setEntryValues(entriesValues.toArray(new String[0]));
			} catch(Exception e) { // e.g. UnsupportedOperation
				Log.i(AppInfo.APP_NAME, "Unsupported function", e);
				multiSelectListPreference.setEnabled(false);
			}
		}
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

		if(!isWifiChangeWifiStateAllowed())
			settingsFragment.findPreference(getString(R.string.prefs_wifichange_settings)).setIcon(iconAlert);

		// set only enabled if Setting is supported
		settingsFragment.findPreference(getString(R.string.prefs_wifiband_settings)).setEnabled(
			(getPackageManager().resolveActivity(new Intent("android.settings.WIFI_FREQUENCY_BAND"), 0) != null));
	}

	private boolean isNotificationsEnabled() {
		if (!NotificationManagerCompat.from(this).areNotificationsEnabled())
			return false;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			return ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
		} else
			return true;
	}

	/**
	 * Check if <code>CHANGE_WIFI_STATE</code> permission is granted. If not, request the user to
	 * open Settings - Apps - Special App access -> Wi-Fi Control to grant it.
	 * @return
	 */
	private boolean isWifiChangeWifiStateAllowed() {
		return (ActivityCompat.checkSelfPermission(this, CHANGE_WIFI_STATE) == PackageManager.PERMISSION_GRANTED);
	}

	@Override
	public void onStop() {
		super.onStop();
		// Activate or de-activate service on exit of preferences
		final boolean activated = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(getString(R.string.prefs_activation), true);
		if (activated) {
			WifiChangeService.removeSuggestions(this); // automatically reset
			MainActivity.startWifiService(this);
		} else {
			MainActivity.stopWifiService(this);
		}
	}

	/**
	 * Log out overlay parameters set by vendor.
	 * @see <a href="https://source.android.com/docs/core/connect/wifi-network-selection?hl=de">Android Plattform Wifi Selection</a>
	 * @see <a href="https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/res/values/config.xml">Android Open Source Git</a>
	 * @see <a href="https://github.com/aosp/wifi/blob/master/service/java/com/android/server/wifi/WifiConfigStore.java">WifiConfigStore</a>
	 * @see <a href="https://android.googlesource.com/platform/frameworks">WifiNetworkSelector</a>
	 */
	private StringBuilder logOverlayParameters() {
		WifiManager wf = getSystemService(WifiManager.class);
		StringBuilder builder = new StringBuilder(4096);
		builder.append(getString(R.string.text_show_overlay_params));
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
				builder.append("Scan Throttling: ").append(wf.isScanThrottleEnabled()).append("<br/>");
			}
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
				builder.append("Dual Band Simultaneous Support: ").append(wf.isDualBandSimultaneousSupported()).append("<br/>");
			}
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
				builder.append("Make Before Break Switching Support: ").append(wf.isMakeBeforeBreakWifiSwitchingSupported()).append("<br/>");
			}
			logOverlayInt("config_networkAvoidBadWifi", builder);
			logOverlayBoolean("config_wifi_dual_band_support", builder);
			logOverlayBoolean("config_wifi_enable_5GHz_preference", builder); //  Boolean indicating autojoin will prefer 5GHz and choose 5GHz BSSIDs
			logOverlayInt("config_wifi_framework_5GHz_preference_boost_factor", builder);
			logOverlayInt("config_wifi_framework_5GHz_preference_penalty_factor", builder);
			logOverlayInt("config_wifi_framework_5GHz_preference_penalty_threshold", builder);
			logOverlayInt("config_wifi_framework_5GHz_preference_boost_threshold", builder);
			logOverlayInt("config_wifi_framework_wifi_score_bad_rssi_threshold_5GHz", builder);
			logOverlayInt("config_wifi_framework_wifi_score_low_rssi_threshold_5GHz", builder); //  or -70 dBm for the 5 GHz and 6
			logOverlayInt("config_wifi_framework_wifi_score_good_rssi_threshold_5GHz", builder);
			logOverlayInt("config_wifi_framework_wifi_score_bad_rssi_threshold_24GHz", builder);
			logOverlayInt("config_wifi_framework_wifi_score_low_rssi_threshold_24GHz", builder); // RSSI is above -73 dBm
			logOverlayInt("config_wifi_framework_wifi_score_good_rssi_threshold_24GHz", builder);
			logOverlayBoolean("config_wifi_framework_enable_associated_network_selection", builder);
			logOverlayBoolean("config_wifi_framework_enable_associated_autojoin_scan", builder);
			logOverlayInt("config_wifi_framework_scan_interval", builder);
			logOverlayInt("config_wifiSufficientDurationAfterUserSelectionMilliseconds", builder);
			logOverlayInt("config_wifi_framework_wifi_score_entry_rssi_threshold_24GHz", builder); // minimale RSSI beträgt -80 dBm für das 2,4-GHz-Band
			logOverlayInt("config_wifi_framework_wifi_score_entry_rssi_threshold_5GHz", builder); // und -77 dBm für die 5-GHz- und 6-GHz-
			logOverlayInt("config_wifiFrameworkScoreEntryRssiThreshold6ghz", builder);
			logOverlayBoolean("config_wifiHighMovementNetworkSelectionOptimizationEnabled", builder);
			logOverlayInt("config_wifiHighMovementNetworkSelectionOptimizationScanDelayMs", builder);
			logOverlayInt("config_wifiHighMovementNetworkSelectionOptimizationRssiDelta", builder);
			logOverlayInt("config_wifiPollRssiIntervalMilliseconds", builder);
			logOverlayBoolean("config_wifiAdjustPollRssiIntervalEnabled", builder);
			logOverlayInt("config_wifiPollRssiIntervalMilliseconds", builder);
			logOverlayInt("config_wifi_framework_wifi_score_low_rssi_threshold_24GHz", builder);
			logOverlayInt("config_wifi_framework_wifi_score_low_rssi_threshold_5GHz", builder);
			logOverlayInt("config_wifiFrameworkScoreLowRssiThreshold6ghz", builder);
			logOverlayInt("config_wifiFrameworkThroughputBonusNumerator", builder);
			logOverlayInt("config_wifiFrameworkThroughputBonusDenominator", builder);
			logOverlayInt("config_wifiFrameworkThroughputBonusLimit", builder);
			logOverlayInt("config_wifiFrameworkLastSelectionMinutes", builder);
			logOverlayInt("config_wifiFrameworkCurrentNetworkBonusMin", builder);
			logOverlayInt("config_wifiFrameworkCurrentNetworkBonusPercent", builder);
			logOverlayInt("config_wifiFrameworkSecureNetworkBonus", builder);
			logOverlayInt("config_wifiFrameworkUnmeteredNetworkBonus", builder);
			logOverlayInt("config_wifiFrameworkSavedNetworkBonus", builder);
			logOverlayInt("config_wifiEstimateRssiErrorMarginDb", builder);
			return builder;
		}
		catch(Exception e) {
			Log.w(AppInfo.APP_NAME, e);
			return builder;
		}
	}

	private void logOverlayBoolean(String parameter, StringBuilder builder) {
		// this is the preferred way to get the identifier from com.android.internal.R$bool
		@SuppressLint("DiscouragedApi") int identifer = Resources.getSystem().getIdentifier(parameter, "bool", "android");
		if(identifer != 0) {
			logParameter(builder, parameter).append(": ").append(Resources.getSystem().getBoolean(identifer)).append("<br/>");
		}
	}

	private void logOverlayInt(String parameter, StringBuilder builder) {
		@SuppressLint("DiscouragedApi") int identifer = Resources.getSystem().getIdentifier(parameter, "integer", "android");
		if(identifer != 0) {
			logParameter(builder, parameter).append(": ").append(Resources.getSystem().getInteger(identifer)).append("<br/>");
		}
	}

	private StringBuilder logParameter(StringBuilder builder, String param) {
		String text = param.replace("config", "");
		for(int i=0;i<text.length();i++) {
			if (text.charAt(i) == '_') { // convert to Camel Case
				builder.append(' ');
				builder.append(Character.toTitleCase(text.charAt(++i)));
			} else
				builder.append(text.charAt(i));
		}
		return builder;
	}

}
