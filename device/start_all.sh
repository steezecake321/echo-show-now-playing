#!/system/bin/sh
# Boot entry point (run as root by /system/etc/init/echo-spotify.rc).
# Brings up: CP15 barrier fix -> chroot mounts -> Termux PulseAudio -> PipeWire -> Soloist watchdog.
# Must not exit: init kills a service's whole process group when its main process ends,
# so this script finishes by exec'ing the watchdog.
BASE=/data/local/echo-spotify
LOG=$BASE/boot.log
T=/data/data/com.termux/files
PY=$T/usr/bin/python3
D=$T/usr/var/lib/proot-distro/containers/debian/rootfs

exec >> $LOG 2>&1
echo "===== start_all $(date) ====="

# Soloist's 32-bit ARM build uses ARMv6 CP15 barriers inside ldrex/strex loops. The 64-bit
# kernel emulates them by default (=1), each trap clears the exclusive monitor, and Soloist
# spins forever. 2 = execute them in hardware.
echo 2 > /proc/sys/abi/cp15_barrier
echo "cp15_barrier=$(cat /proc/sys/abi/cp15_barrier)"

for m in dev proc sys; do
  mountpoint -q $D/$m || mount -o bind /$m $D/$m
done
echo "chroot mounts: $(mount | grep -c "debian/rootfs/")"

i=0
until ip -4 addr show wlan0 | grep -q "inet " || [ $i -ge 120 ]; do
  sleep 2; i=$((i+1))
done
echo "wlan0: $(ip -4 addr show wlan0 | grep -o 'inet [0-9.]*')"

for p in $(ps -A -o PID,ARGS | grep -E "soloist_watchdog|/root/soloist|soloist --type" | grep -v grep | awk '{print $1}'); do
  kill -9 $p
done

$PY -I $BASE/as_termux.py 'pulseaudio --kill 2>/dev/null; pulseaudio --start --exit-idle-time=-1 --load="module-native-protocol-tcp auth-ip-acl=127.0.0.1 auth-anonymous=1 port=4713"'
sleep 2
echo "pulseaudio: $(ps -A -o ARGS | grep -c '^pulseaudio')"

sh $BASE/start_pw.sh > $BASE/start_pw.log 2>&1
echo "pipewire tunnel sink: $(grep -c 'Echo Show speaker' $BASE/start_pw.log)"

rm -f $D/root/soloist_watch.log
cd $BASE
echo "===== handing off to watchdog ====="
exec $PY -u $BASE/soloist_watchdog.py </dev/null > $BASE/watchdog.log 2>&1
