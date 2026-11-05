# Starts PipeWire + WirePlumber inside the Debian chroot (audio is tunnelled to Termux PulseAudio).
D=/data/data/com.termux/files/usr/var/lib/proot-distro/containers/debian/rootfs
for p in $(ps -A -o PID,ARGS | awk '$2 ~ /(pipewire|wireplumber)$/ {print $1}'); do kill -9 $p; done
mkdir -p $D/run/user/0
chmod 700 $D/run/user/0
rm -f $D/run/user/0/pipewire-0*
ENV="/usr/bin/env -i PATH=/usr/bin:/bin:/usr/sbin:/sbin HOME=/root TMPDIR=/tmp XDG_RUNTIME_DIR=/run/user/0"
chroot $D $ENV /bin/bash -c '(/usr/bin/pipewire > /root/pipewire.log 2>&1 &)' </dev/null >/dev/null 2>&1
sleep 2
chroot $D $ENV /bin/bash -c '(/usr/bin/wireplumber > /root/wireplumber.log 2>&1 &)' </dev/null >/dev/null 2>&1
sleep 4
chroot $D $ENV /bin/bash -c 'pw-cli ls Node 2>&1 | grep -E "node.name|node.description|media.class"; echo ---; wpctl status 2>&1 | sed -n "1,30p"'
echo "--- pipewire.log ---"
tail -8 $D/root/pipewire.log
echo "--- wireplumber.log ---"
tail -8 $D/root/wireplumber.log
