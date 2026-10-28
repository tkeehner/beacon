#!/usr/bin/env python3
"""Beacon remote updater, PC side (0.1.16). Pushes a Beacon APK to the phone through Emit's existing relay (a server you run), unchanged.

  beacon-update pair <phone key | @file>    make a pairing code sealed to the phone's key (paste it into Beacon)
  beacon-update push <beacon.apk> [--wait 600]
  beacon-update status | unpair

The relay is msms-relay (Emit's): we reuse its host / cert pin / token from Emit's PC config (read-only, never printed)
and join our OWN room (HMAC(link key, "beacon-room-v1")). Frames are AES-256-GCM with per-session keys, so the relay
only forwards ciphertext. The phone itself refuses anything that isn't com.tyler.primelauncher, signed with the same key
as the installed app, and newer; then the owner taps Install on Android's own prompt. Needs: Python 3.9+, `cryptography`
(Emit's bundled PC Python has both). Must match app/.../update/UpdateCore.kt byte for byte.
"""
import argparse, base64, hashlib, hmac, json, os, socket, ssl, struct, sys, time

MAGIC = b"BUP1"
CHUNK = 1024 * 1024
MAX_APK = 200 * 1024 * 1024

def home_dir(override=None):
    d = override or os.environ.get("BEACON_PC_HOME") or (os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), "Beacon-PC") if sys.platform == "win32"
                                                         else os.path.join(os.path.expanduser("~"), ".config", "beacon-pc"))
    os.makedirs(d, exist_ok=True); return d

def msms_identity(override=None):
    if override: return override
    base = os.environ.get("MSMS_HOME") or (os.path.join(os.environ.get("APPDATA", ""), "MatrixSMS-PC") if sys.platform == "win32"
                                           else os.path.join(os.path.expanduser("~"), ".config", "matrixsms-pc"))
    return os.path.join(base, "identity.json")

# ---- local secret protection (Windows DPAPI, CurrentUser) ----
if sys.platform == "win32":
    import ctypes
    from ctypes import wintypes
    class _BLOB(ctypes.Structure): _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_char))]
    def _dpapi(data, protect):
        buf = ctypes.create_string_buffer(data, len(data)); inb = _BLOB(len(data), ctypes.cast(buf, ctypes.POINTER(ctypes.c_char)))
        e = b"beacon-pc-v1"; ebuf = ctypes.create_string_buffer(e, len(e)); ent = _BLOB(len(e), ctypes.cast(ebuf, ctypes.POINTER(ctypes.c_char))); out = _BLOB()
        fn = ctypes.windll.crypt32.CryptProtectData if protect else ctypes.windll.crypt32.CryptUnprotectData
        ok = fn(ctypes.byref(inb), "beacon" if protect else None, ctypes.byref(ent), None, None, 0x1, ctypes.byref(out))
        if not ok: raise OSError("DPAPI failed")
        try: return ctypes.string_at(out.pbData, out.cbData)
        finally: ctypes.windll.kernel32.LocalFree(out.pbData)
    def protect(b): return "dpapi", base64.b64encode(_dpapi(b, True)).decode()
    def unprotect(kind, s):
        if kind != "dpapi": raise ValueError("unexpected key protection")
        return _dpapi(base64.b64decode(s), False)
else:
    def protect(b): return "plain", base64.b64encode(b).decode()
    def unprotect(kind, s): return base64.b64decode(s)

def _save(path, data):
    tmp = path + ".tmp"
    with open(tmp, "w") as f: json.dump(data, f, indent=1)
    try: os.chmod(tmp, 0o600)
    except OSError: pass
    os.replace(tmp, path)

def relay_cfg(identity_path):
    """Emit's relay settings (host, port, cert pin, token). Read-only; the token is never printed or logged."""
    try:
        with open(identity_path) as f: r = json.load(f).get("relay") or {}
    except FileNotFoundError: raise SystemExit("Emit PC config not found (" + identity_path + "); Beacon updates reuse Emit's relay settings")
    if not (r.get("host") and r.get("token") and r.get("fingerprint")): raise SystemExit("Emit's relay is not configured on this PC (msms relay set ...)")
    return {"h": r["host"], "p": int(r.get("port", 443)), "f": r["fingerprint"].replace(":", "").replace(" ", "").lower(), "t": r["token"]}

# ---- crypto (mirror of LinkCrypto in UpdateCore.kt) ----
def room(key): return hmac.new(key, b"beacon-room-v1", hashlib.sha256).hexdigest()
def hkdf(ikm, salt, info):
    prk = hmac.new(salt, ikm, hashlib.sha256).digest(); return hmac.new(prk, info + b"\x01", hashlib.sha256).digest()
def keys(k, nc, np_):
    return hkdf(k, nc + np_, b"beacon-upd-v1 pc>ph"), hkdf(k, nc + np_, b"beacon-upd-v1 ph>pc")
