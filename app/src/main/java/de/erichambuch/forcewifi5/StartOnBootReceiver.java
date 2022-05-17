package de.erichambuch.forcewifi5;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Log;

import androidx.annotation.RequiresPermission;

/**
 * BroadcastReceiver that is executed on startup of phone.
 * Allows us to register listener for Wifi network changes.
 */
public class StartOnBootReceiver extends android.content.BroadcastReceiver {

	@Override
	@RequiresPermission(value = "android.permission.ACCESS_NETWORK_STATE")
	public void onReceive(final Context context, Intent intent) {
		Log.i(AppInfo.APP_NAME, "StartOnBootReceiver started");
		if(Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) || "android.intent.action.QUICKBOOT_POWERON".equals(intent.getAction())) {
			MainActivity.createNotificationChannel(context);
			// register a listener to network changes
			ConnectivityManager connManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
			connManager.registerNetworkCallback(
					new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
					new MainActivity.NetworkCallback(context.getApplicationContext()));
			Log.i(AppInfo.APP_NAME, "Network callback registered");
		}
	}

}
