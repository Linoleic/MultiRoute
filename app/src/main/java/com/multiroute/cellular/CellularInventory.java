package com.multiroute.cellular;

import android.content.Context;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Root CLI: emits device-scoped digests, never raw ICCIDs or phone numbers. */
public final class CellularInventory {
    static String normalizedIccid(String value) {
        if (value == null) return null;
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT).replaceAll("F+$", "");
        return normalized.matches("89[0-9A-F]{16,20}") ? normalized : null;
    }
    private static String deviceSalt() throws java.io.IOException {
        java.io.File directory = new java.io.File("/data/adb/multiroute");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("Cannot create identity directory");
        java.io.File file = new java.io.File(directory, "cellular_identity");
        try (java.io.RandomAccessFile data = new java.io.RandomAccessFile(file, "rw");
             java.nio.channels.FileLock lock = data.getChannel().lock()) {
            if (!file.setReadable(false, false) || !file.setWritable(false, false) ||
                !file.setReadable(true, true) || !file.setWritable(true, true)) throw new java.io.IOException("Cannot protect identity salt");
            if (data.length() == 0) data.write(java.util.UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII));
            data.seek(0);
            String value = data.readLine();
            if (value == null || !value.matches("[0-9a-f-]{36}")) throw new java.io.IOException("Invalid identity salt");
            return value;
        }
    }
    public static void main(String[] args) throws Exception {
        android.os.Looper.prepareMainLooper();
        Class<?> initializer = Class.forName("android.telephony.TelephonyFrameworkInitializer");
        if (initializer.getMethod("getTelephonyServiceManager").invoke(null) == null) {
            Class<?> manager = Class.forName("android.os.TelephonyServiceManager");
            initializer.getMethod("setTelephonyServiceManager", manager).invoke(null, manager.getConstructor().newInstance());
        }
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        String device = deviceSalt();
        JSONArray result = new JSONArray();
        SubscriptionManager sm = context.getSystemService(SubscriptionManager.class);
        java.util.List<SubscriptionInfo> subscriptions = sm.getActiveSubscriptionInfoList();
        if (subscriptions != null) for (SubscriptionInfo sub : subscriptions) {
            JSONObject item = new JSONObject();
            item.put("subId", sub.getSubscriptionId());
            item.put("slot", sub.getSimSlotIndex() + 1);
            item.put("carrier", String.valueOf(sub.getCarrierName()));
            item.put("default", sub.getSubscriptionId() == SubscriptionManager.getDefaultDataSubscriptionId());
            JSONArray networks = new JSONArray();
            android.net.ConnectivityManager cm = context.getSystemService(android.net.ConnectivityManager.class);
            for (android.net.Network network : cm.getAllNetworks()) {
                android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                android.net.LinkProperties lp = cm.getLinkProperties(network);
                if (caps == null || lp == null || lp.getInterfaceName() == null ||
                        !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                Object spec = android.net.NetworkCapabilities.class.getMethod("getNetworkSpecifier").invoke(caps);
                if (spec instanceof android.net.TelephonyNetworkSpecifier &&
                        ((android.net.TelephonyNetworkSpecifier) spec).getSubscriptionId() == sub.getSubscriptionId()) {
                    networks.put(new JSONObject().put("netId", network.toString()).put("iface", lp.getInterfaceName()));
                }
            }
            item.put("networks", networks);
            String iccid = normalizedIccid(sub.getIccId());
            if (iccid == null) {
                try {
                    iccid = normalizedIccid(context.getSystemService(android.telephony.TelephonyManager.class)
                        .createForSubscriptionId(sub.getSubscriptionId()).getSimSerialNumber());
                } catch (SecurityException ignored) { }
            }
            if (iccid != null) {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest((device + ":" + iccid).getBytes(StandardCharsets.UTF_8));
                StringBuilder key = new StringBuilder("sim_");
                for (byte b : digest) key.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
                item.put("key", key.toString());
            }
            result.put(item);
        }
        System.out.println("MULTIROUTE_SIM=" + result);
    }
}
