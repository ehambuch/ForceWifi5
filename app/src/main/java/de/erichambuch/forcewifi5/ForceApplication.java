package de.erichambuch.forcewifi5;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.work.Configuration;
import androidx.work.WorkManager;

import com.google.android.material.color.DynamicColors;

/**
 * @see <a href="https://developer.android.com/topic/libraries/architecture/workmanager/advanced/custom-configuration">...</a>
 */
public class ForceApplication extends Application implements Configuration.Provider {

    @Override
    public void onCreate() {
        super.onCreate(); // addresses setup problems with Workmanager on Android 12+
        try {
            WorkManager.initialize(this, getWorkManagerConfiguration());
        } catch(Exception e) {
            Crashlytics.recordException(e);
        }
        DynamicColors.applyToActivitiesIfAvailable(this); // for setup of Material 3
    }

    @NonNull
    @Override
    public Configuration getWorkManagerConfiguration() {
        return new Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.INFO)
                .build();
    }

}
