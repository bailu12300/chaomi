<?php
/**
 * 超米 · 服务监控看板
 *
 * 访问路径：https://cm01.zy1080.xyz/monitor/
 *
 * 为什么用 PHP：
 *   纯展示页 + 画图表，不需要参与核心业务。跑在 PHP-FPM 上，
 *   和 Go 主服务完全隔离——主服务挂了监控页照样能开（这恰恰是监控的意义）。
 *   直接读 MySQL 里 Go 巡检写下的数据，不重复探测，也不消耗额外流量。
 *
 * 设计要点：
 *   · 零外部依赖：不引 CDN、不引图表库，SVG 手绘，内网也能开
 *   · 前后端同源，数据全部来自本机 MySQL，不会因为外网断而白屏
 *   · 浅色为主，跟站点风格一致
 */

declare(strict_types=1);

// ===== 数据库配置 =====
// 凭据从站点 env 文件读，不硬编码（该文件不在 web 根目录）
// 优先读服务端统一配置；PHP-FPM 以 www 身份运行可能读不到（600 root），
// 这时用下面的默认凭据——它们只对 localhost socket 有效，不暴露到外网。
$cfgFile = '/opt/cm-pricesite/db.conf';
$dsnRaw = '';
foreach ([$cfgFile, '/opt/cm-pricesite/db.conf.readable'] as $f) {
    if (is_readable($f)) {
        $dsnRaw = trim((string)file_get_contents($f));
        if ($dsnRaw !== '') break;
    }
}

// db.conf 格式：user:pass@unix(/tmp/mysql.sock)/dbname?charset=utf8mb4
$dbUser = 'cmprice';
$dbPass = '2U0uEkd1HZTOaMa01BDz';
$dbName = 'cm_pricesite';
$dbHost = '127.0.0.1';
$dbPort = 3306;

if ($dsnRaw !== '' && preg_match('#^([^:]+):([^@]*)@(?:unix\(([^)]+)\)|tcp\(([^)]+)\))?/?([^?]+)#', $dsnRaw, $m)) {
    $dbUser = $m[1];
    $dbPass = $m[2];
    if (!empty($m[3])) {
        // unix socket：PDO 用 host=localhost + unix_socket
        $dbHost = 'localhost';
    } elseif (!empty($m[4])) {
        $parts = explode(':', $m[4]);
        $dbHost = $parts[0];
        if (isset($parts[1])) $dbPort = (int)$parts[1];
    }
    if (!empty($m[5])) $dbName = $m[5];
}

$pdo = null;
$dbError = '';
try {
    $opts = [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
        PDO::ATTR_TIMEOUT => 3,
    ];
    if ($dbHost === 'localhost') {
        $pdo = new PDO("mysql:unix_socket=/tmp/mysql.sock;dbname={$dbName};charset=utf8mb4",
            $dbUser, $dbPass, $opts);
    } else {
        $pdo = new PDO("mysql:host={$dbHost};port={$dbPort};dbname={$dbName};charset=utf8mb4",
            $dbUser, $dbPass, $opts);
    }
} catch (Throwable $e) {
    $dbError = $e->getMessage();
}

// ===== 取数据 =====
$targets = [];
$events = [];
$summary = ['up' => 0, 'down' => 0, 'total' => 0, 'avgMs' => 0];

if ($pdo) {
    try {
        $targets = $pdo->query(
            "SELECT target_key,name,ok,status,latency_ms,err_msg,fail_streak,checked_at
             FROM watch_status ORDER BY ok ASC, name ASC"
        )->fetchAll();

        $events = $pdo->query(
            "SELECT target_key,name,kind,detail,created_at FROM watch_events
             ORDER BY id DESC LIMIT 30"
        )->fetchAll();

        $sumMs = 0;
        $cnt = 0;
        foreach ($targets as $t) {
            if ((int)$t['ok'] === 1) {
                $summary['up']++;
                $sumMs += (int)$t['latency_ms'];
                $cnt++;
            } else {
                $summary['down']++;
            }
        }
        $summary['total'] = count($targets);
        $summary['avgMs'] = $cnt > 0 ? (int)round($sumMs / $cnt) : 0;
    } catch (Throwable $e) {
        $dbError = $e->getMessage();
    }
}

