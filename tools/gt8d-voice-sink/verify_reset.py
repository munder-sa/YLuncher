"""Test the app's own reset UI, then restore the selected sink through the chooser."""
import pathlib
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
adb_path = sys.argv[1]
adb = [adb_path, "-H", "127.0.0.1", "-s", "12345678"]


def run(*args):
    return subprocess.check_output(adb + list(args), timeout=15).decode("utf-8", "replace")


def tap_text(text):
    activity = run("shell", "dumpsys", "activity", "activities")
    if not re.search(r"topResumedActivity=.* com.gt8d.voicesink/", activity):
        raise RuntimeError("Settings screen is not in front")
    run("shell", "uiautomator", "dump", "/data/local/tmp/gt8d-voice-sink-ui.xml")
    root = ET.fromstring(run("exec-out", "cat", "/data/local/tmp/gt8d-voice-sink-ui.xml"))
    node = next((n for n in root.iter("node") if n.get("text") == text
                 and n.get("clickable") == "true" and n.get("enabled") == "true"), None)
    if node is None:
        raise RuntimeError("Button not visible: " + text)
    box = [int(n) for n in re.findall(r"\d+", node.get("bounds", ""))]
    if len(box) != 4:
        raise RuntimeError("Invalid button bounds")
    run("shell", "input", "tap", str((box[0] + box[2]) // 2), str((box[1] + box[3]) // 2))


try:
    run("shell", "am", "start", "-W", "-n", "com.gt8d.voicesink/.SettingsActivity")
    tap_text("このアプリの既定指定を解除")
    tap_text("解除")
    time.sleep(0.4)
    for action in ["android.speech.action.WEB_SEARCH", "android.speech.action.VOICE_SEARCH_HANDS_FREE"]:
        actual = run("shell", "cmd", "package", "resolve-activity", "--brief", "-a", action)
        assert "android/com.android.internal.app.ResolverActivity" in actual, (action, actual)
        print("PASS reset preferred handler", action, flush=True)
finally:
    project = pathlib.Path(__file__).resolve().parent
    subprocess.run([sys.executable, "-B", str(project / "configure_device.py"), adb_path], check=True)

run("shell", "am", "start", "-W", "-n", "com.gt8d.voicesink/.SettingsActivity")
time.sleep(0.5)
image = subprocess.check_output(adb + ["exec-out", "screencap", "-p"], timeout=10)
(project / "build/installed-screen.png").write_bytes(image)
print("Saved settings screenshot; sink selected for both routes")
