"""Keeps Spotify Soloist running inside the Debian chroot and advertises it on the LAN.

Soloist's built-in mDNS responder doesn't work on this device, so the Spotify
Connect record is published here with python-zeroconf (TXT record CPath only,
like real speakers). If Soloist stops answering its Zeroconf endpoint, it is
restarted and the record is updated to the new port.

Settings are read from /data/local/echo-spotify/config.env (KEY=VALUE lines).
"""
import http.client
import os
import re
import socket
import subprocess
import time

from zeroconf import ServiceInfo, Zeroconf

CONFIG_PATH = "/data/local/echo-spotify/config.env"
DEFAULT_ROOTFS = "/data/data/com.termux/files/usr/var/lib/proot-distro/containers/debian/rootfs"
WS_ADDR = "127.0.0.1:9090"
HEALTHCHECK_INTERVAL = 5
HEALTHCHECK_TIMEOUT = 10
FAILURES_BEFORE_RESTART = 3


def load_config(path):
    cfg = {}
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                cfg[key.strip()] = value.strip().strip('"').strip("'")
    return cfg


CFG = load_config(CONFIG_PATH)
API_KEY = CFG["SOLOIST_API_KEY"]
DEVICE_NAME = CFG.get("DEVICE_NAME", "Echo Show")
IFACE = CFG.get("NET_IFACE", "wlan0")
ROOTFS = CFG.get("ROOTFS", DEFAULT_ROOTFS)
DATA_DIR = ROOTFS + "/root/.local/share/soloist"
LOG_IN_ROOTFS = "/root/soloist_watch.log"
LOG_HOST = ROOTFS + LOG_IN_ROOTFS
HOSTNAME = re.sub(r"[^a-z0-9]+", "-", DEVICE_NAME.lower()).strip("-") + ".local."

zc = Zeroconf()
current_info = None
current_port = None
soloist_proc = None


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def device_ip():
    out = subprocess.run(["ip", "-4", "addr", "show", IFACE], capture_output=True, text=True).stdout
    m = re.search(r"inet (\d+\.\d+\.\d+\.\d+)/", out)
    return m.group(1) if m else None


def kill_soloist():
    global soloist_proc
    out = subprocess.run(["ps", "-A", "-o", "PID,ARGS"], capture_output=True, text=True).stdout
    pids = []
    for line in out.splitlines()[1:]:
        parts = line.split(None, 1)
        if len(parts) == 2 and "soloist" in parts[1] and "soloist_watchdog" not in parts[1]:
            pids.append(parts[0])
    for pid in pids:
        subprocess.run(["kill", "-9", pid])
    if pids:
        log(f"killed stale soloist pids: {pids}")
    if soloist_proc is not None:
        soloist_proc.poll()
        soloist_proc = None
    for name in (".lock", "soloist.pid", "cache/public.ldb/LOCK"):
        try:
            os.remove(os.path.join(DATA_DIR, name))
        except FileNotFoundError:
            pass


def start_soloist():
    global soloist_proc
    kill_soloist()
    time.sleep(1)
    logf = open(LOG_HOST, "a")
    soloist_proc = subprocess.Popen(
        ["chroot", ROOTFS, "/usr/bin/env", "-i",
         "PATH=/usr/bin:/bin:/usr/sbin:/sbin", "HOME=/root", "TMPDIR=/tmp", "XDG_RUNTIME_DIR=/run/user/0",
         "/root/soloist", "-v", "--device-name", DEVICE_NAME, "--api-key", API_KEY, "--ws", WS_ADDR],
        stdin=subprocess.DEVNULL, stdout=logf, stderr=subprocess.STDOUT, start_new_session=True,
    )
    logf.close()
    for _ in range(20):
        time.sleep(0.5)
        result = read_port_from_log()
        if result:
            return result
    return None


def read_port_from_log():
    try:
        with open(LOG_HOST, "r", errors="replace") as f:
            content = f.read()
    except FileNotFoundError:
        return None
    m = None
    for m in re.finditer(r"Started ZeroConf service on port (\d+) path (\S+)", content):
        pass
    if not m:
        return None
    return int(m.group(1)), re.sub(r"\x1b\[[0-9;]*m", "", m.group(2))


def update_advertiser(port, path, ip):
    global current_info, current_port
    info = ServiceInfo(
        "_spotify-connect._tcp.local.",
        f"{DEVICE_NAME}._spotify-connect._tcp.local.",
        addresses=[socket.inet_aton(ip)],
        port=port,
        properties={"CPath": path},
        server=HOSTNAME,
    )
    if current_info is None:
        zc.register_service(info)
    else:
        try:
            zc.update_service(info)
        except Exception as e:
            log(f"update_service failed ({e}), re-registering")
            try:
                zc.unregister_service(current_info)
            except Exception:
                pass
            time.sleep(1)
            zc.register_service(info)
    current_info = info
    current_port = port
    log(f"advertising {ip}:{port}{path}")


def healthcheck(ip, port):
    try:
        conn = http.client.HTTPConnection(ip, port, timeout=HEALTHCHECK_TIMEOUT)
        conn.request("GET", "/zc?action=getInfo")
        resp = conn.getresponse()
        resp.read()
        conn.close()
        return resp.status == 200
    except Exception:
        return False


def main():
    ip = device_ip()
    while ip is None:
        log(f"waiting for an IPv4 address on {IFACE}")
        time.sleep(5)
        ip = device_ip()

    result = start_soloist()
    if not result:
        log("FATAL: soloist never produced a zeroconf port on first launch")
        return
    update_advertiser(*result, ip)
    failures = 0
    checks = 0

    while True:
        try:
            time.sleep(HEALTHCHECK_INTERVAL)
            new_ip = device_ip()
            if new_ip and new_ip != ip:
                ip = new_ip
                update_advertiser(current_port, current_info.properties[b"CPath"].decode(), ip)
            if healthcheck(ip, current_port):
                if failures:
                    log(f"recovered after {failures} failed check(s)")
                failures = 0
                if checks % 60 == 0:
                    log(f"healthy (port {current_port})")
                checks += 1
                continue
            failures += 1
            log(f"health check failed ({failures}/{FAILURES_BEFORE_RESTART}) on port {current_port}")
            if failures < FAILURES_BEFORE_RESTART:
                continue
            log("unhealthy, restarting soloist")
            failures = 0
            result = start_soloist()
            if not result:
                log("restart did not produce a port, retrying next cycle")
                continue
            update_advertiser(*result, ip)
        except Exception as e:
            log(f"loop exception (survived): {e!r}")
            time.sleep(HEALTHCHECK_INTERVAL)


if __name__ == "__main__":
    main()
