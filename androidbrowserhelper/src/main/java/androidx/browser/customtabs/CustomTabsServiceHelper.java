package androidx.browser.customtabs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;

/**
 * Helper to bind Custom Tabs services with proper application context setup.
 * Lives in the androidx.browser.customtabs package to access the package-private
 * setApplicationContext() method on CustomTabsServiceConnection.
 * <p>
 * Needed because TwaLauncher bypasses CustomTabsClient.bindCustomTabsService()
 * (which normally handles this) to resolve a specific ComponentName for
 * Xiaomi dual-app disambiguation.
 */
public class CustomTabsServiceHelper {

    private CustomTabsServiceHelper() {}

    /**
     * Binds the Custom Tabs service after setting the application context on the connection.
     */
    @SuppressLint("RestrictedApi")
    public static boolean bindService(Context context, Intent serviceIntent,
                                      CustomTabsServiceConnection connection, int flags) {
        connection.setApplicationContext(context.getApplicationContext());
        return context.bindService(serviceIntent, connection, flags);
    }
}
