#!/usr/bin/env bash
# Pushes the device-side stack to an Echo Show over adb and (re)starts it.
# Usage: ./deploy.sh <device-ip:port>     e.g. ./deploy.sh 192.168.1.10:5555
# Needs: adb with root (adb root), a writable /system (adb remount), the Debian
# proot-distro container with Soloist at /root/soloist, and config.env already
# created on the device (see config.env.example).
set -euo pipefail
cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1
DEV=${1:?usage: deploy.sh <device-ip:port>}
A="adb -s $DEV"
BASE=/data/local/echo-spotify
ROOTFS=/data/data/com.termux/files/usr/var/lib/proot-distro/containers/debian/rootfs

$A root >/dev/null
sleep 2
adb connect "$DEV" >/dev/null || true

$A shell "mkdir -p $BASE && chmod 700 $BASE"
for f in start_all.sh start_pw.sh as_termux.py soloist_watchdog.py; do
  $A push "$f" "$BASE/$f" >/dev/null
done
$A shell "chmod 700 $BASE/*.sh"
if ! $A shell "test -f $BASE/config.env"; then
  echo "Missing $BASE/config.env on the device. Copy config.env.example there, fill in your key, chmod 600." >&2
  exit 1
fi

$A shell "mkdir -p $ROOTFS/etc/pipewire/pipewire.conf.d $ROOTFS/etc/wireplumber/wireplumber.conf.d"
$A push pipewire/10-echo-tunnel.conf "$ROOTFS/etc/pipewire/pipewire.conf.d/10-echo-tunnel.conf" >/dev/null
$A push pipewire/10-chroot.conf "$ROOTFS/etc/wireplumber/wireplumber.conf.d/10-chroot.conf" >/dev/null

$A remount >/dev/null
$A push echo-spotify.rc /system/etc/init/echo-spotify.rc >/dev/null
$A shell "chmod 644 /system/etc/init/echo-spotify.rc && chcon u:object_r:system_file:s0 /system/etc/init/echo-spotify.rc"

$A shell "stop echo_spotify; start echo_spotify"
echo "Deployed. Boot log: $BASE/boot.log   Watchdog log: $BASE/watchdog.log"
