package de.erichambuch.forcewifi5;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.ACCESS_WIFI_STATE;
import static android.Manifest.permission.CHANGE_WIFI_STATE;

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
        Context context = getApplicationContext();
        WifiController controller = new WifiController(context);

        if (!controller.isActivated()) {
            return Result.success();
        }

        // move Worker to foreground
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setForegroundAsync(new ForegroundInfo(
                        WifiChangeService.ONGOING_NOTIFICATION_ID, 
                        WifiChangeService.createMessageNotification(context, R.string.title_activation)));
            }
        } catch (Exception e) {
            Crashlytics.recordException(e);
        }

        boolean success = true;
        if (ActivityCompat.checkSelfPermission(context, ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(context, CHANGE_WIFI_STATE) == PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(context, ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED) {
            try {
                success = false;
                controller.updateNetworks();
                success = true;
            } catch (Exception e) {
                Crashlytics.recordException(e);
                controller.showPermissionError();
            }
        } else {
            Log.e(AppInfo.APP_NAME, "Worker: Permissions missing");
            controller.showPermissionError();
        }
        return success ? Result.success() : Result.failure();
    }
}
