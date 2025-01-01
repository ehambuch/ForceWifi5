package de.erichambuch.forcewifi5;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.crashlytics.FirebaseCrashlytics;

public final class Crashlytics {

    public static void recordException(@NonNull Exception e) {
        try {
            Log.e(AppInfo.APP_NAME, "Internal crash record", e);
            FirebaseCrashlytics.getInstance().recordException(e);
        } catch(RuntimeException e2) {
            // ignore
        }
    }
}