import socket, pyautogui

HOST = "10.0.0.118"
PORT = 7642

pyautogui.PAUSE = 0
# pyautogui.FAILSAFE = False  # optional

MODS = {
    "shift", "ctrl", "alt", "command", "cmd",
    "shiftleft", "shiftright", "ctrlleft", "ctrlright", "altleft", "altright"
}

ALIASES = {
    "escape": "esc",
    "return": "enter",
    "del": "delete",
    "pgup": "pageup",
    "pgdn": "pagedown",
    "cmd": "command",
    "menu": "apps",
    "prtsc": "printscreen",
    "scrlk": "scrolllock",
    "bksp": "backspace",
}


def norm_key(key: str) -> str:
    key = key.strip().lower()
    return ALIASES.get(key, key)


def is_modifier(key: str) -> bool:
    return key in MODS


def serve():
    s = socket.socket()
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind((HOST, PORT))
    s.listen(1)
    print(f"Running on {HOST}:{PORT}")

    while True:
        conn, addr = s.accept()
        print(f"Client Connected at: {addr}")
        try:
            with conn:
                buf = b""
                pending_mods = []
                while True:
                    data = conn.recv(4096)
                    if not data:
                        break
                    buf += data
                    while b"\n" in buf:
                        line, buf = buf.split(b"\n", 1)
                        raw = line.decode("utf-8", errors="ignore").strip()
                        if not raw:
                            continue

                        # Mouse commands
                        if raw.startswith("mouse_move"):
                            parts = raw.split()
                            if len(parts) == 3:
                                try:
                                    dx = int(parts[1]); dy = int(parts[2])
                                    pyautogui.moveRel(dx, dy, duration=0)
                                except Exception as e:
                                    print(f"Bad mouse_move '{raw}': {e}")
                            continue

                        if raw.startswith("mouse_click"):
                            parts = raw.split()
                            if len(parts) == 2 and parts[1] in ("left", "right", "middle"):
                                try:
                                    pyautogui.click(button=parts[1])
                                except Exception as e:
                                    print(f"Bad mouse_click '{raw}': {e}")
                            continue

                        if raw.startswith("mouse_scroll"):
                            parts = raw.split()
                            if len(parts) == 2:
                                try:
                                    steps = int(parts[1]) * 50
                                    # invert sign: client sends +down, pyautogui +up
                                    pyautogui.scroll(-steps)
                                except Exception as e:
                                    print(f"Bad mouse_scroll '{raw}': {e}")
                            continue

                        if raw.startswith("mouse_hscroll"):
                            parts = raw.split()
                            if len(parts) == 2:
                                try:
                                    steps = int(parts[1]) * 5
                                    # positive = scroll right
                                    pyautogui.hscroll(steps)
                                except Exception as e:
                                    print(f"Bad mouse_hscroll '{raw}': {e}")
                            continue

                        key = norm_key(raw)

                        # Commands
                        if key == "clear":
                            pending_mods.clear()
                            continue

                        if is_modifier(key):
                            if key not in pending_mods:
                                pending_mods.append(key)
                            continue

                        # Press with pending modifiers if any
                        try:
                            if pending_mods:
                                pyautogui.hotkey(*pending_mods, key)
                                pending_mods.clear()
                            else:
                                pyautogui.press(key)
                        except Exception as e:
                            print(f"Warning: could not press '{key}': {e}")
        except Exception as e:
            print(f"Client error: {e}")
        finally:
            print("Client disconnected")


if __name__ == "__main__":
    serve()
