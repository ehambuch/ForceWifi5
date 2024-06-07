package de.erichambuch.forcewifi5;

import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Separate Service due to Android 14+ restrictions on using Foreground Services.
 */
public class WifiChangeService14 extends WifiChangeService {
    protected WifiChangeService14(@NonNull Context context) {
        super(context);
    }

    /**
     * Required default constructor.
     */
    public WifiChangeService14() {
        super();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { // not on Android 14
        return null;
    }
}