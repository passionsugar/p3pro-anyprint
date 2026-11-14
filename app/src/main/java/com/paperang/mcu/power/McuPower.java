package com.paperang.mcu.power;

/** Original vendor JNI name. */
public final class McuPower {
    static { System.loadLibrary("mcu_power"); }
    private static native void mcuPower(int value);
    public static void on() { mcuPower(1); }
}