/**
 * 取某目标最近 N 个探测点
 */
function history(PDO $pdo, string $key, int $limit = 90): array
{
    try {
        $st = $pdo->prepare(
            "SELECT checked_at,ok,latency_ms FROM watch_history
             WHERE target_key=? ORDER BY id DESC LIMIT ?"
        );
        $st->bindValue(1, $key, PDO::PARAM_STR);
        $st->bindValue(2, $limit, PDO::PARAM_INT);
        $st->execute();
        return array_reverse($st->fetchAll());
    } catch (Throwable $e) {
        return [];
    }
}

/**
 * 生成延迟折线 SVG
 *
 * 手写 SVG 而不是引图表库：零外部请求，页面秒开，内网也能用。
 */
function sparkline(array $pts, int $w = 220, int $h = 36): string
{
    if (count($pts) < 2) {
        return '<svg width="' . $w . '" height="' . $h . '"><text x="4" y="22" '
            . 'font-size="10" fill="#8b949e">数据采集中…</text></svg>';
    }
    $maxMs = 1;
    foreach ($pts as $p) {
        $maxMs = max($maxMs, (int)$p['latency_ms']);
    }
    $maxMs = (int)ceil($maxMs * 1.25);
    $n = count($pts);
    $step = $w / max(1, $n - 1);

    $line = '';
    $area = '';
    $downDots = '';
    foreach ($pts as $i => $p) {
        $x = round($i * $step, 1);
        $ok = (int)$p['ok'] === 1;
        $ms = (int)$p['latency_ms'];
        // 故障点画在顶部，视觉上一眼看出中断
        $y = $ok ? round($h - ($ms / $maxMs) * ($h - 6) - 3, 1) : 3;
        $line .= ($i === 0 ? 'M' : 'L') . $x . ' ' . $y . ' ';
        if ($i === 0) $area = 'M' . $x . ' ' . $h . ' L' . $x . ' ' . $y . ' ';
        else $area .= 'L' . $x . ' ' . $y . ' ';
        if (!$ok) {
            $downDots .= '<circle cx="' . $x . '" cy="' . $y . '" r="2.2" fill="#cf222e"/>';
        }
    }
    $area .= 'L' . round(($n - 1) * $step, 1) . ' ' . $h . ' Z';

    return '<svg width="' . $w . '" height="' . $h . '" viewBox="0 0 ' . $w . ' ' . $h . '" '
        . 'preserveAspectRatio="none" style="display:block">'
        . '<path d="' . $area . '" fill="rgba(88,166,255,.13)"/>'
        . '<path d="' . $line . '" fill="none" stroke="#0969da" stroke-width="1.6" '
        . 'stroke-linejoin="round" stroke-linecap="round"/>'
        . $downDots
        . '</svg>';
}

function h(?string $s): string
{
    return htmlspecialchars((string)$s, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
}

function ago(int $ts): string
{
    if ($ts <= 0) return '—';
    $d = (int)round(microtime(true) * 1000) - $ts;
    if ($d < 0) $d = 0;
    if ($d < 5000) return '刚刚';
    if ($d < 60000) return (int)($d / 1000) . ' 秒前';
    if ($d < 3600000) return (int)($d / 60000) . ' 分钟前';
    if ($d < 86400000) return (int)($d / 3600000) . ' 小时前';
    return date('m-d H:i', (int)($ts / 1000));
}

$allOk = $summary['down'] === 0 && $summary['total'] > 0;
?>
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#0969da">
<title>超米 · 服务监控</title>
<style>
:root{
  --bg:#f6f8fa; --surface:#ffffff; --surface2:#eef1f5; --line:#d8dee4;
  --txt:#1f2328; --mut:#6a737d; --acc:#0969da; --ok:#1a7f37; --bad:#cf222e; --warn:#9a6700;
}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--txt);
  font:14px/1.65 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"PingFang SC","Microsoft YaHei",sans-serif;
  -webkit-text-size-adjust:100%}
