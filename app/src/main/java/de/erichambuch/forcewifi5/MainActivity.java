package de.erichambuch.forcewifi5;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.ACCESS_WIFI_STATE;
import static android.text.Html.FROM_HTML_MODE_LEGACY;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static de.erichambuch.forcewifi5.WifiChangeService.ONGOING_NOTIFICATION_ID;
import static de.erichambuch.forcewifi5.WifiChangeService.showNotificationMessage;

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
import android.content.SharedPreferences;
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
import android.net.wifi.WifiSsid;
import android.os.BadParcelableException;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.DragEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
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
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkManager;
import androidx.work.WorkRequest;

import com.google.android.material.bottomappbar.BottomAppBar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.divider.MaterialDividerItemDecoration;
import com.google.android.material.snackbar.Snackbar;
import com.google.firebase.FirebaseApp;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Main activity für die App.
 */
public class MainActivity extends AppCompatActivity {

	public static final String CHANNEL_ID = "ForceWifi5";

	public static final String INTENT_WIFICHANGETEXT = "de.erichambuch.forcewifi5.WIFICHANGETEXT";

	public static final String EXTRA_WIFICHANGETEXT = "de.erichambuch.forcewifi5.recommendation";

	private static final int REQUEST_CODE_LOCATION_SERVICES = 4567;

	/**
	 * Circuit breaker - shared by different listeners.
	 */
	@NonNull
	protected static final AtomicLong lastNetworkCallback = new AtomicLong(0);

	/**
	 * List of all available Wifi networks.
	 */
	@NonNull
	protected final List<AccessPointEntry> listNetworks = Collections.synchronizedList(new ArrayList<>());

	/**
	 * List of all preferred Wifi networks.
	 */
	@NonNull
	protected final List<AccessPointEntry> preferredNetworks = Collections.synchronizedList(new ArrayList<>());

	/**
	 * Flag whether to check for notification enabling or the user dismissed that check.
	 */
	protected volatile boolean checkForNotificationsEnabled = true;

