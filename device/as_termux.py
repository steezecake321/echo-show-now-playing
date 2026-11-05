import os
import sys

P = "/data/data/com.termux/files"
st = os.stat(P + "/home")
uid, gid = st.st_uid, st.st_gid
app_id = uid % 100000
os.setgroups([gid, 3003, 9997, 20000 + app_id, 50000 + app_id])
os.setgid(gid)
os.setuid(uid)
env = {
    "HOME": P + "/home",
    "PREFIX": P + "/usr",
    "PATH": P + "/usr/bin",
    "TMPDIR": P + "/usr/tmp",
    "LANG": "en_US.UTF-8",
    "TERM": "dumb",
    "DEBIAN_FRONTEND": "noninteractive",
}
os.chdir(P + "/home")
os.execve(P + "/usr/bin/bash", ["bash", "-c", sys.argv[1]], env)
