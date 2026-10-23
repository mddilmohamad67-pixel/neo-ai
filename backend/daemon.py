"""Double-fork launcher so the backend survives the shell that started it."""
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PIDFILE = os.path.join(HERE, "logs", "backend.pid")

if os.fork() > 0:
    sys.exit(0)
os.setsid()
if os.fork() > 0:
    sys.exit(0)

os.chdir(HERE)
os.umask(0o077)
with open(PIDFILE, "w") as fh:
    fh.write(str(os.getpid()))
null = os.open(os.devnull, os.O_RDWR)
os.dup2(null, 0)
os.dup2(null, 1)
os.dup2(null, 2)
bash = shutil.which("bash") or "/data/data/com.termux/files/usr/bin/bash"
os.execv(bash, [bash, os.path.join(HERE, "serve_forever.sh")])
