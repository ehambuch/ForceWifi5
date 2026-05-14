package de.erichambuch.forcewifi5;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.ACCESS_WIFI_STATE;
import static android.Manifest.permission.CHANGE_NETWORK_STATE;
import static android.Manifest.permission.NEARBY_WIFI_DEVICES;
import static android.Manifest.permission.POST_NOTIFICATIONS;

import static de.erichambuch.forcewifi5.MainActivity.isLocationServicesEnabled;

import android.Manifest;
import android.app.ActivityManager;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Html;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

public class SetupActivity extends AppCompatActivity implements ActivityCompat.OnRequestPermissionsResultCallback {

    private static final int REQUEST_CODE_PERMISSIONS = 4711;

    protected int currentStep = 0;
    private TextView titleTextView;
    private TextView descriptionTextView;
    private Button btnPrevious;
    private Button btnNext;

    private ImageView iconLogo;

    private final ActivityResultLauncher<Intent> batteryRestrictionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                moveForward();
            }
    );

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);

        iconLogo = findViewById(R.id.setup_logo);
        titleTextView = findViewById(R.id.setup_title);
        descriptionTextView = findViewById(R.id.setup_description);
        btnPrevious = findViewById(R.id.btn_previous);
        btnNext = findViewById(R.id.btn_next);

        btnNext.setOnClickListener(v -> {
            moveForward();
        });

        btnPrevious.setOnClickListener(v -> {
            moveBackward();
        });

        updateUI();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            boolean granted = false;
            for (int grant : grantResults) {
                if (grant != PackageManager.PERMISSION_GRANTED) {
                    granted = true;
                } else {
                    granted = false;
                    currentStep--;
                    return;
                }
            }
            if(granted)
                moveForward();
            else
                moveBackward();
        }
    }

    private void updateUI() {
        switch (currentStep) {
            case 0:
                titleTextView.setText(R.string.setup_title_1);
                descriptionTextView.setText(Html.fromHtml(getString(R.string.setup_text_1), Html.FROM_HTML_MODE_COMPACT));
                btnPrevious.setVisibility(View.INVISIBLE);
                iconLogo.setImageResource(R.drawable.wifi_24px);
                break;
            case 1:
                titleTextView.setText(R.string.setup_title_2);
                descriptionTextView.setText(Html.fromHtml(getString(R.string.setup_text_2), Html.FROM_HTML_MODE_COMPACT));
                btnPrevious.setVisibility(View.VISIBLE);
                iconLogo.setImageResource(R.drawable.location_on_24px);
                break;
            case 2:
                requestLocationPermission();
                break;
            case 3:
                titleTextView.setText(R.string.setup_title_3);
                descriptionTextView.setText(Html.fromHtml(getString(R.string.setup_text_3), Html.FROM_HTML_MODE_COMPACT));
                btnPrevious.setVisibility(View.VISIBLE);
                iconLogo.setImageResource(R.drawable.wifi_notification_24px);
                break;
            case 4:
                requestNotificationPermission();
                break;
            case 5:
                titleTextView.setText(R.string.setup_title_4);
                descriptionTextView.setText(Html.fromHtml(getString(R.string.setup_text_4), Html.FROM_HTML_MODE_COMPACT));
                btnPrevious.setVisibility(View.VISIBLE);
                iconLogo.setImageResource(R.drawable.location_searching_24px);
                break;
            case 6:
                requestLocationServices();
                break;
            case 7:
                titleTextView.setText(R.string.setup_title_5);
                descriptionTextView.setText(Html.fromHtml(getString(R.string.setup_text_5), Html.FROM_HTML_MODE_COMPACT));
                btnPrevious.setVisibility(View.VISIBLE);
                iconLogo.setImageResource(R.drawable.rewarded_ads_24px);
                btnNext.setText(R.string.text_finish);
                break;
        }
    }

    private void requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            final String[] permissions;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions = new String[]{
                        ACCESS_WIFI_STATE, CHANGE_NETWORK_STATE, NEARBY_WIFI_DEVICES, ACCESS_FINE_LOCATION
                };
            } else {
                permissions = new String[]{
                        ACCESS_WIFI_STATE, CHANGE_NETWORK_STATE, ACCESS_FINE_LOCATION
                };
            }
            ActivityCompat.requestPermissions(this,
                    permissions,
                    REQUEST_CODE_PERMISSIONS);
        } else
            moveForward();
    }

    protected void moveForward() {
        if (currentStep < 7) {
            currentStep++;
            updateUI();
        } else {
            PreferenceManager.getDefaultSharedPreferences(this).edit().putBoolean(getString(R.string.prefs_setup), false).apply();
            finish(); // Setup complete
        }
    }

    protected void moveBackward() {
        if (currentStep > 0) {
            currentStep--;
            updateUI();
        }
    }

    private void requestNotificationPermission() {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{POST_NOTIFICATIONS},
                        REQUEST_CODE_PERMISSIONS);
            } else
                moveForward();
        } else {
            moveForward(); // just forward don't need
        }
        // and check if Notifications are enabled for this app
        if(!getSystemService(NotificationManager.class).areNotificationsEnabled()) {
            Intent settingsIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            settingsIntent.setData(Uri.parse("package:" + getPackageName()));
            settingsIntent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
            startActivity(settingsIntent);
        }
    }

    private void requestLocationServices() {
        moveForward();
        if (!isLocationServicesEnabled(this)) {
            try {
                Intent settingsIntent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                settingsIntent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
                startActivity(settingsIntent);
            } catch(ActivityNotFoundException e) {
                Toast.makeText(this, R.string.error_not_supported, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void checkBatteryRestriction() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        final boolean batteryOptimizationIgnored = pm.isIgnoringBatteryOptimizations(getPackageName());
        final boolean backgroundRestricted = am.isBackgroundRestricted();
        if ((!batteryOptimizationIgnored || backgroundRestricted)) {
            Intent intent = new Intent();
            if (!batteryOptimizationIgnored) {
                // kein REQUEST... nutzen, weil lt. Google Policy verboten
                intent.setAction(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                intent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
            } else { // Background Restriction -> Open App settings
                intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setPackage(getApplicationContext().getPackageName());
                intent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
            }
            batteryRestrictionLauncher.launch(intent);
        } else {
            moveForward();
        }
    }

    /**
     * Get list of required Wifi permissions for app.
     * @return the list of required permissions
     * @see <a href="https://developer.android.com/develop/connectivity/wifi/wifi-permissions?hl=de">...</a>
     */
    public static @NonNull String[] getRequiredAppPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{
                    ACCESS_WIFI_STATE, CHANGE_NETWORK_STATE, NEARBY_WIFI_DEVICES, ACCESS_FINE_LOCATION, POST_NOTIFICATIONS
            };
        } else {
            return new String[]{
                    ACCESS_WIFI_STATE, CHANGE_NETWORK_STATE, ACCESS_FINE_LOCATION
            };
        }
    }
}