.wrap{max-width:1080px;margin:0 auto;padding:18px 16px 48px}
header{display:flex;align-items:center;gap:12px;margin-bottom:16px;flex-wrap:wrap}
h1{font-size:20px;margin:0;font-weight:800;letter-spacing:.3px}
.badge{font-size:12px;padding:3px 10px;border-radius:999px;font-weight:600}
.badge.ok{background:rgba(26,127,55,.12);color:var(--ok)}
.badge.bad{background:rgba(207,34,46,.12);color:var(--bad)}
.muted{color:var(--mut);font-size:12.5px}
.spacer{flex:1}
.btn{background:var(--surface);border:1px solid var(--line);color:var(--txt);
  padding:6px 14px;border-radius:8px;font-size:12.5px;cursor:pointer;text-decoration:none}
.btn:hover{border-color:var(--acc);color:var(--acc)}

.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:10px;margin-bottom:16px}
.card{background:var(--surface);border:1px solid var(--line);border-radius:12px;padding:14px 16px}
.card .k{color:var(--mut);font-size:12px}
.card .v{font-size:26px;font-weight:800;font-variant-numeric:tabular-nums;margin-top:2px}
.card .v.ok{color:var(--ok)} .card .v.bad{color:var(--bad)} .card .v.acc{color:var(--acc)}

.panel{background:var(--surface);border:1px solid var(--line);border-radius:12px;
  overflow:hidden;margin-bottom:16px}
.panel h2{font-size:14px;margin:0;padding:12px 16px;border-bottom:1px solid var(--line);
  background:var(--surface2);font-weight:700}

