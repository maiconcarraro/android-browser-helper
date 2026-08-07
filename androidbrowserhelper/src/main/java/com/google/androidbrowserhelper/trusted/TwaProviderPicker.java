// Copyright 2019 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.androidbrowserhelper.trusted;

import static androidx.browser.customtabs.CustomTabsService.TRUSTED_WEB_ACTIVITY_CATEGORY;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.browser.customtabs.CustomTabsService;
import androidx.browser.customtabs.TrustedWebUtils;

/**
 * Chooses a Browser/Custom Tabs/Trusted Web Activity provider and appropriate launch mode.
 * All browsers on the users device are considered, in order of Android's preference (so the user's
 * default will come first). We look for browsers that support Trusted Web Activities first, then
 * ones that support Custom Tabs falling back to any browser if neither of those criteria are
 * matched.
 *
 * A browser is an app that has an Activity that answers the following Intent:
 * <pre>
 * new Intent()
 *         .setAction(Intent.ACTION_VIEW)
 *         .addCategory(Intent.CATEGORY_BROWSABLE)
 *         .setData(Uri.parse("http://"));
 * </pre>
 *
 * A Custom Tabs provider is an app that has a Service that answers the following Intent:
 * <pre>
 * new Intent()
 *         .setAction(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION);
 * </pre>
 *
 * A Trusted Web Activity provider is an app that has a Service that answers the following Intent:
 * <pre>
 * new Intent()
 *         .setAction(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION)
 *         .addCategory(CustomTabsService.TRUSTED_WEB_ACTIVITY_CATEGORY);
 * </pre>
 */
public class TwaProviderPicker {
    private static final String TAG = "TWAProviderPicker";
    private static String sPackageNameForTesting;
    private static final String[] preferredProviders = {
        "com.android.chrome", // CHROME
        "com.chrome.beta", // CHROME_BETA
        "com.chrome.dev", // CHROME_DEV
        "com.chrome.canary", // CHROME_CANARY
        "com.google.android.apps.chrome", // CHROME_LOCAL_BUILD
        "org.chromium.chrome", // CHROMIUM_LOCAL_BUILD
        "com.microsoft.emmx", // MICROSOFT_EDGE
    };

    /**
     * Well-known browsers (in preference order) probed directly by package name when
     * PackageManager intent queries return nothing — a transient system bug seen on Samsung
     * OneUI (https://github.com/TrianguloY/URLCheck/issues/350). Direct package lookups via
     * {@link PackageManager#getApplicationInfo} don't depend on intent resolution, so they
     * still work when the query path is broken. Values are the launch mode each browser is
     * known to support; these packages are declared as {@code <package>} queries in the
     * library manifest so they stay visible without intent-based visibility.
     */
    private static final Map<String, Integer> knownProviders = new LinkedHashMap<>();
    static {
        knownProviders.put("com.android.chrome", LaunchMode.TRUSTED_WEB_ACTIVITY);
        knownProviders.put("com.chrome.beta", LaunchMode.TRUSTED_WEB_ACTIVITY);
        knownProviders.put("com.chrome.dev", LaunchMode.TRUSTED_WEB_ACTIVITY);
        knownProviders.put("com.chrome.canary", LaunchMode.TRUSTED_WEB_ACTIVITY);
        knownProviders.put("com.microsoft.emmx", LaunchMode.TRUSTED_WEB_ACTIVITY);
        knownProviders.put("com.sec.android.app.sbrowser", LaunchMode.TRUSTED_WEB_ACTIVITY);
        knownProviders.put("org.mozilla.firefox", LaunchMode.CUSTOM_TAB);
        knownProviders.put("com.brave.browser", LaunchMode.CUSTOM_TAB);
        knownProviders.put("com.opera.browser", LaunchMode.CUSTOM_TAB);
    }

    @IntDef({LaunchMode.TRUSTED_WEB_ACTIVITY, LaunchMode.CUSTOM_TAB, LaunchMode.BROWSER})
    @Retention(RetentionPolicy.SOURCE)
    public @interface LaunchMode {
        /** The webapp should be launched as a Trusted Web Activity. */
        int TRUSTED_WEB_ACTIVITY = 0;
        /** The webapp should be launched as a simple (no Session) Custom Tab. */
        int CUSTOM_TAB = 1;
        /** The webapp should be opened in the browser. */
        int BROWSER = 2;
    }