	protected AdMobUtils adMobUtils;

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
		public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities networkCapabilities) {
			WifiInfo wifiInfo = (WifiInfo) networkCapabilities.getTransportInfo();
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

	/**
	 * Receiver to check if Android has accepted our WifiSuggestions.
	 */
	public class WifiSuggestionReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (WifiManager.ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION.equals(intent.getAction())) {
				// Android has successfully connected to one of your suggestions!
				// You can get the details of the suggestion that was used:
				WifiNetworkSuggestion suggestion = intent.getParcelableExtra(
						WifiManager.EXTRA_NETWORK_SUGGESTION);

				Log.d(AppInfo.APP_NAME, "Connected to: " + suggestion.getSsid());

				final String ssid = suggestion != null ? suggestion.getSsid() : "?";

				showNotificationMessage(context, String.format(getString(R.string.text_wifi_change_accepted), ssid));
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
				text.append(entry.bssid);
				text.append(" - ");
				if(entry.frequencies != null)
					text.append(entry.frequencies);
				text.append("</small>");
				text.append("<br/>");
			}
			// letztes br löschen
			return Html.fromHtml(text.substring(0, text.length() - 5), FROM_HTML_MODE_LEGACY).toString();
		}

		void addAccessPoint(AccessPointEntry entry) {
			this.accessPoints.add(entry);
		}
	}

	public static class AccessPointFrequencies implements Parcelable {

		public static final Creator<AccessPointFrequencies> CREATOR = new Creator<>() {
			@Override
			public AccessPointFrequencies createFromParcel(Parcel in) {
				return new AccessPointFrequencies(in);
			}

			@Override
			public AccessPointFrequencies[] newArray(int size) {
				return new AccessPointFrequencies[size];
			}
		};

		private static final Map<Integer, String> CHANNELWIDTH = new HashMap<>();

		static {
			CHANNELWIDTH.put(ScanResult.CHANNEL_WIDTH_20MHZ, "20 MHz");
			CHANNELWIDTH.put(ScanResult.CHANNEL_WIDTH_40MHZ, "40 MHz");
			CHANNELWIDTH.put(ScanResult.CHANNEL_WIDTH_80MHZ, "80 MHz");
			CHANNELWIDTH.put(ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ, "80+80 MHz");
			CHANNELWIDTH.put(ScanResult.CHANNEL_WIDTH_160MHZ, "160 MHz");
			CHANNELWIDTH.put(5, "320 MHz"); // ScanResult.CHANNEL_WIDTH_320MHZ
		}

		final int frequency;

		final int center0;

		final int center1;

		final int channelwidth;

		public AccessPointFrequencies(int frequency, int center0, int center1, int bandwidth) {
			this.frequency = frequency;
			this.center0 = center0;
			this.center1 = center1;
			this.channelwidth = bandwidth;
		}

		protected AccessPointFrequencies(Parcel in) {
			frequency = in.readInt();
			center0 = in.readInt();
			center1 = in.readInt();
			channelwidth = in.readInt();
		}


		@Override
		public int describeContents() {
			return 0;
		}

		@Override
		public void writeToParcel(Parcel dest, int flags) {
			dest.writeInt(frequency);
			dest.writeInt(center0);
			dest.writeInt(center1);
			dest.writeInt(channelwidth);
		}

		@NonNull
		public String toString() {
			final StringBuilder builder = new StringBuilder(32);
			return builder.append(frequency).append(" MHz ").append((char)0x00B1).append(" ").append(CHANNELWIDTH.getOrDefault(channelwidth, "0")).toString();
		}
	}

	public static class AccessPointEntry implements Parcelable {

		public static final Creator<AccessPointEntry> CREATOR = new Creator<>() {
			@Override
			public AccessPointEntry createFromParcel(Parcel in) {
				return new AccessPointEntry(in);
			}

			@Override
			public AccessPointEntry[] newArray(int size) {
				return new AccessPointEntry[size];
			}
		};

		final String name;

		@Nullable
		final WifiSsid wifiSsid;

		final String bssid;
		final AccessPointFrequencies frequencies;
		final int signalLevel;

		final boolean recommended;

		AccessPointEntry(String name, @Nullable WifiSsid wifiSsid, String bssid,  @Nullable AccessPointFrequencies frequencies, int level, boolean recommended) {
			this.name = name;
			this.wifiSsid = wifiSsid;
			this.bssid = bssid;
			this.frequencies = frequencies;
			this.signalLevel = level;
			this.recommended = recommended;
		}

		protected AccessPointEntry(Parcel in) {
			name = in.readString();
			wifiSsid = in.readParcelable(in.getClass().getClassLoader());
			bssid = in.readString();
			frequencies = in.readParcelable(AccessPointFrequencies.class.getClassLoader());
			signalLevel = in.readInt();
			recommended = in.readBoolean();
		}

		@Override
		public int describeContents() {
			return 0;
		}

		@Override
		public void writeToParcel(Parcel dest, int flags) {
			dest.writeString(name);
			dest.writeParcelable(wifiSsid, flags);
			dest.writeString(bssid);
			dest.writeParcelable(frequencies, flags);
			dest.writeInt(signalLevel);
			dest.writeBoolean(recommended);
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

	/**
	 * Listener for Drag &amp; Drop between Lists.
	 */
    static class ListDragListener implements View.OnDragListener {
		private final CustomWifiListAdapter adapter;

		ListDragListener(CustomWifiListAdapter adapter) {
			this.adapter = adapter;
		}

		@Override
		public boolean onDrag(View v, DragEvent event) {
			if (event.getAction() == DragEvent.ACTION_DROP) {
				if(!(event.getLocalState() instanceof CustomWifiListAdapter.WifiLocalState))
					return false;
				// we retrieve the whole object as LocalState
				CustomWifiListAdapter.WifiLocalState localState = (CustomWifiListAdapter.WifiLocalState) event.getLocalState();
				if (localState.listView.getParent() instanceof RecyclerView) {
					RecyclerView sourceRecyclerView = (RecyclerView) localState.listView.getParent();
					CustomWifiListAdapter sourceAdapter = (CustomWifiListAdapter) sourceRecyclerView.getAdapter();

					if (sourceAdapter != null && sourceAdapter != adapter) {
						sourceAdapter.removeItem(localState.accessPointEntry);
						adapter.addItem(localState.accessPointEntry);
					}
				}
				return true;
			}
			return true;
		}
	}

	private final ScanFinishedListener scanFinishedListener = new ScanFinishedListener();
	private final RecommendationListener recommendationListener = new RecommendationListener();

	private final WifiSuggestionReceiver suggestionReceiver = new WifiSuggestionReceiver();

	private NetworkCallback networkCallback;

	private Menu menuBar;

	@SuppressLint("MissingPermission")
	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.activity_main);

		this.adMobUtils = new AdMobUtils(this);

		final BottomAppBar appbar = ((BottomAppBar)findViewById(R.id.bottomAppBar));
		appbar.setOnMenuItemClickListener(this::onOptionsItemSelected);

		createNotificationChannel(this);

		findViewById(R.id.floatingButtonSave).setOnClickListener(v -> {
				MainActivity.this.saveSuggestions();
			}
		);
		findViewById(R.id.cardSwitchManualModeBtn).setOnClickListener(v -> {
			MainActivity.this.toggleManualMode();
			updateManualModeUI();
		});
		findViewById(R.id.closeCardBtn).setOnClickListener(v -> {
			findViewById(R.id.nowificardview).setVisibility(View.GONE);
		});

		// register a listener to network changes (this may occure twice if already done in StartOnBootReceiver!)
		ConnectivityManager connManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
		connManager.registerNetworkCallback(
				new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
				networkCallback = new NetworkCallback(getApplicationContext()));

		// register a listener that gives a feedback if Android has accepted change
		registerReceiver(suggestionReceiver,
				new IntentFilter(WifiManager.ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION));

        if (Intent.ACTION_MAIN.equals(getIntent().getAction()) &&
				PreferenceManager.getDefaultSharedPreferences(this).getBoolean(getString(R.string.prefs_setup), true)) // show info dialog only on first start
		{
			startActivity(new Intent(getApplicationContext(), SetupActivity.class));
		} else {
			// Get Permissions right away from the start
			String[] permissions = SetupActivity.getRequiredAppPermissions();
			boolean haveAll = true;
			for(String p : permissions) {
				if(checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED)
					haveAll = false;
			}
			if(!haveAll)
				PreferenceManager.getDefaultSharedPreferences(this).edit().putBoolean(getString(R.string.prefs_setup), true).apply();
			// and restart again next time...
		}

		// only activate Crashlytics if enabled
		try {
			if (isCrashlyticsEnabled()) {
				FirebaseApp.initializeApp(this);
				FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true);
			}
		} catch (Exception e) {
			Log.e(AppInfo.APP_NAME, "Error init Firebase", e);
		}

		// set up Ads
		adMobUtils.requestAdConsent();
		adMobUtils.setUpAds();
	}

	@SuppressLint("MissingPermission")
	protected void onStart() {
		super.onStart();

		// update if changed in settings
		updateManualModeUI();

		// starting with Android 6, Location services has to be enabled to list all wifis
		if (isLocationServicesEnabled(this)) {
			doStart();
		} else {
			showLocationServicesDialog();
		}
	}

	@Override
	public void onPause() {
		if (adMobUtils.getAdMobView() != null)
			adMobUtils.getAdMobView().pause();

		super.onPause();
	}

	@Override
	public void onResume() {
		if (adMobUtils.getAdMobView() != null) {
			try {
				adMobUtils.getAdMobView().resume();
				// load a new add when coming back after more than 1 minutes -> ensures ad refresh
				adMobUtils.loadAd();
			} catch(RuntimeException e) {
				Crashlytics.recordException(e);
			}
		}

		// show start message again
		super.onResume();
	}

	// Block for AdMob and Consent Handling



	/**
	 * Update the whole UI for Manual or Automatic mode.
	 */
	private void updateManualModeUI() {
		final boolean manualMode = isManualMode();
		findViewById(R.id.wifiListPreferred).setEnabled(manualMode);
		findViewById(R.id.listview).setEnabled(manualMode);
		BottomAppBar appBar = findViewById(R.id.bottomAppBar);
		MenuItem manualItem = appBar.getMenu().findItem(R.id.menu_manualmode); // if using a normal Menu we should overwrite onPrepareOptionsMenu
		if (manualItem != null) {
			manualItem.setIcon(manualMode ? R.drawable.autostop_24px : R.drawable.autoplay_24px);
		}
		findViewById(R.id.floatingButtonSave).setVisibility(manualMode ? VISIBLE : GONE);
		findViewById(R.id.wifiListPreferredOverlay).setVisibility(manualMode ? GONE : VISIBLE);
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
		if (!isLocationServicesEnabled(this)) {
			showError(R.string.error_no_location_enabled);
		}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			registerReceiver(scanFinishedListener, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), RECEIVER_NOT_EXPORTED);
			registerReceiver(recommendationListener, new IntentFilter(INTENT_WIFICHANGETEXT), RECEIVER_NOT_EXPORTED);
        } else {
			registerReceiver(scanFinishedListener, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
			registerReceiver(recommendationListener, new IntentFilter(INTENT_WIFICHANGETEXT));
		}

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
		} catch (Exception e) {
			Log.w(AppInfo.APP_NAME, e);
		}
		try {
			unregisterReceiver(recommendationListener); // may fail
		} catch (Exception e) {
			Log.w(AppInfo.APP_NAME, e);
		}
		super.onStop();
	}

	@Override
	public boolean onOptionsItemSelected(@NonNull MenuItem item) {
		final int id = item.getItemId();
		if( id == R.id.menu_wifionoff) {
			try {
				startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
			} catch(ActivityNotFoundException e) {
				startActivity(new Intent(Settings.Panel.ACTION_WIFI));
			}
			return true;
		} else if (id == R.id.menu_settings) {
			startActivity(new Intent(getApplicationContext(), SettingsActivity.class));
			return true;
		} else if (id == R.id.menu_about) {
			showAbout();
			return true;
		} else if (id == R.id.menu_wifi_reset) {
			removeAllSuggestions();
			return true;
		} else if (id == R.id.menu_manualmode) {
			toggleManualMode();
			updateManualModeUI();
			findViewById(R.id.mainFragment).post(new Runnable() {
				@Override
				public void run() {
					try {
						listNetworks();
					} catch (SecurityException e) {
						// ignore
					}
				}
			});
			return true;
		} else if (id == R.id.menu_info) {
			showContact();
			return true;
		} else if (id == R.id.menu_setup) {
			startActivity(new Intent(getApplicationContext(), SetupActivity.class));
		} else if (id == R.id.menu_not_working) {
			showWhyNotWorking();
		} else if (item.getItemId() == R.id.menu_privacy_options) {
			adMobUtils.showPrivacyOptions();
		} else if (item.getItemId() == R.id.menu_dataprotection) {
			openDataProtection();
		}
		return super.onOptionsItemSelected(item);
	}

	private void openDataProtection() {
		Intent i = new Intent(Intent.ACTION_VIEW);
		i.setData(Uri.parse(getString(R.string.dataprotection_url)));
		if (i.resolveActivity(getPackageManager()) != null)
			startActivity(i);
		else
			showError(getString(R.string.error_no_browser));
	}

	private void saveSuggestions() {
		final List<WifiNetworkSuggestion> suggestionList = new ArrayList<>();
		int priority = this.preferredNetworks.size();
		for(AccessPointEntry entry : this.preferredNetworks) {
			final WifiNetworkSuggestion.Builder builder=
				new WifiNetworkSuggestion.Builder().
						setBssid(MacAddress.fromString(entry.bssid)).
						setPriority(priority--);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && entry.wifiSsid != null) {
				builder.setWifiSsid(entry.wifiSsid);
				suggestionList.add(builder.build());
            } else {
				builder.setSsid(entry.name);
				suggestionList.add(builder.build());
			}
		}
		// and now provide suggestions
		WifiChangeService.provideSuggestions(MainActivity.this, suggestionList);
		showMessage(R.string.text_suggestions_saved);

		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isAggressive() && !suggestionList.isEmpty()) {
			WifiChangeService.aggressiveNetworkChange(MainActivity.this, suggestionList.get(0));
		} else {
			// Here we try to force a re-connect of the Wifi via Internet connectivity
			try {
				startActivity(WifiChangeService.getWifiIntent(getApplicationContext()));
			} catch (ActivityNotFoundException e) {
				Log.w(AppInfo.APP_NAME, e);
			}
		}
		try {
			MainActivity.this.listNetworks(); // and update UI
		} catch (SecurityException e) {
			// ignore, something very wrong
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
						Snackbar.make(MainActivity.this.findViewById(R.id.mainFragment), R.string.message_remove_suggestions, Snackbar.LENGTH_LONG).setTextMaxLines(3).show();
						try {
							MainActivity.this.listNetworks(); // and update UI
						} catch(SecurityException e) {
							// ignore, something very wrong
						}
					}
				})
				.setMessage(Html.fromHtml(getString(R.string.message_reset_suggestions), Html.FROM_HTML_MODE_COMPACT))
				.show();
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
	@SuppressLint("DefaultLocale")
    @RequiresPermission(allOf = {ACCESS_WIFI_STATE, ACCESS_FINE_LOCATION})
	void listNetworks() {
		WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
		final boolean isWifiEnabled = wifiManager != null && wifiManager.isWifiEnabled();
		List<ScanResult> scanResults = wifiManager != null ? wifiManager.getScanResults() : Collections.emptyList();
		WifiInfo activeNetwork = wifiManager.getConnectionInfo();
		boolean isConnected = activeNetwork != null && activeNetwork.getNetworkId() >= 0;
		final boolean manualMode = isManualMode();

		// display active wifi
		final boolean activeWifi = (isWifiEnabled && isConnected && activeNetwork != null);
		if(isWifiEnabled) {
			if(activeWifi) {
				if (activeNetwork.getBSSID() == null)
					((TextView) findViewById(R.id.activatedWifi)).setText(getString(R.string.text_connecting));
				else
					((TextView) findViewById(R.id.activatedWifi)).setText(
							String.format("%s - %s at %d MHz",
									WifiUtils.unquoteSSid(activeNetwork.getSSID()),
									activeNetwork.getBSSID(),
									activeNetwork.getFrequency()));
			} else {
				((TextView)findViewById(R.id.activatedWifi)).setText(getString(R.string.text_nowifi));
			}
		} else {
			((TextView)findViewById(R.id.activatedWifi)).setText(getString(R.string.error_wifi_not_enabled));
			((TextView) findViewById(R.id.nowifitextview)).setText(getString(R.string.error_wifi_not_enabled));
			((MaterialCardView)findViewById(R.id.nowificardview)).
					setCardBackgroundColor(getResources().getColor(android.R.color.holo_orange_light, getTheme()));
		}

		// We retrieve a list of all suggestions - and display them
		preferredNetworks.clear();
		try {
			final List<WifiNetworkSuggestion> suggestionList = new ArrayList<>(wifiManager.getNetworkSuggestions());
			suggestionList.sort(Comparator.comparingInt(WifiNetworkSuggestion::getPriority));
			for(WifiNetworkSuggestion suggestion : suggestionList) {
				preferredNetworks.add(new AccessPointEntry(
						WifiUtils.getSsid(suggestion),
						WifiUtils.getWifiSsid(suggestion),
						WifiUtils.getBssid(suggestion),
						null,
						-1, true));
			}
		} catch (BadParcelableException e) { // on OnePlus...
			Log.e(AppInfo.APP_NAME, "Error on getNetworkSuggestions", e);
		}
		final RecyclerView preferredListview = findViewById(R.id.wifiListPreferred);
		final CustomWifiListAdapter preferredAdapter = new CustomWifiListAdapter(preferredNetworks, wifiManager);
		preferredListview.setAdapter(preferredAdapter);
		preferredListview.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
		preferredListview.addItemDecoration(new MaterialDividerItemDecoration(this, MaterialDividerItemDecoration.VERTICAL));
		preferredListview.setOnDragListener(new ListDragListener(preferredAdapter));

		// build up list with all SSID supporting 2.4 and 5 GHz
		// we also always add the connected network (even if only on one frequency) to display completeness to user
		listNetworks.clear();
		Map<String, NetworkEntry> map245Ghz = new HashMap<>();
		for (ScanResult result : scanResults) {
			if (result.SSID != null && !result.SSID.isEmpty() && result.BSSID != null) {
				boolean connected = activeNetwork != null && WifiUtils.isSameSSID(result, activeNetwork);
				NetworkEntry isThere = map245Ghz.get(result.SSID);
				if (isThere == null)
					map245Ghz.put(result.SSID, isThere = new NetworkEntry(result.SSID, connected));
				if (result.frequency >= 2000 && result.frequency <= 2999)
					isThere.is24ghz = true;
				if (result.frequency >= 5000 && result.frequency <= 5999)
					isThere.is5ghz = true;
				if (result.frequency >= 6000 && result.frequency <= 6999)
					isThere.is6ghz = true;
				isThere.addAccessPoint(
						new AccessPointEntry(
								result.SSID,
								WifiUtils.getWifiSsid(result),
								result.BSSID,
								new AccessPointFrequencies(result.frequency, result.centerFreq0, result.centerFreq1, result.channelWidth),
								result.level, false));
			}
		}

		// filter out all networks that are qualified for switch (ALL if in manual mode)
		for (NetworkEntry entry : map245Ghz.values()) {
			if ((entry.is24ghz && (entry.is5ghz || entry.is6ghz))
					|| entry.connected || manualMode) {
				listNetworks.addAll(entry.accessPoints);
			}
		}

		// for testing on Emulator
		if(WifiUtils.isEmulator()) {
			for(int i =1;i<=20;i++) {
				listNetworks.add(new AccessPointEntry("SSID "+i, null,"BSSID"+i,
						new AccessPointFrequencies(999, 999,999,ScanResult.CHANNEL_WIDTH_20MHZ), 50, false));
			}
		}

		final RecyclerView listView = findViewById(R.id.listview);
		final boolean isOnWantedFreq = activeNetwork != null && WifiUtils.isWantedFrequency(this, activeNetwork.getFrequency());
		final CustomWifiListAdapter listAdapter = new CustomWifiListAdapter(listNetworks, wifiManager);
		listView.setAdapter(listAdapter);
		listView.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
		listView.addItemDecoration(new MaterialDividerItemDecoration(this, MaterialDividerItemDecoration.VERTICAL));
		listView.setHasFixedSize(true);
		listView.setOnDragListener(new ListDragListener(listAdapter));

		// and display the message box
		if (checkSelfPermission(ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
			showPermissionsError();
		} else if (!listNetworks.isEmpty()) {
			((MaterialCardView)findViewById(R.id.nowificardview)).setCardBackgroundColor(getResources().getColor(android.R.color.holo_green_dark, getTheme()));
			findViewById(R.id.nowificardview).setVisibility(VISIBLE);
			final TextView noWifiTextview = findViewById(R.id.nowifitextview);
			if(isManualMode()) {
				noWifiTextview.setText(R.string.text_wifimanualmode);
			} else {
				if(isOnWantedFreq) {
					noWifiTextview.setText(R.string.text_wififrequencyok);
				} else {
					noWifiTextview.setText(R.string.text_wififound);
				}
			}
			findViewById(R.id.cardSwitchManualModeBtn).setVisibility(GONE);
		} else { // otherwise: we found networks, but not the appropriate (2.4/5)
			((MaterialCardView)findViewById(R.id.nowificardview)).setCardBackgroundColor(getResources().getColor(R.color.design_default_color_primary_dark, getTheme()));
			findViewById(R.id.nowificardview).setVisibility(VISIBLE);
			((TextView) findViewById(R.id.nowifitextview)).setText(R.string.text_nowififound);
			findViewById(R.id.cardSwitchManualModeBtn).setVisibility(manualMode ? GONE : VISIBLE);
		}
	}

	void showMessage(@StringRes int id) {
		Snackbar.make(MainActivity.this.findViewById(R.id.mainFragment), id, Snackbar.LENGTH_LONG).setTextMaxLines(3).show(); // otherwise use Toast
	}

	void showMessage(String msg) {
		Snackbar.make(MainActivity.this.findViewById(R.id.mainFragment), msg, Snackbar.LENGTH_LONG).setTextMaxLines(3).show(); // otherwise use Toast
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
		findViewById(R.id.nowificardview).setVisibility(View.VISIBLE);
		((TextView) findViewById(R.id.nowifitextview)).setText(R.string.error_no_permissions_provived);
		findViewById(R.id.cardSwitchManualModeBtn).setVisibility(GONE);
	}

	private void showAbout() {
		final AlertDialog dialog = new MaterialAlertDialogBuilder(this)
				.setTitle(getString(R.string.app_name))
				.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						dialog.cancel();
					}
				})
				.setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) { // deactive Crashlytics
						PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).edit().
								putBoolean(getString(R.string.prefs_crashlytics), false).apply();
						dialog.cancel();
					}
				})
				.setNeutralButton(R.string.text_settings, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						startActivity(new Intent(getApplicationContext(), SettingsActivity.class));
						dialog.cancel();
					}
				})
				.setMessage(Html.fromHtml(getString(R.string.message_welcome), Html.FROM_HTML_MODE_COMPACT)).create();
		dialog.show();
		TextView view = (TextView) dialog.findViewById(android.R.id.message);
		if (view != null)
			view.setMovementMethod(LinkMovementMethod.getInstance()); // make links clickable
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

	private void showWhyNotWorking() {
		final MaterialAlertDialogBuilder dialogBuilder = new MaterialAlertDialogBuilder(this)
				.setTitle(getString(R.string.app_name))
				.setNeutralButton(R.string.text_wifi, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						dialog.cancel();
						try {
							startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
						} catch(ActivityNotFoundException e) {
							startActivity(new Intent(Settings.Panel.ACTION_WIFI));
						}
					}
				})
				.setPositiveButton(R.string.text_settings, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						dialog.cancel();
						startActivity(new Intent(getApplicationContext(), SettingsActivity.class));
					}
				})
				.setMessage(Html.fromHtml(getString(R.string.text_not_working), Html.FROM_HTML_MODE_COMPACT));
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

	private boolean isAggressive() {
		return PreferenceManager.getDefaultSharedPreferences(this).getBoolean(getString(R.string.prefs_aggressive_change), true);
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
					Crashlytics.recordException(e);
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
	boolean isManualMode() {
		return !PreferenceManager.getDefaultSharedPreferences(this).getBoolean(getString(R.string.prefs_activation), false);
	}
	boolean toggleManualMode() {
		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		final String key = getString(R.string.prefs_activation);
		boolean mode = !prefs.getBoolean(key, false);
		prefs.edit().putBoolean(key, mode).apply();
		return mode;
	}

	boolean isCrashlyticsEnabled() {
		return PreferenceManager.getDefaultSharedPreferences(this).getBoolean(getString(R.string.prefs_crashlytics), true);
	}
}
