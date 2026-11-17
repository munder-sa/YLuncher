"""Explicit-component sink tests only; never changes defaults or sends a voice key."""
import json
import pathlib
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
adb = [sys.argv[1], "-H", "127.0.0.1", "-s", "12345678"]
package = "com.gt8d.voicesink"
normal = "android.speech.action.WEB_SEARCH"
locked = "android.speech.action.VOICE_SEARCH_HANDS_FREE"


def run(*args):
    return subprocess.check_output(adb + list(args), timeout=15).decode("utf-8", "replace")


def foreground():
    return re.findall(r"topResumedActivity=ActivityRecord\{\S+ u\d+ ([^ ]+)",
                      run("shell", "dumpsys", "activity", "activities"))


def counts():
    result = subprocess.run(adb + ["exec-out", "cat", f"/data/user/0/{package}/shared_prefs/received_actions.xml"],
                            capture_output=True, timeout=10)
    if b"No such file" in result.stdout + result.stderr:
        return {normal: 0, locked: 0}
    if result.returncode:
        raise RuntimeError("Unable to read sink counters")
    root = ET.fromstring(result.stdout)
    saved = {x.get("name"): int(x.get("value")) for x in root.findall("int")}
    return {normal: saved.get(normal, 0), locked: saved.get(locked, 0)}


settings_before = {key: run("shell", "settings", "get", "secure", key).strip()
                   for key in ["assistant", "voice_interaction_service"]}
results = []
for action in [normal, locked, "com.gt8d.voicesink.TEST_UNSUPPORTED"]:
    before = counts()
    previous = foreground()
    result = run("shell", "am", "start", "-W", "--activity-no-animation", "-n",
                 package + "/.SinkActivity", "-a", action)
    if "Error:" in result or "Exception" in result:
        raise AssertionError(result)
    time.sleep(0.6)
    after = counts()
    resumed = foreground()
    expected = dict(before)
    if action in expected:
        expected[action] += 1
    assert after == expected, (action, before, after)
    assert resumed == previous, (action, "foreground changed", previous, resumed)
    results.append({"action": action, "count_correct": True, "returned_to_previous_screen": True})
    print("PASS", action, "counter and previous screen")

settings_after = {key: run("shell", "settings", "get", "secure", key).strip()
                  for key in settings_before}
assert settings_before == settings_after, "Assistant settings changed"
print("PASS assistant settings unchanged")
report = {"explicit_component_tests": results, "assistant_settings_unchanged": True}
out = pathlib.Path(__file__).resolve().parent / "build/explicit-tests.json"
out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