class Dir:
    def __init__(self, key):
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        self.a = AESGCM(key); self.ctr = 0
    def _n(self): n = b"\0\0\0\0" + struct.pack(">Q", self.ctr); self.ctr += 1; return n
    def seal(self, p): return self.a.encrypt(self._n(), p, None)
    def open(self, c): return self.a.decrypt(self._n(), c, None)

def seal_pairing(phone_key_der, payload):
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    pub = serialization.load_der_public_key(phone_key_der)
    aes = os.urandom(32); iv = os.urandom(12)
    wk = pub.encrypt(aes, padding.OAEP(mgf=padding.MGF1(hashes.SHA1()), algorithm=hashes.SHA256(), label=None))
    ct = AESGCM(aes).encrypt(iv, json.dumps(payload, separators=(",", ":")).encode(), None)
    return "BCN1." + base64.urlsafe_b64encode(wk + iv + ct).decode().rstrip("=")

def fingerprint(der): return ":".join("%02X" % b for b in hashlib.sha256(der).digest()[:6])

# ---- relay ----
def _line(s, limit=64):
    b = bytearray()
    while not b.endswith(b"\n"):
        c = s.recv(1)
        if not c: raise ConnectionError("relay closed the connection")
        b += c
        if len(b) > limit: raise ConnectionError("relay protocol error")
    return bytes(b)

LOOPBACK = ("127.0.0.1", "::1", "localhost")
def require_loopback_for_plain(host):
    """--plain (no TLS) is for local tests only: never send the relay token in cleartext to a real host."""
    if str(host).strip().lower().strip("[]") not in LOOPBACK:
        raise SystemExit("--plain is only allowed for loopback relay hosts (127.0.0.1, ::1, localhost)")

def connect(cfg, plain=False, timeout=10):
    if plain: require_loopback_for_plain(cfg["h"])
    raw = socket.create_connection((cfg["h"], cfg["p"]), timeout=timeout)
    raw.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    if plain: return raw
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE
    ctx.minimum_version = ssl.TLSVersion.TLSv1_3
    s = ctx.wrap_socket(raw, server_hostname=None)
    got = hashlib.sha256(s.getpeercert(binary_form=True)).hexdigest()
    if not hmac.compare_digest(got, cfg["f"]): s.close(); raise ConnectionError("relay certificate pin mismatch")
    return s

def join(cfg, key, plain=False):
    """Returns a spliced socket, or None if the phone isn't waiting right now (relay says nopeer after ~20 s)."""
    s = connect(cfg, plain)
    s.sendall(("MSMS-RELAY/1 %s %s pc\n" % (cfg["t"], room(key))).encode()); s.settimeout(40)
    while True:
        l = _line(s)
        if l == b"OK paired\n": break
        if l in (b"OK wait\n", b"PONG\n"): continue
        s.close()
        if l.strip() == b"ERR nopeer": return None
        if l.strip() == b"ERR auth": raise SystemExit("relay token rejected")
        raise ConnectionError("relay: " + l.decode(errors="replace").strip())
    s.sendall(b"GO\n"); s.settimeout(120); return s

def _read_exact(s, n):
    b = bytearray()
    while len(b) < n:
        c = s.recv(n - len(b))
        if not c: raise ConnectionError("link closed")
        b += c
    return bytes(b)
def frame_w(s, b): s.sendall(struct.pack(">I", len(b)) + b)
def frame_r(s):
    (n,) = struct.unpack(">I", _read_exact(s, 4))
    if n > 8 * 1024 * 1024: raise ConnectionError("frame too large")
    return _read_exact(s, n)

class Session:
    def __init__(self, s, key):
        self.s = s; nc = os.urandom(16); frame_w(s, MAGIC + nc)
        h = frame_r(s)
        if len(h) != 20 or h[:4] != MAGIC: raise ConnectionError("phone sent a bad hello")
        kt, kr = keys(key, nc, h[4:]); self.tx, self.rx, self.id = Dir(kt), Dir(kr), 0
    def call(self, m, p=None):
        self.id += 1
        frame_w(self.s, self.tx.seal(json.dumps({"id": self.id, "m": m, "p": p or {}}, separators=(",", ":")).encode()))
        r = json.loads(self.rx.open(frame_r(self.s)))
        if r.get("id") != self.id: raise ConnectionError("out of order reply")
        if "err" in r: raise RuntimeError("%s: %s" % (r["err"].get("code"), r["err"].get("msg")))
        return r["ok"]

# ---- commands ----
def load_pair(home):
    p = os.path.join(home, "pair.json")
    if not os.path.exists(p): raise SystemExit("not paired: run `beacon-update pair <phone key>` first")
    with open(p) as f: d = json.load(f)
    return d, unprotect(d["kind"], d["key"])

