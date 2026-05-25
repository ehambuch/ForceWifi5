package de.erichambuch.forcewifi5;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresPermission;

/**
 * BroadcastReceiver that is executed on startup of phone.
 * Allows us to register listener for Wifi network changes.
 * <p>Starting with Android 13, this is only called if App is started!</p>
 */
public class StartOnBootReceiver extends android.content.BroadcastReceiver {

	@Override
	@RequiresPermission(value = "android.permission.ACCESS_NETWORK_STATE")
	public void onReceive(final Context context, @NonNull Intent intent) {
		Log.i(AppInfo.APP_NAME, "StartOnBootReceiver started");
		if(Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) || "android.intent.action.QUICKBOOT_POWERON".equals(intent.getAction())) {
			MainActivity.createNotificationChannel(context);
			// register a listener to network changes
			ForceApplication app = (ForceApplication) context.getApplicationContext();
			app.registerGlobalNetworkCallback();
		}
	}

}
