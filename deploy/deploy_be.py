# -*- coding: utf-8 -*-
"""部署后端到 cm01：上传 + 备份 + 重启 + 验证"""
import sys, time, paramiko

HOST = os.environ.get("ZAOBA_HOST", "cm01.zy1080.xyz")
PORT = int(os.environ.get("ZAOBA_PORT", "22"))
USER = os.environ.get("ZAOBA_USER", "root")
PWD = os.environ.get("ZAOBA_PASS", "")
if not PWD:
    raise SystemExit("请先设置环境变量 ZAOBA_PASS（SSH 密码），不要在代码里写凭据")


def connect():
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, port=PORT, username=USER, password=PWD, timeout=30,
              banner_timeout=30, auth_timeout=30, look_for_keys=False, allow_agent=False)
    return c


def run(c, cmd, timeout=180):
    _, out, err = c.exec_command(cmd, timeout=timeout)
    o = out.read().decode("utf-8", "replace")
    e = err.read().decode("utf-8", "replace")
    rc = out.channel.recv_exit_status()
    if o:
        sys.stdout.write(o)
    if e.strip():
        sys.stdout.write("[STDERR] " + e)
    return rc


def run_noread(c, cmd):
    """启动守护进程用：不读 stdout，避免 channel 被 hold 导致 SIGTERM"""
    t = c.get_transport()
    ch = t.open_session()
    ch.exec_command(cmd)
    time.sleep(1)
    ch.close()


local = sys.argv[1]
remote_tmp = "/tmp/cmpricesite.new"

c = connect()
try:
    print("== 1) 上传 ==")
    sftp = c.open_sftp()
    sftp.put(local, remote_tmp)
    st = sftp.stat(remote_tmp)
    print("uploaded %s bytes" % st.st_size)
    sftp.close()

    print("== 2) 备份 + 停旧进程 + 替换 ==")
    run(c, "cd /opt/cm-pricesite && cp bin/cmpricesite bin/cmpricesite.bak-fuse-$(date +%Y%m%d-%H%M%S) && "
           "PID=$(ss -ltnp 2>/dev/null | grep ':39581' | sed -n 's/.*pid=\\([0-9]*\\).*/\\1/p' | head -1) && "
           "echo \"listener pid=$PID\" && "
           "if [ -n \"$PID\" ]; then kill -9 $PID; fi && sleep 1 && "
           "cp /tmp/cmpricesite.new bin/cmpricesite && chmod +x bin/cmpricesite && "
           "ls -la bin/cmpricesite && "
           "ss -ltnp 2>/dev/null | grep ':39581' || echo 'port free'")
finally:
    c.close()

print("== 3) 启动 ==")
c = connect()
try:
    # ★ 必须先把 cm01.env / db.conf 注入环境再启动：
    #   setsid 起来的进程不会自动读 env 文件，否则 HW_CRAWLER_KEY / CM_DB_DSN 全为空。
    start_cmd = (
        "cd /opt/cm-pricesite && "
        "ENV_ARGS=''; "
        "if [ -f cm01.env ]; then set -a; . ./cm01.env; set +a; fi; "
        "if [ -z \"$CM_DB_DSN\" ] && [ -f db.conf ]; then "
        "  export CM_DB_DSN=\"$(cat db.conf)\"; fi; "
        "setsid ./bin/cmpricesite serve > bin/serve.log 2>&1 < /dev/null &"
    )
    run_noread(c, start_cmd)
finally:
    c.close()

print("== 4) 验证 ==")
c = connect()
try:
    run(c, "sleep 2; curl -s --max-time 6 http://127.0.0.1:39581/api/hw/status; echo; "
           "echo '--- cats ---'; curl -s --max-time 6 http://127.0.0.1:39581/api/hw/cats; echo; "
           "echo '--- models count ---'; curl -s --max-time 8 'http://127.0.0.1:39581/api/hw/models' | head -c 300; echo")
finally:
    c.close()
