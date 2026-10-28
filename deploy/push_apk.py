# -*- coding: utf-8 -*-
"""签名并推送 APK 到 cm01。

流程：zipalign → apksigner(v1+v2+v3，强制 --min-sdk-version 21) → 硬校验 → sftp → HTTP 验证

★ 血泪教训（2026-10-26）
  apksigner 不传 --min-sdk-version 时，会从 manifest 读 minSdk，
  读到 >=24 就认为 v1 不必要而**静默跳过 v1 签名**（META-INF 里仍生成
  .SF/.RSA，但 verify 报 v1=false）。国产 ROM / 侧载渠道校验 JAR 签名，
  缺 v1 直接报「解析软件包时出现问题 / 应用未安装」。
  所以这里固定按 API 21 签名，并硬校验三方案全部为 true。
"""
import os, subprocess, sys, paramiko

HOST = os.environ.get("ZAOBA_HOST", "cm01.zy1080.xyz")
PORT = int(os.environ.get("ZAOBA_PORT", "22"))
USER = os.environ.get("ZAOBA_USER", "root")
PWD = os.environ.get("ZAOBA_PASS", "")
if not PWD:
    raise SystemExit("请先设置环境变量 ZAOBA_PASS（SSH 密码），不要在代码里写凭据")
SDK = "D:/cm-pricesite/tools/android-sdk"
BT = SDK + "/build-tools/34.0.0"
JDK = "D:/cm-pricesite/tools/jdk17/jdk-17.0.20.1+1"
KS = os.environ.get("CHAOMI_KS", "")
KS_PASS = os.environ.get("CHAOMI_KS_PASS", "")
KEY_ALIAS = os.environ.get("CHAOMI_KS_ALIAS", "chaomi")
if not KS or not KS_PASS://n    raise SystemExit("请设置 CHAOMI_KS（keystore 路径）和 CHAOMI_KS_PASS（口令），不要把凭据写进代码")
EXPECT_CN = "CN=Chaomi"
OUTDIR = "D:/zaoba-community/android/app/build/outputs/apk/release"
PREFIX = "chaomi"

ver = sys.argv[1] if len(sys.argv) > 1 else "4.0.0"
unsigned = OUTDIR + "/app-release-unsigned.apk"
aligned = OUTDIR + "/app-release-aligned.apk"
final = OUTDIR + "/%s-v%s.apk" % (PREFIX, ver)

env = dict(os.environ, JAVA_HOME=JDK, PATH=JDK + "/bin;" + os.environ.get("PATH", ""))

if not os.path.exists(unsigned):
    sys.exit("找不到未签名包：%s（先跑 gradle assembleRelease）" % unsigned)
if not os.path.exists(KS):
    sys.exit("找不到签名文件：%s" % KS)

print("== 1) zipalign ==")
r = subprocess.run([BT + "/zipalign.exe", "-p", "-f", "4", unsigned, aligned],
                   capture_output=True, text=True)
print(r.stdout.strip() or "ok", r.stderr.strip())
if r.returncode != 0:
    sys.exit("zipalign failed")

print("== 2) apksigner（新密钥 %s）==" % KEY_ALIAS)
r = subprocess.run([BT + "/apksigner.bat", "sign",
                    "--ks", KS, "--ks-key-alias", KEY_ALIAS,
                    "--ks-pass", "pass:" + KS_PASS,
                    "--key-pass", "pass:" + KS_PASS,
                    "--min-sdk-version", "21",
                    "--v1-signing-enabled", "true",
                    "--v2-signing-enabled", "true",
                    "--v3-signing-enabled", "true",
                    "--out", final, aligned],
                   capture_output=True, text=True, env=env, shell=False)
print(r.stdout.strip() or "ok", r.stderr.strip())
if r.returncode != 0 or not os.path.exists(final):
    sys.exit("apksigner failed")

print("== 3) 硬校验 ==")
r = subprocess.run([BT + "/apksigner.bat", "verify", "--min-sdk-version", "21",
                    "--verbose", "--print-certs", final],
                   capture_output=True, text=True, env=env)
out = r.stdout
print(out.strip()[:700])
for scheme in ["v1 scheme (JAR signing)", "v2 scheme", "v3 scheme"]:
    line = [l for l in out.splitlines() if scheme in l]
    if not line or "true" not in line[0]:
        sys.exit("✗ 签名校验失败：%s 未生效 → %s" % (scheme, line))
if "Verifies" not in out:
    sys.exit("✗ 签名校验失败：apksigner verify 未通过")
if EXPECT_CN not in out:
    sys.exit("✗ 签名校验失败：证书不是 %s（可能签错密钥）" % EXPECT_CN)

# zipalign 复核
r = subprocess.run([BT + "/zipalign.exe", "-c", "-v", "4", final],
                   capture_output=True, text=True)
if r.returncode != 0:
    sys.exit("✗ zipalign 复核未通过")
print("[OK] v1 + v2 + v3 全部有效，证书 = %s，对齐通过" % EXPECT_CN)

# 汇总证书指纹
for l in out.splitlines():
    if "SHA-256 digest" in l or "certificate DN" in l:
        print("   ", l.strip())
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

print("== 5) 清理旧包 + HTTP 验证 ==")
cmd = (
    "cd /opt/cm-pricesite/site/downloads && "
    "ls -1 *.apk 2>/dev/null | grep -v '^%s-v%s.apk$' | xargs -r rm -f; "
    "echo '--- 现存包 ---'; ls -la; "
    "echo '--- 下载页 ---'; curl -s --max-time 8 http://127.0.0.1:39581/app | grep -oE 'downloads/[a-z0-9.-]*apk|版本 [0-9.]+'; "
    "echo '--- APK HTTP ---'; curl -s -o /dev/null -w 'apk=%%{http_code} size=%%{size_download}\\n' --max-time 30 http://127.0.0.1:39581/downloads/%s"
) % (PREFIX, ver, os.path.basename(final))
_, o, _ = c.exec_command(cmd, timeout=180)
print(o.read().decode("utf-8", "replace"))
c.close()
