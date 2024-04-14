package de.erichambuch.forcewifi5;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.crashlytics.FirebaseCrashlytics;

public final class Crashlytics {

    public static void recordException(@NonNull Throwable e) {
        FirebaseCrashlytics.getInstance().recordException(e);
        Log.e(AppInfo.APP_NAME, "Internal crash record", e);
    }

    public static void recordException(@NonNull Exception e) {
        FirebaseCrashlytics.getInstance().recordException(e);
        Log.e(AppInfo.APP_NAME, "Internal crash record", e);
    }
}