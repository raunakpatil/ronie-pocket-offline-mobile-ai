r"""Drives the Ronie Pocket chat UI over adb: types a message, sends it, waits for the reply,
then prints the visible conversation and the stats line. Dev/testing helper.

Usage: tools-pc\.venv\Scripts\python.exe scripts\phone-chat.py "Hey Ronie!" ["second message" ...]
"""
import os, re, subprocess, sys, time, xml.etree.ElementTree as ET

ADB = os.path.join(os.environ["LOCALAPPDATA"], "Android", "Sdk", "platform-tools", "adb.exe")


def serial():
    out = subprocess.run([ADB, "devices", "-l"], capture_output=True, text=True).stdout
    for line in out.splitlines():
        if "model:Pixel_9_Pro" in line:
            return line.split()[0]
    sys.exit("Pixel 9 Pro not connected")


S = serial()
def sh(*args): return subprocess.run([ADB, "-s", S, "shell", *args], capture_output=True, text=True).stdout


def dump():
    sh("uiautomator", "dump", "/sdcard/ronie-ui.xml")
    xml = subprocess.run([ADB, "-s", S, "exec-out", "cat", "/sdcard/ronie-ui.xml"], capture_output=True).stdout
    return ET.fromstring(xml.decode("utf-8", "replace"))


def center(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def find(root, pred):
    return next((n for n in root.iter("node") if pred(n)), None)


def texts(root):
    return [n.get("text") for n in root.iter("node") if n.get("text")]


def send(msg):
    root = dump()
    field = find(root, lambda n: n.get("class", "").endswith("EditText"))
    if field is None:
        sys.exit("message field not found (is the phone unlocked and the app open?)")
    sh("input", "tap", *map(str, center(field)))
    # Clear any leftover text by selecting all and deleting or backspacing
    sh("input", "keyevent", "KEYCODE_MOVE_END")
    for _ in range(50):
        sh("input", "keyevent", "KEYCODE_DEL")
    sh("input", "text", msg.replace(" ", "%s").replace("'", "").replace("?", "\\?").replace("!", "\\!"))
    root = dump()
    btn = find(root, lambda n: n.get("text") == "Send" or n.get("content-desc") == "Send")
    if btn is None:
        sys.exit("Send button not found")
    sh("input", "tap", *map(str, center(btn)))
    t0 = time.time()
    while time.time() - t0 < 180:            # wait until the Stop button disappears again
        time.sleep(1.5)
        root = dump()
        has_stop = find(root, lambda n: n.get("text") == "Stop" or n.get("content-desc") == "Stop") is not None
        has_ready = find(root, lambda n: n.get("content-desc") in ("Send", "Speak to Ronie") or n.get("text") == "Send") is not None
        if not has_stop and has_ready:
            break
    return root, time.time() - t0


for m in sys.argv[1:]:
    root, secs = send(m)
    print(f"\n>>> {m}   ({secs:.1f}s wall)")
    for t in texts(root):
        print("   |", t.replace("\n", " / ")[:400])
