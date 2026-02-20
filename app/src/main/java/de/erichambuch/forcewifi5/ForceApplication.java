package de.erichambuch.forcewifi5;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

/**
 * Not necessary anymore:
 * @see <a href="https://developer.android.com/topic/libraries/architecture/workmanager/advanced/custom-configuration">...</a>
 */
public class ForceApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this); // for setup of Material 3
    }

}
