package com.gt8d.usbtoggle;

public final class ModeLogicTest {
    private static int count;
    private static void check(boolean result) { count++; if (!result) throw new AssertionError("check " + count); }
    private static void rejects(Runnable action) {
        count++; try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("unsafe input accepted " + count);
    }
    public static void main(String[] args) {
        String nonce = "0123456789abcdef0123456789abcdef";
        check(ModeLogic.supported("zhiduojing-wireless", "SC162-V06.000.002", "35"));
        check(!ModeLogic.supported("other", "SC162-V06.000.002", "35"));
        check(!ModeLogic.supported("zhiduojing-wireless", "SC138", "35"));
        check(!ModeLogic.supported("zhiduojing-wireless", "SC162-V06", "34"));
        check(!ModeLogic.supported("zhiduojing-wireless", null, "35"));
        check(ModeLogic.validMode("0")); check(ModeLogic.validMode("1"));
        check(!ModeLogic.validMode(null)); check(!ModeLogic.validMode("")); check(!ModeLogic.validMode("2"));
        rejects(() -> ModeLogic.apply(nonce, "0", "1; reboot"));
        rejects(() -> ModeLogic.apply(nonce, "$(reboot)", "0"));
        rejects(() -> ModeLogic.probe("'; reboot"));
        rejects(() -> ModeLogic.reboot(nonce, ""));
        check(ModeLogic.actualMode(ModeLogic.USB_ADB).equals("USB ADBモード"));
        check(ModeLogic.actualMode("autoReverseTemp").equals("車載モード"));
        check(ModeLogic.actualMode("unexpected").startsWith("未判定"));
        check(!ModeLogic.probe(nonce).contains("setprop"));
        check(!ModeLogic.probe(nonce).contains("/system/bin/reboot"));
        check(!ModeLogic.apply(nonce, "1", "0").contains("/system/bin/reboot"));
        check(!ModeLogic.reboot(nonce, "0").contains("setprop"));
        System.out.println("PASS " + count + " checks");
        // Export complete scripts for independent shell syntax and simulated-device checks.
        if (args.length > 0) {
            System.out.println("PROBE\t" + ModeLogic.probe(nonce));
            System.out.println("TO_CAR\t" + ModeLogic.apply(nonce, "1", "0"));
            System.out.println("TO_ADB\t" + ModeLogic.apply(nonce, "0", "1"));
            System.out.println("REBOOT\t" + ModeLogic.reboot(nonce, "0"));
        }
    }
}