def cmd_pair(a):
    raw = a.phone_key
    if raw.startswith("@"):
        with open(raw[1:]) as f: raw = f.read()
    der = base64.b64decode("".join(raw.split()))
    cfg = relay_cfg(msms_identity(a.msms_identity))
    if a.relay_host: cfg["h"] = a.relay_host
    if a.relay_port: cfg["p"] = a.relay_port
    if a.plain: require_loopback_for_plain(cfg["h"])
    key = os.urandom(32); kind, blob = protect(key)
    home = home_dir(a.home)
    name = (a.name or socket.gethostname())[:40]
    _save(os.path.join(home, "pair.json"), {"v": 1, "kind": kind, "key": blob, "phone_fp": fingerprint(der), "pc": name, "relay": {"h": cfg["h"], "p": cfg["p"], "f": cfg["f"]}, "plain": bool(a.plain)})
    code = seal_pairing(der, {**cfg, "k": base64.b64encode(key).decode(), "pc": name})
    if a.out:
        with open(a.out, "w") as f: f.write(code)
    print(json.dumps({"ok": True, "phone_fingerprint": fingerprint(der), "pairing_code": code if not a.out else None, "written_to": a.out,
                      "note": "sealed to the phone's key; paste into Beacon > Settings > Updates > Pair PC for updates"}))
    return 0

def cmd_push(a):
    path = a.apk; size = os.path.getsize(path)
    if not 0 < size <= MAX_APK: raise SystemExit("APK size out of range")
    with open(path, "rb") as f:
        if f.read(4) != b"PK\x03\x04": raise SystemExit("not an APK (zip) file")
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""): h.update(b)
    digest = h.hexdigest()
    home = home_dir(a.home); d, key = load_pair(home)
    cfg = relay_cfg(msms_identity(a.msms_identity)); r = d.get("relay") or {}
    cfg["h"], cfg["p"] = r.get("h", cfg["h"]), r.get("p", cfg["p"])
    plain = bool(d.get("plain"))
    end = time.time() + a.wait; s = None; tries = 0
    while s is None:
        tries += 1
        s = join(cfg, key, plain)
        if s is None:
            if time.time() > end: print(json.dumps({"ok": False, "error": "phone_not_waiting", "tries": tries, "hint": "phone offline, Remote updates off, or Beacon not paired"})); return 3
            print("phone not on the relay yet, retrying...", file=sys.stderr); time.sleep(5)
    try:
        sess = Session(s, key)
        info = sess.call("info")
        print("phone has %s %s (code %s)" % (info.get("package"), info.get("name"), info.get("code")), file=sys.stderr)
        off = int(sess.call("begin", {"sha256": digest, "size": size})["offset"]); t0 = time.time()
        with open(path, "rb") as f:
            while off < size:
                f.seek(off); data = f.read(CHUNK)
                off = int(sess.call("chunk", {"sha256": digest, "offset": off, "d": base64.b64encode(data).decode()})["offset"])
                print("\r%5.1f%% %d/%d" % (100.0 * off / size, off, size), end="", file=sys.stderr, flush=True)
        print("", file=sys.stderr)
        res = sess.call("finish", {"sha256": digest})
        try: sess.call("bye")
        except Exception: pass
        res.update(ok=True, sha256=digest, size=size, seconds=round(time.time() - t0, 1), was=info)
        print(json.dumps(res)); return 0
    except RuntimeError as e:
        print(json.dumps({"ok": False, "error": str(e)})); return 4
    finally:
        try: s.close()
        except Exception: pass

def cmd_status(a):
    p = os.path.join(home_dir(a.home), "pair.json")
    if not os.path.exists(p): print(json.dumps({"paired": False})); return 0
    with open(p) as f: d = json.load(f)
    print(json.dumps({"paired": True, "pc": d.get("pc"), "phone_fingerprint": d.get("phone_fp"), "relay_host": (d.get("relay") or {}).get("h")})); return 0

def cmd_unpair(a):
    p = os.path.join(home_dir(a.home), "pair.json")
    if os.path.exists(p): os.remove(p)
    print(json.dumps({"paired": False})); return 0

def main(argv=None):
    ap = argparse.ArgumentParser(prog="beacon-update")
    ap.add_argument("--home"); ap.add_argument("--msms-identity")
    sp = ap.add_subparsers(dest="cmd", required=True)
    p = sp.add_parser("pair"); p.add_argument("phone_key"); p.add_argument("--out"); p.add_argument("--name")
    p.add_argument("--relay-host"); p.add_argument("--relay-port", type=int); p.add_argument("--plain", action="store_true", help=argparse.SUPPRESS)  # tests only; loopback hosts only
    q = sp.add_parser("push"); q.add_argument("apk"); q.add_argument("--wait", type=float, default=600)
    sp.add_parser("status"); sp.add_parser("unpair")
    a = ap.parse_args(argv)
    return {"pair": cmd_pair, "push": cmd_push, "status": cmd_status, "unpair": cmd_unpair}[a.cmd](a)

if __name__ == "__main__": sys.exit(main())
