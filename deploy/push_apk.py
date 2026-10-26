# -*- coding: utf-8 -*-
"""签名并推送 APK 到 cm01：zipalign -> apksigner -> sftp -> 验证"""
import os, re, subprocess, sys, paramiko

HOST = os.environ.get("ZAOBA_HOST", "cm01.zy1080.xyz")
PORT = int(os.environ.get("ZAOBA_PORT", "22"))
USER = os.environ.get("ZAOBA_USER", "root")
PWD = os.environ.get("ZAOBA_PASS", "")
if not PWD:
    raise SystemExit("请先设置环境变量 ZAOBA_PASS（SSH 密码），不要在代码里写凭据")
SDK = "D:/cm-pricesite/tools/android-sdk"
BT = SDK + "/build-tools/34.0.0"
JDK = "D:/cm-pricesite/tools/jdk17/jdk-17.0.20.1+1"
KS = "D:/zaoba-community/android/zaoba.keystore"
OUTDIR = "D:/zaoba-community/android/app/build/outputs/apk/release"

ver = sys.argv[1]            # 例 1.2.0
unsigned = OUTDIR + "/app-release-unsigned.apk"
aligned = OUTDIR + "/app-release-aligned.apk"
final = OUTDIR + "/zaoba-community-native-v%s.apk" % ver

env = dict(os.environ, JAVA_HOME=JDK, PATH=JDK + "/bin;" + os.environ.get("PATH", ""))

print("== 1) zipalign ==")
r = subprocess.run([BT + "/zipalign.exe", "-p", "-f", "4", unsigned, aligned],
                   capture_output=True, text=True)
print(r.stdout.strip() or "ok", r.stderr.strip())
if r.returncode != 0:
    sys.exit("zipalign failed")

print("== 2) apksigner ==")
# ★ 必须显式传 --min-sdk-version：否则 apksigner 从 manifest 读到 minSdk>=24
#   就认为 v1 不必要而**静默跳过 v1 签名**，导致部分国产 ROM / 侧载渠道报
#   「解析软件包时出现问题」。这里固定按 API 21 生成，保证 v1+v2+v3 全齐。
r = subprocess.run([BT + "/apksigner.bat", "sign",
                    "--ks", KS, "--ks-pass", "pass:zaoba123",
                    "--key-pass", "pass:zaoba123",
                    "--min-sdk-version", "21",
                    "--v1-signing-enabled", "true",
                    "--v2-signing-enabled", "true",
                    "--v3-signing-enabled", "true",
                    "--out", final, aligned],
                   capture_output=True, text=True, env=env, shell=False)
print(r.stdout.strip() or "ok", r.stderr.strip())
if r.returncode != 0 or not os.path.exists(final):
    sys.exit("apksigner failed")

print("== 3) verify ==")
r = subprocess.run([BT + "/apksigner.bat", "verify", "--min-sdk-version", "21",
                    "--verbose", "--print-certs", final],
                   capture_output=True, text=True, env=env)
out = r.stdout
print(out.strip()[:600])
# 硬校验：三种签名方案必须全部有效，否则直接失败，不推坏包
for scheme in ["v1 scheme (JAR signing)", "v2 scheme", "v3 scheme"]:
    line = [l for l in out.splitlines() if scheme in l]
    if not line or "true" not in line[0]:
        sys.exit("签名校验失败：%s 未生效 → %s" % (scheme, line))
if "Verifies" not in out:
    sys.exit("签名校验失败：apksigner verify 未通过")
print("[OK] v1 + v2 + v3 全部有效")
size = os.path.getsize(final)
print("apk size:", size)

print("== 4) upload ==")
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(HOST, port=PORT, username=USER, password=PWD, timeout=30,
          banner_timeout=30, auth_timeout=30, look_for_keys=False, allow_agent=False)
sftp = c.open_sftp()
remote = "/opt/cm-pricesite/site/downloads/" + os.path.basename(final)
sftp.put(final, remote)
print("uploaded ->", remote, sftp.stat(remote).st_size)
sftp.close()

print("== 5) verify http ==")
_, out, _ = c.exec_command("curl -s -o /dev/null -w 'app_page=%%{http_code}\\n' --max-time 8 http://127.0.0.1:39581/app; "
                           "curl -s -o /dev/null -w 'apk=%%{http_code} size=%%{size_download}\\n' --max-time 20 http://127.0.0.1:39581/downloads/%s; "
                           "ls -la /opt/cm-pricesite/site/downloads/" % os.path.basename(final), timeout=120)
print(out.read().decode("utf-8", "replace"))
c.close()
