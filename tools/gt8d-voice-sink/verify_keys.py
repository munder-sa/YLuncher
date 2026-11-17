"""One short and one long voice key; verify standard sink and vendor path separately."""
import json
import pathlib
import subprocess
import sys
import time

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
adb = [sys.argv[1], "-H", "127.0.0.1", "-s", "12345678"]


def run(*args):
    return subprocess.check_output(adb + list(args), timeout=15).decode("utf-8", "replace")


for action in ["android.speech.action.WEB_SEARCH", "android.speech.action.VOICE_SEARCH_HANDS_FREE"]:
    handler = run("shell", "cmd", "package", "resolve-activity", "--brief", "-a", action)
    assert "com.gt8d.voicesink/.SinkActivity" in handler, handler
assert "package:com.google.android.googlequicksearchbox" in run(
    "shell", "pm", "list", "packages", "-e", "com.google.android.googlequicksearchbox")
before = {key: run("shell", "settings", "get", "secure", key).strip()
          for key in ["assistant", "voice_interaction_service"]}
reports = []
for kind, arguments, expected_vendor_requests in [
    ("short", [], 1), ("long", ["--longpress"], 2)
]:
    start = float(run("shell", "date", "+%s").strip())
    run("shell", "input", "keyevent", *arguments, "KEYCODE_VOICE_ASSIST")
    time.sleep(1.3)
    raw = run("logcat", "-d", "-v", "epoch", "-t", "7000")
    rows = []
    for line in raw.splitlines():
        try:
            stamp = float(line.split()[0])
        except (ValueError, IndexError):
            continue
        if stamp >= start:
            rows.append(line)
    vendor = [line for line in rows if "openAppByPkgName open app=local.aibox.gptlive" in line]
    consumed = [line for line in rows if "GT8DVoiceSink" in line
                and "consumed action=android.speech.action.WEB_SEARCH" in line]
    google = [line for line in rows if "ActivityTaskManager: START" in line
              and "com.google.android.googlequicksearchbox" in line]
    chatgpt = [line for line in rows if "ActivityTaskManager: START" in line
               and "com.openai.chatgpt" in line]
    assert len(vendor) == expected_vendor_requests, (kind, "vendor requests", len(vendor))
    assert len(consumed) == 1, (kind, "sink requests", len(consumed))
    assert len(google) == 0, (kind, "unexpected Google launch", len(google))
    assert len(chatgpt) > 0, (kind, "ChatGPT was not called")
    report = {"key": kind, "vendor_requests": len(vendor), "standard_requests_consumed": len(consumed),
              "google_launches": len(google), "chatgpt_launch_confirmed": True}
    reports.append(report)
    print("PASS", json.dumps(report, ensure_ascii=False))
    time.sleep(1.2)

after = {key: run("shell", "settings", "get", "secure", key).strip() for key in before}
assert before == after, "Assistant settings changed"
print("PASS Google enabled and assistant settings unchanged")
out = pathlib.Path(__file__).resolve().parent / "build/key-tests.json"
out.write_text(json.dumps(reports, ensure_ascii=False, indent=2), encoding="utf-8")
