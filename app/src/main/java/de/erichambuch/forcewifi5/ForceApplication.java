package de.erichambuch.forcewifi5;

import static de.erichambuch.forcewifi5.MainActivity.startWifiService;
import static de.erichambuch.forcewifi5.WifiChangeService.ONGOING_NOTIFICATION_ID;
import static de.erichambuch.forcewifi5.WifiChangeService.isAutomaticMode;

import android.app.Activity;
import android.app.Application;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Application class.
 */
public class ForceApplication extends Application {

    /**
     * Circuit breaker - shared by different listeners.
     */
    @NonNull
    protected static final AtomicLong lastNetworkCallback = new AtomicLong(0);

    private WeakReference<Activity> currentActivity = new WeakReference<>(null);

    /**
     * Network callback: We use this on enabling of a network to initiate a change of the network if required.
     */
    public static class NetworkCallback extends ConnectivityManager.NetworkCallback {

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
                if(isAutomaticMode(myContext)) {
                    startWifiService(myContext);
                }
                updateUI();
            } else
                Log.d(AppInfo.APP_NAME, "Skipped NetworkCallBack");

            updateWidget();
        }

        @Override
        public void onLost(@NonNull Network network) {
            Log.d(AppInfo.APP_NAME, "Networkcallback onLost");
            updateWidget();
            updateUI();
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
                if(isAutomaticMode(myContext))
                    startWifiService(myContext);
                updateUI();
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
                if(isAutomaticMode(myContext))
                    startWifiService(myContext);
                updateUI();
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

        private void updateUI() {
            // only update UI if our main activity is running
            Activity activity = ((ForceApplication) myContext.getApplicationContext()).getCurrentActivity();
            if (activity instanceof MainActivity) {
                try {
                    if(!activity.isFinishing() && !activity.isDestroyed()) {
                        activity.runOnUiThread(() -> ((MainActivity) activity).listNetworks());
                    }
                } catch (SecurityException e) {
                    Log.w(AppInfo.APP_NAME, e);
                }
            }
        }
    }

    private NetworkCallback networkCallback;


    @Override
    public void onCreate() {
        super.onCreate();
        // This lifecycle listener checks if our MainActivity is running or not
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {}
            @Override
            public void onActivityStarted(@NonNull Activity activity) {}
            @Override
            public void onActivityResumed(@NonNull Activity activity) {
                if(activity instanceof MainActivity )
                    currentActivity = new WeakReference<>(activity);
            }
            @Override
            public void onActivityPaused(@NonNull Activity activity) {
                if (activity instanceof MainActivity) {
                    currentActivity.clear();
                }
            }
            @Override
            public void onActivityStopped(@NonNull Activity activity) {}
            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {}
            @Override
            public void onActivityDestroyed(@NonNull Activity activity) {}
        });
        registerGlobalNetworkCallback();
    }

    @Nullable
    public Activity getCurrentActivity() {
        return currentActivity.get();
    }

    @Override
    public void onTerminate() {
        try {
            if (networkCallback != null) {
                ConnectivityManager connManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                connManager.unregisterNetworkCallback(networkCallback);
            }
        } finally {
            super.onTerminate();
        }
    }

    protected synchronized void registerGlobalNetworkCallback() {
        if(networkCallback == null) {
            // register a listener to network changes (this may occure twice if already done in StartOnBootReceiver!)
            ConnectivityManager connManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            connManager.registerNetworkCallback(
                    new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                    networkCallback = new NetworkCallback(getApplicationContext()));
            Log.i(AppInfo.APP_NAME, "Network callback registered");
        }
    }

}
