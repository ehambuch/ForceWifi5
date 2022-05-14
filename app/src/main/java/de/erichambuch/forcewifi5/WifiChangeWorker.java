package de.erichambuch.forcewifi5;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.ACCESS_WIFI_STATE;
import static android.Manifest.permission.CHANGE_WIFI_STATE;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * Wrapper around {@link WifiChangeService}.
 */
@RequiresApi(api = Build.VERSION_CODES.O)
public class WifiChangeWorker extends Worker {

    public WifiChangeWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @NonNull
    @Override
    public Result doWork() {
        Log.i(AppInfo.APP_NAME, "Start WifiChangeWorker");
        // and use original service to perform logic
        final WifiChangeService service = new WifiChangeService(getApplicationContext());
        // move Worker to foreground
        setForegroundAsync(new ForegroundInfo(
                WifiChangeService.ONGOING_NOTIFICATION_ID, service.createNotification(R.string.title_activation)));
        boolean success = false;
        if(service.isActivated()) {
            if(ActivityCompat.checkSelfPermission(getApplicationContext(), ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    && ActivityCompat.checkSelfPermission(getApplicationContext(), CHANGE_WIFI_STATE) == PackageManager.PERMISSION_GRANTED
                    && ActivityCompat.checkSelfPermission(getApplicationContext(), ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED) {
                try {
                    service.updateNetworks();
                    success = true;
                } catch(Exception e) {
                    Log.e(AppInfo.APP_NAME, "Worker: updateNetworks", e);
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