    /**
     * The result of {@link #pickProvider}, holding the launch mode and package (which may be null
     * when the launchMode is BROWSER).
     */
    public static class Action {
        /** How the webapp should be launched. */
        @LaunchMode
        public final int launchMode;
        /** The provider package name, may be null when {@code launchMode == BROWSER}. */
        @Nullable
        public final String provider;

        /** Creates this object with the given parameters. */
        public Action(@LaunchMode int launchMode, @Nullable String provider) {
            this.launchMode = launchMode;
            this.provider = provider;
        }
    }

    /**
     * Chooses an appropriate provider (see class description) and the launch mode that browser
     * supports.
     */
    public static Action pickProvider(PackageManager pm) {
        return pickProvider(pm, null);
    }

    /**
     * Same as {@link #pickProvider(PackageManager)}, but ignores the given packages. Used to find
     * an alternative browser when the preferred one is installed but couldn't be reached (e.g.
     * Chrome in Samsung deep sleep). When the preferred browser is excluded, selection naturally
     * falls back to the user's default browser, even if it isn't in the preferred providers list.
     *
     * @param excludePackages Browser packages to skip, or {@code null} to consider all of them.
     */
    public static Action pickProvider(PackageManager pm, @Nullable Set<String> excludePackages) {
        // Setting the Intent Data as seen at
        // https://cs.android.com/android/platform/superproject/+/fd994cf9ef8207ad03dc3a1d831e9263ddfd4469:packages/apps/PermissionController/src/com/android/packageinstaller/role/model/BrowserRoleBehavior.java
        Intent queryBrowsersIntent = new Intent()
                .setAction(Intent.ACTION_VIEW)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setData(Uri.fromParts("http", "", null));
        if (sPackageNameForTesting != null) {
            queryBrowsersIntent.setPackage(sPackageNameForTesting);
        }

        String bestCctProvider = null;
        String bestBrowserProvider = null;

        // These packages will be in order of Android's preference.
        List<ResolveInfo> possibleProviders
                = pm.queryIntentActivities(queryBrowsersIntent, PackageManager.MATCH_DEFAULT_ONLY);

        // According to the documentation, the flag we want to use above is MATCH_DEFAULT_ONLY.
        // This would match all the browsers installed on the user's system whose intent handler
        // contains the category Intent.CATEGORY_DEFAULT. However, in Android M the behavior of
        // the PackageManager changed to only return the default browser unless the MATCH_ALL is
        // passed (this is specific to querying browsers - if you query for any other type of
        // package, MATCH_DEFAULT_ONLY will work as documented). This flag did not exist on Android
        // versions before M, so we only use it in that case.
        //
        // Additionally we add the result of the call with MATCH_ALL onto the end of the result of
        // MATCH_DEFAULT_ONLY (instead of calling queryIntentActivities just once, with MATCH_ALL)
        // because (again, as opposed to the documentation) when MATCH_ALL is used the results are
        // not returned in order of Android's preference.
        //
        // This will result in the user's default browser being in the list twice, however that
        // shouldn't affect the correctness of the following code.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            possibleProviders.addAll(pm.queryIntentActivities(queryBrowsersIntent,
                    PackageManager.MATCH_ALL));
        }

        if (possibleProviders.isEmpty()) {
            // PackageManager reports zero installed browsers. On Samsung OneUI this happens
            // intermittently due to a system bug where intent resolution transiently returns
            // empty results (see https://github.com/TrianguloY/URLCheck/issues/350). Only the
            // query path is broken — binding and launching still work — so probe well-known
            // browsers directly by package name instead of giving up.
            Log.w(TAG, "PackageManager returned no browsers at all — likely the transient "
                    + "Samsung OneUI intent-resolution bug; probing known browsers directly");
            return probeKnownProviders(pm, excludePackages);
        }

        Map<String, Integer> customTabsServices = getLaunchModesForCustomTabsServices(pm);
        String preferredProviderName = "";

        // Loop preferred providers to use as preference order
        for (String preferredProvider : preferredProviders) {
            if (excludePackages != null && excludePackages.contains(preferredProvider)) {
                continue;
            }
            for (ResolveInfo possibleProvider : possibleProviders) {
                String providerName = possibleProvider.activityInfo.packageName;

                if (providerName.equals(preferredProvider)) {
                    Log.d(TAG, "Found provider from preferred providers list: " + providerName);
                    preferredProviderName = preferredProvider;
                    break;
                }
            }

            if (!preferredProviderName.isEmpty()) {
                break;
            }
        }
        
        
        if (preferredProviderName.isEmpty()) {
            Log.d(TAG, "Provider not in preferred providers list.");
        }

        for (ResolveInfo possibleProvider : possibleProviders) {
            String providerName = possibleProvider.activityInfo.packageName;

            if (excludePackages != null && excludePackages.contains(providerName)) {
                continue;
            }

            if (!preferredProviderName.isEmpty() && !providerName.equals(preferredProviderName)) {
                continue;
            }

            @LaunchMode int launchMode = customTabsServices.containsKey(providerName)
                    ? customTabsServices.get(providerName) : LaunchMode.BROWSER;

            switch (launchMode) {
                case LaunchMode.TRUSTED_WEB_ACTIVITY:
                    Log.d(TAG, "Found TWA provider, finishing search: " + providerName);
                    return new Action(LaunchMode.TRUSTED_WEB_ACTIVITY, providerName);
                case LaunchMode.CUSTOM_TAB:
                    Log.d(TAG, "Found Custom Tabs provider: " + providerName);
                    if (bestCctProvider == null) bestCctProvider = providerName;
                    break;
                case LaunchMode.BROWSER:
                    Log.d(TAG, "Found browser: " + providerName);
                    if (bestBrowserProvider == null) bestBrowserProvider = providerName;
                    break;
            }
        }

        if (bestCctProvider != null) {
            Log.d(TAG, "Found no TWA providers, using first Custom Tabs provider: "
                    + bestCctProvider);
            return new Action(LaunchMode.CUSTOM_TAB, bestCctProvider);
        }

        Log.d(TAG, "Found no TWA providers, using first browser: " + bestBrowserProvider);
        return new Action(LaunchMode.BROWSER, bestBrowserProvider);
    }

    /**
     * Probes well-known browsers directly by package name, bypassing intent resolution. Used
     * when PackageManager queries return no browsers at all (transient Samsung OneUI bug).
     * Returns the first installed and enabled known browser, or {@code Action(BROWSER, null)}
     * when none is found.
     */
    private static Action probeKnownProviders(PackageManager pm,
            @Nullable Set<String> excludePackages) {
        for (Map.Entry<String, Integer> knownProvider : knownProviders.entrySet()) {
            String packageName = knownProvider.getKey();
            if (excludePackages != null && excludePackages.contains(packageName)) {
                continue;
            }
            try {
                if (!pm.getApplicationInfo(packageName, 0).enabled) {
                    continue;
                }
            } catch (PackageManager.NameNotFoundException e) {
                continue; // Not installed.
            }
            Log.w(TAG, "Probed known provider directly: " + packageName);
            return new Action(knownProvider.getValue(), packageName);
        }
        Log.w(TAG, "No known browser found via direct package probe");
        return new Action(LaunchMode.BROWSER, null);
    }

    /**
     * Restricts the logic to only consider providers from the given package. For use in testing.
     * Pass in {@code null} to reset.
     */
    static void restrictToPackageForTesting(@Nullable String packageName) {
        sPackageNameForTesting = packageName;
    }

    /** Returns a map from package name to LaunchMode for all available Custom Tabs Services. */
    private static Map<String, Integer> getLaunchModesForCustomTabsServices(PackageManager pm) {
        List<ResolveInfo> services = pm.queryIntentServices(
                new Intent(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION),
                PackageManager.GET_RESOLVED_FILTER);

        Map<String, Integer> customTabsServices = new HashMap<>();
        for (ResolveInfo service : services) {
            String packageName = service.serviceInfo.packageName;

            if (ChromeLegacyUtils.supportsTrustedWebActivities(pm, packageName)) {
                // Chrome 72-74 support Trusted Web Activites but don't yet have the TWA category on
                // their CustomTabsService.
                customTabsServices.put(packageName, LaunchMode.TRUSTED_WEB_ACTIVITY);
                continue;
            }

            boolean supportsTwas = service.filter != null &&
                    service.filter.hasCategory(TRUSTED_WEB_ACTIVITY_CATEGORY);

            customTabsServices.put(packageName,
                    supportsTwas ? LaunchMode.TRUSTED_WEB_ACTIVITY : LaunchMode.CUSTOM_TAB);
        }
        return customTabsServices;
    }
}