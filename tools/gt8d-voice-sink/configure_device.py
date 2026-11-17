"""Select the sink through Android's normal resolver UI. Never disables another app."""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
adb = [sys.argv[1], "-H", "127.0.0.1", "-s", "12345678"]
package = "com.gt8d.voicesink"
component = package + "/.SinkActivity"


def run(*args):
    return subprocess.check_output(adb + list(args), timeout=15).decode("utf-8", "replace")


def resolved(action):
    return run("shell", "cmd", "package", "resolve-activity", "--brief", "-a", action).strip().splitlines()[-1]


def hierarchy():
    activity = run("shell", "dumpsys", "activity", "activities")
    if not re.search(r"topResumedActivity=.* android/com.android.internal.app.ResolverActivity", activity):
        raise RuntimeError("Expected Android resolver; no tap performed")
    run("shell", "uiautomator", "dump", "/data/local/tmp/gt8d-voice-sink-ui.xml")
    xml = run("exec-out", "cat", "/data/local/tmp/gt8d-voice-sink-ui.xml")
    return ET.fromstring(xml)


def tap(node):
    if node.get("enabled") != "true":
        raise RuntimeError("Expected an enabled UI element")
    box = [int(x) for x in re.findall(r"\d+", node.get("bounds", ""))]
    if len(box) != 4:
        raise RuntimeError("Invalid UI bounds")
    run("shell", "input", "tap", str((box[0] + box[2]) // 2), str((box[1] + box[3]) // 2))


for action in ["android.speech.action.WEB_SEARCH", "android.speech.action.VOICE_SEARCH_HANDS_FREE"]:
    current = resolved(action)
    if current == component:
        print("Already selected", action)
        continue
    if current != "android/com.android.internal.app.ResolverActivity":
        raise RuntimeError("Existing default is not a chooser; preserved: " + current)
    run("shell", "am", "start", "-W", "-a", action)
    root = hierarchy()
    parents = {child: parent for parent in root.iter() for child in parent}
    label = next((node for node in root.iter("node") if node.get("text") == "GT8D 音声キー抑止"), None)
    if label is None:
        raise RuntimeError("Sink label not found")
    row = label
    while row.get("clickable") != "true":
        row = parents.get(row)
        if row is None:
            raise RuntimeError("Selectable sink row not found")
    tap(row)
    root = hierarchy()
    always = next((node for node in root.iter("node") if node.get("resource-id") == "android:id/button_always"), None)
    if always is None:
        raise RuntimeError("Always button not found; selection not committed")
    tap(always)
    time.sleep(0.6)
    actual = resolved(action)
    if actual != component:
        raise AssertionError((action, actual))
    print("PASS preferred handler", action, component)
