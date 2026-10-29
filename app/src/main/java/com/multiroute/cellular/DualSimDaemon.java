package com.multiroute.cellular;

import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.TelephonyNetworkSpecifier;
import android.os.Looper;
import android.os.Handler;
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

    // TelephonyIntents hidden framework broadcast; verified against this device's framework DEX.
    private static final String DEFAULT_DATA_CHANGED =
            "android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED";

    private static final class Session {
        final Context context;
        final ConnectivityManager cm;
        final boolean restricted;
        final Runnable prepareRequest;
        final Handler handler = new Handler(Looper.getMainLooper());
        final java.util.ArrayList<ConnectivityManager.NetworkCallback> callbacks = new java.util.ArrayList<>();
        int primary = -1, secondary = -1;
        volatile boolean closed;
        final SubscriptionManager subscriptions;
        boolean subscriptionListenerRegistered, receiverRegistered;
        final Runnable refreshTask = this::refreshSubscriptions;
        final SubscriptionManager.OnSubscriptionsChangedListener subscriptionListener =
                new SubscriptionManager.OnSubscriptionsChangedListener() {
                    @Override public void onSubscriptionsChanged() { scheduleRefresh(); }
                };
        final BroadcastReceiver defaultDataReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                if (DEFAULT_DATA_CHANGED.equals(intent.getAction())) {
                    scheduleRefresh();
                }
            }
        };
        Session(Context context, ConnectivityManager cm, boolean restricted, Runnable prepareRequest) {
            this.context = context; this.cm = cm; this.restricted = restricted;
            this.prepareRequest = prepareRequest;
            this.subscriptions = context.getSystemService(SubscriptionManager.class);
        }
        void close() {
            // Block delayed refreshes and network callbacks before releasing any resources.
            synchronized (callbacks) {
                closed = true;
                handler.removeCallbacksAndMessages(null);
            }
            if (subscriptionListenerRegistered) {
                try { subscriptions.removeOnSubscriptionsChangedListener(subscriptionListener); }
                catch (Exception e) { android.util.Log.w("MultiRouteDualSim", "Listener cleanup failed", e); }
                subscriptionListenerRegistered = false;
            }
            if (receiverRegistered) {
                try { context.unregisterReceiver(defaultDataReceiver); }
                catch (Exception e) { android.util.Log.w("MultiRouteDualSim", "Receiver cleanup failed", e); }
                receiverRegistered = false;
            }
            releaseRequests();
        }
        void releaseRequests() {
            synchronized (callbacks) {
                for (ConnectivityManager.NetworkCallback callback : callbacks) {
                    try { cm.unregisterNetworkCallback(callback); } catch (Exception ignored) { }
                }
                callbacks.clear();
            }
        }
        void request(boolean mms) {
            final int targetSub = secondary;
            final String kind = mms ? "INTERNET+MMS" : "INTERNET";
            NetworkRequest.Builder builder = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(new TelephonyNetworkSpecifier.Builder().setSubscriptionId(targetSub).build());
            if (mms) builder.addCapability(NetworkCapabilities.NET_CAPABILITY_MMS);
            if (restricted) builder.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
            ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) {
                    android.util.Log.i("MultiRouteDualSim", kind + " sub=" + targetSub + " available " + n);
                }
                @Override public void onLost(Network n) {
                    android.util.Log.i("MultiRouteDualSim", kind + " sub=" + targetSub + " lost " + n);
                }
                @Override public void onUnavailable() {
                    android.util.Log.w("MultiRouteDualSim", kind + " unavailable sub=" + targetSub);
                }
            };
            synchronized (callbacks) {
                if (closed) return;
                android.util.Log.i("MultiRouteDualSim", "request " + kind + " sub=" + targetSub);
                cm.requestNetwork(builder.build(), callback);
                callbacks.add(callback);
            }
        }
        void scheduleRefresh() {
            // Both event sources run on our handler; coalesce bursts during a SIM transition.
            synchronized (callbacks) {
                if (closed) return;
                handler.removeCallbacks(refreshTask);
                handler.postDelayed(refreshTask, 300);
            }
        }
        void start() {
            // Register before the initial read so a concurrent default-data change cannot be missed.
            subscriptions.addOnSubscriptionsChangedListener(command -> handler.post(command), subscriptionListener);
            subscriptionListenerRegistered = true;
            IntentFilter filter = new IntentFilter(DEFAULT_DATA_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(defaultDataReceiver, filter, null, handler, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(defaultDataReceiver, filter, null, handler);
            }
            receiverRegistered = true;
            scheduleRefresh();
        }
        void refreshSubscriptions() {
            if (closed) return;
            try {
                int nextPrimary = SubscriptionManager.getDefaultDataSubscriptionId();
                List<SubscriptionInfo> subs = subscriptions.getActiveSubscriptionInfoList();
                java.util.ArrayList<Integer> ids = new java.util.ArrayList<>();
                if (subs != null) for (SubscriptionInfo sub : subs) ids.add(sub.getSubscriptionId());
                int nextSecondary = SubscriptionSelection.INSTANCE.secondary(nextPrimary, ids);
                if (primary == nextPrimary && secondary == nextSecondary) return;
                releaseRequests();
                primary = nextPrimary; secondary = nextSecondary;
                android.util.Log.i("MultiRouteDualSim", "retarget primary=" + primary + " secondary=" + secondary);
                if (secondary >= 0) {
                    prepareRequest.run();
                    // Submit independent requests immediately. A carrier may satisfy one or both
                    // with the same bearer; one rejected variant must not cancel the other.
                    boolean registered = false;
                    Exception failure = null;
                    for (boolean mms : new boolean[]{false, true}) {
                        try {
                            request(mms);
                            registered = true;
                        } catch (Exception e) {
                            failure = e;
                            android.util.Log.w("MultiRouteDualSim",
                                    "Request variant failed mms=" + mms + " sub=" + secondary, e);
                        }
                    }
                    if (!registered) throw new IllegalStateException("Both network requests failed", failure);
                }
            } catch (Exception e) {
                android.util.Log.e("MultiRouteDualSim", "Subscription refresh failed", e);
                releaseRequests(); primary = -1; secondary = -1;
            }
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
        Class<?> properties = Class.forName("android.os.SystemProperties");
        Method get = properties.getMethod("get", String.class, String.class);
        Method set = properties.getMethod("set", String.class, String.class);
        String key = "ril.multisim.dualdata_status";
        String original = (String) get.invoke(null, key, "2");
        boolean changed = args[0].equals("1") && !original.equals("1");
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        Session session = new Session(context, cm, args[0].equals("2"), () -> {
            if (args[0].equals("1")) {
                try {
                    if (!"1".equals(get.invoke(null, key, "2"))) set.invoke(null, key, "1");
                } catch (Exception e) { throw new IllegalStateException("Cannot enable dual data", e); }
            }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { session.close(); } catch (Exception ignored) { }
            try {
                if (changed && "1".equals(get.invoke(null, key, "2"))) set.invoke(null, key, original);
            } catch (Exception ignored) { }
        }));
        try {
            if (changed) set.invoke(null, key, "1");
            session.start();
            Looper.loop();
        } finally {
            try { session.close(); } catch (Exception ignored) { }
            if (changed && "1".equals(get.invoke(null, key, "2"))) set.invoke(null, key, original);
        }
    }
}
