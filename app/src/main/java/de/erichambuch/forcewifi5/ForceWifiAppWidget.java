package de.erichambuch.forcewifi5;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.util.Log;
import android.widget.RemoteViews;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Application widget just showing the current frequency.
 */
public class ForceWifiAppWidget extends AppWidgetProvider {

    private volatile ConnectivityManager.NetworkCallback networkCallback = null;

    public void onEnabled(Context context) {
        super.onEnabled(context);
        // here we have a change to register a network listener with Android >9
        ConnectivityManager connManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        connManager.registerNetworkCallback(
                new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                networkCallback = new MainActivity.NetworkCallback(context.getApplicationContext()));
    }

    public void onDisabled(Context context) {
        if(networkCallback != null) {
            ConnectivityManager connManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            connManager.unregisterNetworkCallback(networkCallback);
        }
    }

    public void updateAppWidget(@NonNull Context context, @NonNull AppWidgetManager appWidgetManager,
                                int appWidgetId) {
        Log.d(AppInfo.APP_NAME, "Update widget");
        final WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        CharSequence widgetText = context.getString(R.string.title_deacttived);
        int widgetColor = context.getResources().getColor(android.R.color.darker_gray, context.getTheme());
        if (wifiManager.isWifiEnabled()) {
            int frequency = wifiManager.getConnectionInfo().getFrequency();
            if (frequency > 0) {
                widgetText = BigDecimal.valueOf(frequency).divide(BigDecimal.valueOf(1000), RoundingMode.HALF_UP).setScale(3, RoundingMode.HALF_UP) + " GHz";
                widgetColor = isWantedFrequency(context, frequency) ? context.getResources().getColor(android.R.color.holo_green_light, context.getTheme()) :
                        context.getResources().getColor(android.R.color.holo_red_light, context.getTheme());
            } else { // e.g. mission permissionss
                widgetText = context.getString(R.string.title_unknown);
            }
        }
        Intent intent = new Intent(context, MainActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 0,
                intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.force_wifi_app_widget);
        views.setTextViewText(R.id.appwidget_text, widgetText);
        views.setTextColor(R.id.appwidget_text, widgetColor);
        views.setOnClickPendingIntent(R.id.appwidget_text, pendingIntent);

        appWidgetManager.updateAppWidget(appWidgetId, views);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId);
        }
    }

    // TODO duplicate code
    private boolean is5GHzPreferred(Context context) {
        return ("1".equals(PreferenceManager.getDefaultSharedPreferences(context).getString(context.getString(R.string.prefs_2ghz5ghz), "1")));
    }

    private boolean is6GHzPreferred(Context context) {
        return ("2".equals(PreferenceManager.getDefaultSharedPreferences(context).getString(context.getString(R.string.prefs_2ghz5ghz), "1")));
    }

    public boolean isWantedFrequency(@NonNull Context context, int freq) {
        if (is6GHzPreferred(context)) {
            return (freq >= 5925);
        } else if (is5GHzPreferred(context))
            return (freq >= 5000 && freq <= 5920);
        else
            return (freq < 3000);
    }
}