package de.erichambuch.forcewifi5;

/**
 * <b>ForceWifi5</b>
 * <p>Author: Eric Hambuch (<a href="mailto:erichambuch+apps@googlemail.com">erichambuch+apps@googlemail.com</a>)
 * Licence: <a href="https://www.apache.org/licenses/LICENSE-2.0">Apache 2.0</a>
 * Uses: <a href="https://material.io">Material Design</a>
 * </p>
 * <ul>
 *     <li>V1.0 (25.10.2020) - erste Fassung</li>
 *     <li>V1.1 () - Improved settings and permission notifications</li>
 *     <li>V1.2 (5.11.2020) - Improved (foreground)service handling for Android O and above</li>
 *     <li>V1.3 (25.11.2020) - added notification if no 2/5 network is available</li>
 *     <li>V1.4 (16.01.2021) - Workaround for Android problems with Stackoverflow recommandations</li>
 *     <li>V1.5.1 (21.03.2021) - Handling multiple access points, Update Android libs, Warning for pre-Android 10 versions (does not work reliably)</li>
 *     <li>V1.6 (22.03.2021) - Support for switching to other SSID</li>
 *     <li>V1.6.1 (23.03.2021) - Bugfix Activation</li>
 *     <li>V1.7.0 (07.07.2021) - Update Google Libs</li>
 *     <li>V1.7.1 (08.08.2021) - Fehlerkorrektur bei Android kl. 8 (Endlosschleife beim Wechsel), Swipe-to-refresh in ListView, Infofenster</li>
 *     <li>V1.7.2 (05.10.2021) - Update Google Libs, Starttext angepasst, Fehlermeldungen überarbeitet</li>
 *     <li>V1.7.3 (06.11.2021) - Fix problem at boot</li>
 *     <li>V1.8.0 (04.12.2021) - Update Libs, Android 12</li>
 *     <li>V1.9.0 (14.01.2022) - OSS Licenses, UI Updates, Dark Mode</li>
 *     <li>V1.9.1 (24.01.2022) - Adjustments für Android 11</li>
 *     <li>V1.9.2 (25.01.2022) - Bugfix for Android 12 (Battery Optimizations), Fixes Wifi-Suggestion logic</li>
 *     <li>V1.10.0 (02.02.2022) - Improved Settings, Tried fixes for Foreground-Service-problem in Android 12</li>
 *     <li>V1.11.0 (16.02.2022) - Now uses WorkManager for Android 12, Fix Exception in WifiChangeService.onDestroy()</li>
 *     <li>V1.12.0 (08.05.2022) - Now displays connected Wifi as well as recommended wifi channel</li>
 *     <li>V1.12.1 (08.05.2022) - Bugfixes for connection identification</li>
 *     <li>V1.13.0 (14.05.2022) - Added buttom to switch wifi on/off, added widget</li>
 *     <li>V1.13.1 (16.05.2022) - Update Widget</li>
 *     <li>V1.13.2 (17.05.2022) - Improvements for network detection and widget</li>
 *     <li>V1.13.3 (07.06.2022) - Update Libs, ProGuard</li>
 *     <li>V1.13.4 (22.07.2022) - Update Google Libs</li>
 *     <li>V1.13.5 (25.07.2022) - Bugfix Feedbackmail</li>
 *     <li>V1.14.0 (14.08.2022) - Replace Feedback button by link to Play Store to avoid spamming, Migration Android API 32</li>
 *     <li>V1.14.1 (12.09.2022) - Android API 33</li>
 *     <li>V1.14.2 (07.10.2022) - Fixes for Android 12 (WorkManager)</li>
 *     <li>V1.15.0 (10.4.2023) - Updated Libs, More explanation on permissions</li>
 *     <li>V1.16.0 (08.06.2023) - changed permission/location/notification UI flows, displays additional infos, Android 13 tested</li>
 *     <li>V1.16.1 (10.06.2023) - Support for 6 GHz, Updated permission workflow</li>
 *     <li>V1.17.0 (17.06.2023) - Polish translation provided by Marek Bogacz, Spanish/French by automatic Google translation</li>
 *     <li>V1.18.0 (18.08.2023) - Update Android 14</li>
 *     <li>V1.19.0 (02.12.2023) - Android 14 Permission Update (new developer policy, no automatic switch on Android14+ from background), Material 3</li>
 *     <li>V1.19.1 (07.12.2023) - Several bug fixes, new Navigation Bar</li>
 *     <li>V1.20.0 (09.12.2023) - Improved Layout</li>
 *     <li>V1.21.0 (10.12.2023) - AdMob integration, Manual suggestion mode</li>
 *     <li>V1.21.1 (24.12.2023) - AdMob deactivated by default to comply with Google policies</li>
 *     <li>V1.21.2(50/51) (26.12.2024) - Bugfix: main layout issues, activated AdMob</li>
 *     <li>V1.22.0(52) (13.02.2024) - Added Overview for Vendor Overlay Configuration of Wifi Manager</li>
 *     <li>V1.22.1(53) (17.03.2024) - Bugfixes</li>
 *     <li>V1.23.0(54) (13.04.2024) - RequestNetwork for more aggressive network change, Google Crashlytics, 60 GHz Support</li>
 *     <li>V1.24.0(55) (21.04.2024) - added channel width, fixed first crashes for aggressiveNetworkChange()</li>
 *     <li>V1.25.0(56) (21.04.2024) - Reworked permissions for Android13+, added experimental option to define channels</li>
 *     <li>V1.25.1(57) (24.04.2024) - fixed crashes reported by Crashlytics</li>
 *     <li>V1.25.2(58) (26.04.2024) - fixed crashes reported by Crashlytics</li>
 *     <li>V1.25.3(60) (26.04.2024) - fixed crashes reported by Crashlytics, Improved Notifications</li>
 *     <li>V1.25.4(61) (07.06.2024) - More Crashlytics, minor fixes</li>
 *     <li>V1.26.0(62) (21.11.2024) - Removed AdMob</li>
 *     <li>V1.26.1(63) (01.01.2025) - Updated startup message, fixes for Crashlytics</li>
 *     <li>V1.27.0(64/65 (02.07.2025) - Update Android 15, Bugfix Overview Wifi List to handle multiple access points</li>
 *     <li>V1.28.0 (68) (29.12.2025) - Update Android 16</li>
 * </ul>
 * Open Issues to be fixed:
 * <ul>
 *     <li>TODO: disconnect does not work anymore: <a href="https://issuetracker.google.com/issues/128554616">Issue 128554616</a></li>
 *     <li>TODO: make a guided setup process for all the settings</li>
 *     <li><a href="https://developer.android.com/training/location/permissions#background">Problems with Foreground/Background</a></li>
 *     <li><a href="https://issuetracker.google.com/issues/192032398?pli=1#comment6">Issue 192032398</a></li>
 *     <li><a href="https://stackoverflow.com/questions/44425584/context-startforegroundservice-did-not-then-call-service-startforeground/46449975#46449975">Stackoverflow</a></li>
 *     <li><a href="https://stackoverflow.com/questions/63124728/connect-to-wifi-in-android-q-programmatically">Stackoverflow</a></li>
 *     <li><a href="https://source.android.com/docs/core/connect/wifi-network-selection">Google Android Source</a></li>
 * </ul>
 */
public final class AppInfo {

    public static final String APP_NAME = "ForceWifi5";
    public static final String INTENT_SHOW_DATAPROCTECTION = "de.erichambuch.forcewifi5.VIEW_DATA_PROTECTION";
    public static final String INTENT_SHOW_MARKET = "de.erichambuch.forcewifi5.VIEW_MARKET";
    public static final String INTENT_SHOW_FAQ = "de.erichambuch.forcewifi5.VIEW_FAQ";

    public static final String INTENT_SHOW_OVERLAY = "de.erichambuch.forcewifi5.SHOW_OVERLAY";
}
