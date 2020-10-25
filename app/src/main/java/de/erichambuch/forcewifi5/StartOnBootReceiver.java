package de.erichambuch.forcewifi5;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

/**
 * BroadcastReceiver that is executed on startup of phone.
 * Allows us to register listener for Wifi network changes.
 */
public class StartOnBootReceiver extends android.content.BroadcastReceiver {

	@Override
	public void onReceive(final Context context, Intent intent) {
		if(intent.getAction().equals(Intent.ACTION_BOOT_COMPLETED)) {
			// register a listener to network changes
			ConnectivityManager connManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
			connManager.registerNetworkCallback(
					new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
					new ConnectivityManager.NetworkCallback() {
					    @Override
					    public void onAvailable(Network network) {
					        context.startService(new Intent(context, WifiChangeService.class));
					    }
					});
		}
	}

}
