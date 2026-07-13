"""
py2roid 远程日志接收服务器

用法:
  python tools\log_server.py
  → 浏览器打开 http://localhost:8765

App 端连入（替换为电脑 IP）:
  adb shell am start -n com.xz.py2roid/.MainActivity \
    --es log_web http://192.168.1.100:8765
"""

import json
import time
import urllib.parse
from http.server import HTTPServer, BaseHTTPRequestHandler
from datetime import datetime

log_history = []
buffer = []


class Handler(BaseHTTPRequestHandler):

    def do_GET(self):
        if self.path == "/" or self.path.startswith("/?"):
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(HTML.encode("utf-8"))
        elif self.path.startswith("/poll"):
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            global buffer
            lines = "\n".join(buffer)
            buffer = []
            self.wfile.write(lines.encode("utf-8"))
        elif self.path.startswith("/log"):
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write("\n".join(log_history[-300:]).encode("utf-8"))
        elif self.path.startswith("/register"):
            qs = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            source = qs.get("source", ["unknown"])[0]
            ip = self.client_address[0]
            now = datetime.now().strftime("%H:%M:%S")
            line = f"[{now}] ← {source} connected from {ip}"
            log_history.append(line)
            buffer.append(line)
            self.send_response(200)
            self.end_headers()
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        if self.path.startswith("/log"):
            length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(length).decode("utf-8")
            params = urllib.parse.parse_qs(body)
            raw_lines = params.get("lines", [""])[0]
            if raw_lines:
                now = datetime.now().strftime("%H:%M:%S")
                for l in raw_lines.split("\n"):
                    if l.strip():
                        tagged = f"[{now}] {l.strip()}"
                        log_history.append(tagged)
                        buffer.append(tagged)
                if len(log_history) > 5000:
                    log_history[:1000] = []
            self.send_response(200)
            self.end_headers()
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, fmt, *args):
        pass


HTML = r"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>py2roid 远程日志</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{background:#1e1e1e;color:#d4d4d4;font:13px/1.5 'Cascadia Code','JetBrains Mono','Consolas',monospace;padding:12px}
#log{white-space:pre-wrap;word-break:break-all}
.line{padding:1px 0;display:flex}
.line:hover{background:#2a2d2e}
.tag{color:#569cd6;flex-shrink:0;margin-right:8px}
.msg{flex:1}
.warn .msg{color:#ce9178}
.error .msg{color:#f44747}
.green .msg{color:#4ec9b0}
#status{position:fixed;top:8px;right:12px;font-size:12px}
.f-bar{margin-bottom:8px;display:flex;gap:8px;align-items:center;flex-wrap:wrap}
.f-bar input{flex:1;min-width:200px;background:#3c3c3c;border:1px solid #555;color:#d4d4d4;padding:4px 8px;border-radius:3px;font:inherit}
.f-bar label{color:#888;font-size:12px;cursor:pointer}
#count{color:#888;font-size:12px}
#usage{color:#888;font-size:12px;margin-left:auto}
</style>
</head>
<body>
<div class="f-bar">
  <input id="filter" placeholder="过滤 (空格分隔 AND)..." oninput="render()">
  <label><input type="checkbox" id="autoScroll" checked> 自动滚动</label>
  <label><input type="checkbox" id="showTime" checked> 时间</label>
  <span id="count">0</span>
  <span id="usage"></span>
</div>
<div id="log"></div>
<div id="status">○ 等待连接...</div>
<script>
const logEl=document.getElementById('log'),fEl=document.getElementById('filter'),
    asEl=document.getElementById('autoScroll'),stEl=document.getElementById('showTime'),
    countEl=document.getElementById('count'),statusEl=document.getElementById('status'),
    usageEl=document.getElementById('usage');
let allLines=[];
function match(l,ks){return !ks.length||ks.every(k=>l.toLowerCase().includes(k))}
function esc(s){return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')}
function render(){
    const ks=fEl.value.trim().toLowerCase().split(/\s+/).filter(Boolean);
    const f=allLines.filter(l=>match(l,ks));
    const showTime=stEl.checked;
    logEl.innerHTML=f.map(l=>{
        let cls='',msg=l;
        if(l.startsWith('[')){
            const m=l.match(/^(\[[^\]]+\])\s*(.*)/);
            if(m){msg=m[2];const t=m[1];if(showTime)msg=esc(t)+' '+esc(msg);else msg=esc(msg)}
        }else msg=esc(msg);
        if(l.includes('Warn')||l.includes('warn')) cls='warn';
        else if(l.includes('Error')||l.includes('error')||l.includes('Exception')) cls='error';
        else if(l.includes('OK')||l.includes('connected')||l.includes('Running')) cls='green';
        return `<div class="line ${cls}"><span class="msg">${msg}</span></div>`;
    }).join('');
    countEl.textContent=f.length+'/'+allLines.length;
    if(asEl.checked) window.scrollTo(0,document.body.scrollHeight);
}
async function poll(){
    try{
        const r=await fetch('/poll'),t=await r.text();
        if(t){t.split('\n').filter(Boolean).forEach(l=>{allLines.push(l)});
        if(allLines.length>6000)allLines.splice(0,1000);render()}
        statusEl.textContent='● 已连接';statusEl.style.color='#4ec9b0';
        usageEl.textContent=allLines.length+' total';
    }catch(e){statusEl.textContent='○ 断开';statusEl.style.color='#f44747'}
}
setInterval(poll,600);
render();
</script>
</body>
</html>"""

if __name__ == "__main__":
    port = 8765
    print(f"╔══════════════════════════════════════╗")
    print(f"║  py2roid 远程日志接收器              ║")
    print(f"║  浏览器: http://localhost:{port}       ║")
    print(f"║                                      ║")
    print(f"║  ADB 连入:                           ║")
    print(f"║    adb shell am start                ║")
    print(f"║      -n com.xz.py2roid/.MainActivity  ║")
    print(f"║      --es log_web http://IP:{port}    ║")
    print(f"║                                      ║")
    print(f"║  按 Ctrl+C 停止                      ║")
    print(f"╚══════════════════════════════════════╝")
    server = HTTPServer(("0.0.0.0", port), Handler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n停止")
