package de.erichambuch.forcewifi5;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.ACCESS_WIFI_STATE;
import static android.Manifest.permission.CHANGE_WIFI_STATE;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * Wrapper around {@link WifiChangeService}.
 */
public class WifiChangeWorker extends Worker {

    public WifiChangeWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.i(AppInfo.APP_NAME, "Start WifiChangeWorker");
        // and use original service to perform logic
        WifiChangeService service = null;
        // move Worker to foreground
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                service = new WifiChangeService14(getApplicationContext());
                Log.i(AppInfo.APP_NAME, "Cannot move to Foreground with Android14+, try anyway");
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                service = new WifiChangeService(getApplicationContext());
                setForegroundAsync(new ForegroundInfo(
                        WifiChangeService.ONGOING_NOTIFICATION_ID, WifiChangeService.createMessageNotification(getApplicationContext(), R.string.title_activation), FOREGROUND_SERVICE_TYPE_LOCATION));
            } else {
                service = new WifiChangeService(getApplicationContext());
                setForegroundAsync(new ForegroundInfo(
                        WifiChangeService.ONGOING_NOTIFICATION_ID, WifiChangeService.createMessageNotification(getApplicationContext(), R.string.title_activation)));
            }
        } catch (Exception e) {
            Crashlytics.recordException(e);
        }

        boolean success = true;
        if(service != null && service.isActivated()) {
            if(ActivityCompat.checkSelfPermission(getApplicationContext(), ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    && ActivityCompat.checkSelfPermission(getApplicationContext(), CHANGE_WIFI_STATE) == PackageManager.PERMISSION_GRANTED
                    && ActivityCompat.checkSelfPermission(getApplicationContext(), ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED) {
                try {
                    success = false;
                    service.updateNetworks();
                    success = true;
                } catch(Exception e) {
                    Crashlytics.recordException(e);
                    service.showPermissionError();
                }
            } else {
                Log.e(AppInfo.APP_NAME, "Worker: Permissions missing");
                service.showPermissionError();
            }
        }
        return success ? Result.success() : Result.failure();
    }
}
