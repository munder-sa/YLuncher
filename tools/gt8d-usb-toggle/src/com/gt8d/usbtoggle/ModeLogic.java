package com.gt8d.usbtoggle;

/** Fixed commands for the verified X21 SC162 firmware; no arbitrary command interface. */
public final class ModeLogic {
    public static final String PACKAGE = "com.gt8d.usbtoggle";
    public static final String ACTION = PACKAGE + ".RESULT";
    public static final String PROPERTY = "persist.sdcarplay.reverse.disable.wireless";
    public static final String USB_ADB = "diag,serial_cdev,rmnet,dpl,qdss,adb";
    private ModeLogic() {}

    public static boolean validMode(String mode) { return "0".equals(mode) || "1".equals(mode); }
    public static boolean supported(String custom, String firmware, String sdk) {
        return "zhiduojing-wireless".equals(custom) && "35".equals(sdk)
                && firmware != null && firmware.startsWith("SC162-");
    }
    public static String actualMode(String usb) {
        if (USB_ADB.equals(usb)) return "USB ADBモード";
        if ("autoReverseTemp".equals(usb) || "appleSvcTemp".equals(usb)) return "車載モード";
        return "未判定（" + usb + "）";
    }
    private static String callback(String nonce, String operation) {
        if (nonce == null || !nonce.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("nonce");
        if (!operation.matches("probe|apply|reboot")) throw new IllegalArgumentException("operation");
        return "/system/bin/am broadcast --receiver-foreground --user 0 -a " + ACTION
                + " -p " + PACKAGE + " --es nonce " + nonce + " --es op " + operation
                + " --es uid \"$(/system/bin/id -u)\" --es mode \"$(/system/bin/getprop " + PROPERTY + ")\""
                + " --es usb \"$(/system/bin/getprop sys.usb.config)\""
                + " --es custom \"$(/system/bin/getprop ro.board.reverse.custom)\""
                + " --es firmware \"$(/system/bin/getprop ro.build.display.id)\""
                + " --es sdk \"$(/system/bin/getprop ro.build.version.sdk)\" --ei code \"$code\"";
    }
    public static String probe(String nonce) { return "code=0; " + callback(nonce, "probe"); }
    private static void check(String expected, String target) {
        if (!validMode(expected) || !validMode(target)) throw new IllegalArgumentException("mode");
    }
    public static String apply(String nonce, String expected, String target) {
        check(expected, target);
        // Recheck device and original value inside the privileged process before writing.
        String guard = "[ \"$(getprop ro.board.reverse.custom)\" = zhiduojing-wireless ]"
                + " && [ \"$(getprop ro.build.version.sdk)\" = 35 ]"
                + " && case \"$(getprop ro.build.display.id)\" in SC162-*) true;; *) false;; esac";
        return "code=0; if ! ( " + guard + " ); then code=13; "
                + "elif [ \"$(getprop " + PROPERTY + ")\" != " + expected + " ]; then code=10; "
                + "else setprop " + PROPERTY + " " + target + "; rc=$?; "
                + "if [ \"$rc\" != 0 ] || [ \"$(getprop " + PROPERTY + ")\" != " + target + " ]; then "
                + "setprop " + PROPERTY + " " + expected + "; code=11; "
                + "[ \"$(getprop " + PROPERTY + ")\" = " + expected + " ] || code=12; fi; fi; "
                + callback(nonce, "apply");
    }
    public static String reboot(String nonce, String target) {
        check(target, target);
        return "code=0; if [ \"$(getprop " + PROPERTY + ")\" = " + target + " ]; then "
                + "/system/bin/sync; /system/bin/reboot; code=$?; else code=10; fi; "
                + callback(nonce, "reboot");
    }
}
