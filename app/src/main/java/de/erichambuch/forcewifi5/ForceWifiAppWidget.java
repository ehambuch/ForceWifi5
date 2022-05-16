package de.erichambuch.forcewifi5;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.widget.RemoteViews;

import androidx.preference.PreferenceManager;

import java.math.BigDecimal;

/**
 * Application widget just showing the current frequency.
 */
public class ForceWifiAppWidget extends AppWidgetProvider {

    public void updateAppWidget(Context context, AppWidgetManager appWidgetManager,
                                int appWidgetId) {
        final WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        final CharSequence widgetText;
        final int widgetColor;
        if (wifiManager.isWifiEnabled()) {
            int frequency = wifiManager.getConnectionInfo().getFrequency();
            widgetText = BigDecimal.valueOf(frequency).divide(BigDecimal.valueOf(1000)) + " GHz";
            widgetColor = isWantedFrequency(context, frequency) ? context.getResources().getColor(android.R.color.holo_green_light, context.getTheme()) :
                    context.getResources().getColor(android.R.color.holo_red_light, context.getTheme());
        } else {
            widgetColor = context.getResources().getColor(android.R.color.darker_gray, context.getTheme());
            widgetText = context.getString(R.string.title_deacttived);
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

    public boolean isWantedFrequency(Context context, int freq) {
        if (is6GHzPreferred(context)) {
            return (freq >= 5925);
        } else if (is5GHzPreferred(context))
            return (freq >= 5000 && freq <= 5920);
        else
            return (freq < 3000);
    }
}