"""
py2roid 实时日志 Web 查看器
用法: python tools\logviewer.py
然后在浏览器打开 http://localhost:8765
"""

import subprocess
import threading
import time
import os
from http.server import HTTPServer, BaseHTTPRequestHandler

ADB = r"F:\dev\trae\sbb-2\sdk\platform-tools\adb.exe"
LOG_FILE = os.path.join(os.path.dirname(__file__), "_logcache.txt")
TAGS = ("py2roid.OnnxEngine", "py2roid.Detector", "py2roid.DeviceProfile", "py2roid.TfliteEngine", "py2roid.VcapEngine")

log_history = []      # 全部日志行
buffer = []           # 新行缓冲区
lock = threading.Lock()


def logcat_worker():
    """后台跑 adb logcat，过滤 py2roid 标签"""
    # 先清 + dump 已有日志
    subprocess.run([ADB, "logcat", "-c"], capture_output=True)
    time.sleep(0.5)

    # 持续 logcat
    args = [ADB, "logcat"] + [f"-s"] + list(TAGS) + ["--format=time"]
    proc = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            universal_newlines=True, bufsize=1)
    for line in iter(proc.stdout.readline, ""):
        line = line.rstrip("\n\r")
        if not line:
            continue
        with lock:
            log_history.append(line)
            buffer.append(line)
            if len(log_history) > 5000:
                log_history[:1000] = []


class LogHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/":
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(HTML.encode("utf-8"))
        elif self.path == "/log":
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.end_headers()
            with lock:
                lines = "\n".join(log_history[-200:])
            self.wfile.write(lines.encode("utf-8"))
        elif self.path == "/poll":
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.end_headers()
            with lock:
                new = "\n".join(buffer)
                buffer.clear()
            self.wfile.write(new.encode("utf-8"))
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, fmt, *args):
        pass  # 不打印 HTTP 日志


HTML = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>py2roid 实时日志</title>
<style>
* { margin:0; padding:0; box-sizing:border-box; }
body { background:#1e1e1e; color:#d4d4d4; font:13px/1.5 'Cascadia Code','JetBrains Mono','Consolas',monospace; padding:12px; }
#log { white-space:pre-wrap; word-break:break-all; }
.line { padding:1px 0; }
.line:hover { background:#2a2d2e; }
.time { color:#569cd6; }
.tag { color:#4ec9b0; }
.info .msg { color:#d4d4d4; }
.warn .msg { color:#ce9178; }
.error .msg { color:#f44747; }
.verbose .msg { color:#808080; }
#status { position:fixed; top:8px; right:12px; color:#888; font-size:12px; }
.filter-bar { margin-bottom:8px; display:flex; gap:8px; align-items:center; }
.filter-bar input { flex:1; background:#3c3c3c; border:1px solid #555; color:#d4d4d4; padding:4px 8px; border-radius:3px; font:inherit; }
.filter-bar label { color:#888; font-size:12px; }
#count { color:#888; font-size:12px; margin-left:8px; }
</style>
</head>
<body>
<div class="filter-bar">
  <input id="filter" placeholder="过滤关键词 (空格分隔 AND)..." oninput="render()">
  <label><input type="checkbox" id="autoScroll" checked> 自动滚动</label>
  <span id="count">0 行</span>
</div>
<div id="log"></div>
<div id="status">连接中...</div>
<script>
const logEl = document.getElementById('log');
const filterEl = document.getElementById('filter');
const autoScrollEl = document.getElementById('autoScroll');
const countEl = document.getElementById('count');
const statusEl = document.getElementById('status');
let allLines = [];

function matches(line, keywords) {
  if (!keywords.length) return true;
  const lower = line.toLowerCase();
  return keywords.every(k => lower.includes(k));
}

function render() {
  const keywords = filterEl.value.trim().toLowerCase().split(/\s+/).filter(Boolean);
  const filtered = allLines.filter(l => matches(l, keywords));
  logEl.innerHTML = filtered.map(l => {
    const cls = l.includes('Warn') || l.includes('warn') ? 'warn' :
               l.includes('Error') || l.includes('error') ? 'error' :
               l.includes('Verbose') ? 'verbose' : 'info';
    return `<div class="line ${cls}"><span class="msg">${escapeHtml(l)}</span></div>`;
  }).join('');
  countEl.textContent = allLines.length + ' 行 / ' + filtered.length + ' 显示';
  if (autoScrollEl.checked) window.scrollTo(0, document.body.scrollHeight);
}

function escapeHtml(s) {
  return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}

async function poll() {
  try {
    const r = await fetch('/poll');
    const text = await r.text();
    if (text) {
      const newLines = text.split('\\n').filter(Boolean);
      allLines.push(...newLines);
      if (allLines.length > 5000) allLines.splice(0, 1000);
      render();
    }
    statusEl.textContent = '● 已连接';
    statusEl.style.color = '#4ec9b0';
  } catch(e) {
    statusEl.textContent = '○ 断开';
    statusEl.style.color = '#f44747';
  }
}

// 首次加载历史
fetch('/log').then(r => r.text()).then(text => {
  allLines = text.split('\\n').filter(Boolean);
  render();
});

setInterval(poll, 500);
</script>
</body>
</html>"""

if __name__ == "__main__":
    print("🔍 启动 py2roid 日志查看器 ...")
    t = threading.Thread(target=logcat_worker, daemon=True)
    t.start()
    time.sleep(1)

    port = 8765
    server = HTTPServer(("0.0.0.0", port), LogHandler)
    print(f"  → 浏览器打开: http://localhost:{port}")
    print(f"  → 按 Ctrl+C 停止\n")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n停止")
