package com.multiroute.adb;

/** Short-lived root app_process reader. No Binder transaction numbers or system mutations. */
public final class AdbPortReader {
    public static void main(String[] args) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object binder = sm.getMethod("getService", String.class).invoke(null, "adb");
            Class<?> stub = Class.forName("android.debug.IAdbManager$Stub");
            Object manager = stub.getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
            Object port = Class.forName("android.debug.IAdbManager")
                    .getMethod("getAdbWirelessPort").invoke(manager);
            System.out.println("PORT=" + port);
        } catch (Throwable ignored) {
            System.out.println("PORT_UNAVAILABLE");
        }
    }
}
