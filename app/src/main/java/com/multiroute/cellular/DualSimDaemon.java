package com.multiroute.cellular;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.TelephonyNetworkSpecifier;
import android.os.Looper;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import java.lang.reflect.Method;
import java.util.List;

/** Root app_process entry point. A live callback holds the requested secondary network. */
public final class DualSimDaemon {
    /** app_process does not inherit the telephony initialization performed in Zygote. */
    private static void initializeTelephony() throws Exception {
        Class<?> initializer = Class.forName("android.telephony.TelephonyFrameworkInitializer");
        if (initializer.getMethod("getTelephonyServiceManager").invoke(null) == null) {
            Class<?> manager = Class.forName("android.os.TelephonyServiceManager");
            initializer.getMethod("setTelephonyServiceManager", manager)
                    .invoke(null, manager.getConstructor().newInstance());
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !(args[0].equals("1") || args[0].equals("2"))) {
            throw new IllegalArgumentException("Expected mode 1 or 2");
        }
        Looper.prepareMainLooper();
        initializeTelephony();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        SubscriptionManager sm = context.getSystemService(SubscriptionManager.class);
        int primary = SubscriptionManager.getDefaultDataSubscriptionId();
        List<SubscriptionInfo> subscriptions = sm.getActiveSubscriptionInfoList();
        int secondary = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        if (subscriptions != null) for (SubscriptionInfo info : subscriptions) {
            if (info.getSubscriptionId() != primary) {
                if (secondary != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    throw new IllegalStateException("More than one secondary subscription");
                }
                secondary = info.getSubscriptionId();
            }
        }
        if (!SubscriptionManager.isValidSubscriptionId(primary) || !SubscriptionManager.isValidSubscriptionId(secondary)) {
            throw new IllegalStateException("Two active subscriptions are required");
        }
        Class<?> properties = Class.forName("android.os.SystemProperties");
        Method get = properties.getMethod("get", String.class, String.class);
        Method set = properties.getMethod("set", String.class, String.class);
        String key = "ril.multisim.dualdata_status";
        String original = (String) get.invoke(null, key, "2");
        boolean changed = args[0].equals("1") && !original.equals("1");
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        NetworkRequest.Builder builder = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_MMS)
                .setNetworkSpecifier(new TelephonyNetworkSpecifier.Builder().setSubscriptionId(secondary).build());
        if (args[0].equals("2")) builder.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { android.util.Log.i("MultiRouteDualSim", "available " + n); }
            @Override public void onLost(Network n) { android.util.Log.i("MultiRouteDualSim", "lost " + n); }
            @Override public void onUnavailable() {
                android.util.Log.w("MultiRouteDualSim", "unavailable");
                Looper.getMainLooper().quitSafely();
            }
        };
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { cm.unregisterNetworkCallback(callback); } catch (Exception ignored) { }
            try {
                if (changed && "1".equals(get.invoke(null, key, "2"))) set.invoke(null, key, original);
            } catch (Exception ignored) { }
        }));
        try {
            if (changed) set.invoke(null, key, "1");
            cm.requestNetwork(builder.build(), callback);
            Looper.loop();
        } finally {
            try { cm.unregisterNetworkCallback(callback); } catch (Exception ignored) { }
            if (changed && "1".equals(get.invoke(null, key, "2"))) set.invoke(null, key, original);
        }
    }
}
