# -*- coding: utf-8 -*-
"""把本地 web/ 上传到 cm01 的 site/community/"""
import os, sys, paramiko

HOST = os.environ.get("ZAOBA_HOST", "cm01.zy1080.xyz")
PORT = int(os.environ.get("ZAOBA_PORT", "22"))
USER = os.environ.get("ZAOBA_USER", "root")
PWD = os.environ.get("ZAOBA_PASS", "")
if not PWD:
    raise SystemExit("请先设置环境变量 ZAOBA_PASS（SSH 密码），不要在代码里写凭据")
LOCAL = "D:/zaoba-community/web"
REMOTE = "/opt/cm-pricesite/site/community"

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(HOST, port=PORT, username=USER, password=PWD, timeout=30,
          banner_timeout=30, auth_timeout=30, look_for_keys=False, allow_agent=False)
sftp = c.open_sftp()

def put_dir(local, remote):
    for name in os.listdir(local):
        lp = os.path.join(local, name)
        rp = remote + "/" + name
        if os.path.isdir(lp):
            try:
                sftp.mkdir(rp)
            except IOError:
                pass
            put_dir(lp, rp)
        else:
            sftp.put(lp, rp)
            print("->", rp, os.path.getsize(lp))

put_dir(LOCAL, REMOTE)
sftp.close()

_, out, _ = c.exec_command(
    "curl -s -o /dev/null -w 'community=%%{http_code}\\n' --max-time 8 http://127.0.0.1:39581/community; "
    "curl -s --max-time 8 http://127.0.0.1:39581/community/assets/app.js | grep -c 'hw/movers'; "
    "curl -s -o /dev/null -w 'css=%%{http_code}\\n' --max-time 8 http://127.0.0.1:39581/community/assets/styles.css", timeout=60)
print(out.read().decode("utf-8", "replace"))
c.close()
