package de.erichambuch.forcewifi5;

/**
 * ForceWifi5.
 * <p>Author: Eric Hambuch (erichambuch@googlemail.com)
 * Licence: Apache 2.0
 * Uses: Material Design
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
 * </ul>
 * <ul>
 *     <li>TODO: ACCESS_BACKGROUND_LOCATION for Android 11?</li>
 *     <li>TODO: disconnect geht nicht mehr: https://issuetracker.google.com/issues/128554616</li>
 * </ul>
 */
public class AppInfo {
    public static final String APP_NAME = "ForceWifi5";
}