table{width:100%;border-collapse:collapse;font-size:13px}
th,td{padding:10px 14px;text-align:left;border-bottom:1px solid var(--line);vertical-align:middle}
th{color:var(--mut);font-weight:600;font-size:12px;background:var(--surface);white-space:nowrap}
tr:last-child td{border-bottom:none}
tr:hover td{background:#fafbfc}
.dot{display:inline-block;width:8px;height:8px;border-radius:50%;margin-right:7px;vertical-align:middle}
.dot.ok{background:var(--ok);box-shadow:0 0 0 3px rgba(26,127,55,.15)}
.dot.bad{background:var(--bad);box-shadow:0 0 0 3px rgba(207,34,46,.15)}
.name{font-weight:600}
.ms{font-variant-numeric:tabular-nums;color:var(--mut)}
.ms.fast{color:var(--ok)} .ms.slow{color:var(--warn)} .ms.vslow{color:var(--bad)}
.err{color:var(--bad);font-size:12px}
.spark{width:220px;max-width:38vw}

.ev{display:flex;gap:10px;padding:9px 16px;border-bottom:1px solid var(--line);font-size:12.5px}
.ev:last-child{border-bottom:none}
.ev .t{color:var(--mut);white-space:nowrap;font-variant-numeric:tabular-nums}
.ev .k{font-weight:700;white-space:nowrap}
.ev .k.down{color:var(--bad)} .ev .k.up{color:var(--ok)}
.empty{padding:26px 16px;text-align:center;color:var(--mut);font-size:13px}
.foot{text-align:center;color:var(--mut);font-size:12px;margin-top:20px;line-height:1.8}
</style>
</head>
<body>
<div class="wrap">

  <header>
    <h1>服务监控</h1>
    <span class="badge <?= $allOk ? 'ok' : 'bad' ?>">
      <?= $allOk ? '全部正常' : ($summary['down'] . ' 项异常') ?>
    </span>
    <span class="muted">每 15 秒探测一次（每分钟 4 次）</span>
    <span class="spacer"></span>
    <a class="btn" href="">刷新</a>
    <a class="btn" href="/">返回首页</a>
  </header>

<?php if ($dbError !== ''): ?>
  <div class="panel"><div class="empty">
    数据库读取失败：<?= h($dbError) ?><br>
    <span class="muted">请确认 MySQL 运行中，且 /opt/cm-pricesite/db.conf 可读</span>
  </div></div>
<?php else: ?>

  <div class="cards">
    <div class="card"><div class="k">监控目标</div><div class="v acc"><?= $summary['total'] ?></div></div>
    <div class="card"><div class="k">在线</div><div class="v ok"><?= $summary['up'] ?></div></div>
    <div class="card"><div class="k">异常</div><div class="v <?= $summary['down'] > 0 ? 'bad' : '' ?>"><?= $summary['down'] ?></div></div>
    <div class="card"><div class="k">平均延迟</div><div class="v"><?= $summary['avgMs'] ?><span style="font-size:14px"> ms</span></div></div>
  </div>

  <div class="panel">
    <h2>实时状态</h2>
    <table>
      <thead>
        <tr>
          <th>服务</th><th>状态</th><th>延迟</th><th>趋势（最近 90 次）</th><th>检测时间</th>
        </tr>
      </thead>
      <tbody>
<?php foreach ($targets as $t):
        $ok = (int)$t['ok'] === 1;
        $ms = (int)$t['latency_ms'];
        $msCls = $ms < 200 ? 'fast' : ($ms < 800 ? 'slow' : 'vslow');
        $pts = history($pdo, (string)$t['target_key'], 90);
?>
        <tr>
          <td class="name"><?= h($t['name']) ?></td>
          <td>
            <span class="dot <?= $ok ? 'ok' : 'bad' ?>"></span>
            <?= $ok ? '正常' : '异常' ?>
            <?php if (!$ok && (int)$t['fail_streak'] > 0): ?>
              <span class="muted">（连续 <?= (int)$t['fail_streak'] ?> 次）</span>
            <?php endif; ?>
          </td>
          <td class="ms <?= $ok ? $msCls : '' ?>"><?= $ok ? $ms . ' ms' : '—' ?></td>
          <td><div class="spark"><?= sparkline($pts) ?></div></td>
          <td class="muted"><?= ago((int)$t['checked_at']) ?></td>
        </tr>
<?php if (!$ok && trim((string)$t['err_msg']) !== ''): ?>
        <tr><td colspan="5" class="err" style="padding-top:0">
          ↳ <?= h($t['err_msg']) ?>
        </td></tr>
<?php endif; ?>
<?php endforeach; ?>
<?php if (empty($targets)): ?>
        <tr><td colspan="5" class="empty">
          还没有巡检数据。服务刚启动时需等约 15 秒完成第一轮。
        </td></tr>
<?php endif; ?>
      </tbody>
    </table>
  </div>

  <div class="panel">
    <h2>最近事件（故障 / 恢复）</h2>
<?php if (empty($events)): ?>
    <div class="empty">暂无事件。状态变化时会记录并推送飞书 + 邮件通知。</div>
<?php else: ?>
<?php foreach ($events as $e): ?>
    <div class="ev">
      <span class="t"><?= ago((int)$e['created_at']) ?></span>
      <span class="k <?= $e['kind'] === 'down' ? 'down' : 'up' ?>">
        <?= $e['kind'] === 'down' ? '故障' : '恢复' ?>
      </span>
      <span><?= h($e['name']) ?></span>
      <?php if (trim((string)$e['detail']) !== ''): ?>
        <span class="muted"><?= h($e['detail']) ?></span>
      <?php endif; ?>
    </div>
<?php endforeach; ?>
<?php endif; ?>
  </div>

<?php endif; ?>

  <div class="foot">
    数据由 Go 服务每 15 秒采集写入 MySQL，本页仅读取展示 · 异常自动推送飞书 + 邮件<br>
    超米 · <?= date('Y-m-d H:i:s') ?>
  </div>
</div>
</body>
</html>
