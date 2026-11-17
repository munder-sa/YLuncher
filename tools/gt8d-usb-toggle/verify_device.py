"""Read-only bridge/shell verification. All mode writes and reboots are simulated.

Usage: python -B verify_device.py <adb.exe>
"""
import pathlib
import subprocess
import sys

project = pathlib.Path(__file__).resolve().parent
java = r"C:\Program Files\Android\Android Studio\jbr\bin\java.exe"
output = subprocess.check_output([
    java, "-cp", str(project / "build/tests"),
    "com.gt8d.usbtoggle.ModeLogicTest", "scripts"], text=True, encoding="utf-8")
scripts = dict(line.split("\t", 1) for line in output.splitlines() if "\t" in line)
adb = [sys.argv[1], "-H", "127.0.0.1", "-s", "12345678"]

for name, script in scripts.items():
    subprocess.run(adb + ["shell", "sh", "-n"], input=script.encode(), check=True, timeout=8)
print("PASS 4 original scripts: Android shell syntax")

header = r'''
mode=INITIAL; custom=zhiduojing-wireless; sdk=35; firmware=SC162-V06.000.002
writes=0; reboots=0; behavior=normal
simulated_getprop() {
 case "$1" in
 persist.sdcarplay.reverse.disable.wireless) printf '%s' "$mode";;
 ro.board.reverse.custom) printf '%s' "$custom";;
 ro.build.version.sdk) printf '%s' "$sdk";;
 ro.build.display.id) printf '%s' "$firmware";;
 sys.usb.config) printf '%s' diag,serial_cdev,rmnet,dpl,qdss,adb;;
 esac
}
simulated_setprop() {
 writes=$((writes+1))
 case "$behavior" in
 fail) return 1;;
 corrupt) mode=2; return 1;;
 *) mode="$2"; return 0;;
 esac
}
simulated_id() { printf 1000; }
simulated_sync() { :; }
simulated_reboot() { reboots=$((reboots+1)); }
simulated_report() { printf 'code=%s mode=%s writes=%s reboots=%s\n' "$code" "$mode" "$writes" "$reboots"; }
'''

cases = [
    ("car", "TO_CAR", "1", "", "code=0 mode=0 writes=1 reboots=0"),
    ("adb", "TO_ADB", "0", "", "code=0 mode=1 writes=1 reboots=0"),
    ("stale", "TO_CAR", "0", "", "code=10 mode=0 writes=0 reboots=0"),
    ("other_device", "TO_ADB", "0", "custom=other", "code=13 mode=0 writes=0 reboots=0"),
    ("other_android", "TO_ADB", "0", "sdk=34", "code=13 mode=0 writes=0 reboots=0"),
    ("other_firmware", "TO_ADB", "0", "firmware=SC138", "code=13 mode=0 writes=0 reboots=0"),
    ("write_failure", "TO_CAR", "1", "behavior=fail", "code=11 mode=1 writes=2 reboots=0"),
    ("restore_failure", "TO_CAR", "1", "behavior=corrupt", "code=12 mode=2 writes=2 reboots=0"),
    ("reboot", "REBOOT", "0", "", "code=0 mode=0 writes=0 reboots=1"),
    ("stale_reboot", "REBOOT", "1", "", "code=10 mode=1 writes=0 reboots=0"),
    ("probe", "PROBE", "1", "", "code=0 mode=1 writes=0 reboots=0"),
]
for name, which, initial, setup, expected in cases:
    script = scripts[which]
    for original, replacement in [
        ("/system/bin/am", "simulated_report"), ("/system/bin/id", "simulated_id"),
        ("/system/bin/sync", "simulated_sync"), ("/system/bin/reboot", "simulated_reboot"),
        ("/system/bin/getprop", "simulated_getprop"),
    ]:
        script = script.replace(original, replacement)
    # Token replacement avoids rewriting names that have already been substituted.
    import re
    script = re.sub(r"(?<![\w/])getprop\b", "simulated_getprop", script)
    script = re.sub(r"(?<![\w/])setprop\b", "simulated_setprop", script)
    assert "/system/bin/reboot" not in script and "/system/bin/setprop" not in script
    assert not re.search(r"(?:^|;\s*|\bthen\s+)(?:setprop|reboot)\b", script)
    test_script = header.replace("INITIAL", initial) + "\n" + setup + "\n" + script + "\n"
    result = subprocess.check_output(adb + ["shell", "sh", "-s"],
                                     input=test_script.encode(), timeout=8).decode().strip()
    if result != expected:
        raise AssertionError(f"{name}: {result!r} != {expected!r}")
print(f"PASS {len(cases)} simulated mode/safety cases on Android shell (no real writes/reboots)")

image = subprocess.check_output(adb + ["exec-out", "screencap", "-p"], timeout=8)
(project / "build/installed-screen.png").write_bytes(image)
print("Saved installed-screen.png")
