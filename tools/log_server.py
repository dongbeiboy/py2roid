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
body{background:#1e1e1e;color:#d4d4d4;font:13px/1.5 'Cascadia Code','JetBrains Mono','Consolas',monospace;padding:12px;padding-top:0}
/* ---- 顶栏 ---- */
.f-bar{position:sticky;top:0;z-index:10;background:#1e1e1e;padding:8px 0;display:flex;gap:8px;align-items:center;flex-wrap:wrap;border-bottom:1px solid #333;margin-bottom:6px}
.f-bar input[type=text]{flex:1;min-width:200px;background:#1e1e1e;border:1px solid #444;color:#d4d4d4;padding:6px 10px;border-radius:4px;font:inherit;transition:border-color .15s}
.f-bar input[type=text]:focus{outline:none;border-color:#569cd6}
.f-bar input[type=text]::placeholder{color:#555}
.f-bar input[type=checkbox]{accent-color:#4ec9b0;width:14px;height:14px;cursor:pointer;vertical-align:middle}
.f-bar label{color:#888;font-size:12px;cursor:pointer;user-select:none;display:inline-flex;align-items:center;gap:3px}
#count{color:#888;font-size:12px}
#usage{color:#888;font-size:12px}
/* ---- Stats HUD ---- */
#hud{display:inline-flex;align-items:center;gap:8px;font-size:12px;flex-shrink:0;background:#1a2a1a;border:1px solid #2a4a2a;border-radius:4px;padding:0 8px;height:22px}
#hud .h-cpu{color:#4ec9b0}
#hud .h-temp{color:#ce9178}
#hud .h-gpu{color:#569cd6}
#hud .h-na{color:#555}
#status{font-size:12px;margin-left:auto}
/* ---- 日志行 ---- */
#log{white-space:pre-wrap;word-break:break-all}
.line{padding:2px 0;display:flex;align-items:baseline;gap:6px;border-radius:2px;transition:background .05s}
.line:hover{background:#2a2d2e}
.line.sel{background:#264f78 !important}
/* 时间戳 */
.t{color:#569cd6;flex-shrink:0;font-size:12px;min-width:62px}
/* 级别徽标 */
.lvl{display:inline-flex;align-items:center;justify-content:center;width:20px;height:16px;border-radius:3px;font-size:11px;font-weight:700;flex-shrink:0;line-height:1}
.lvl-I{background:#1a3a1a;color:#4ec9b0}
.lvl-E{background:#3a1a1a;color:#f44747}
.lvl-W{background:#3a2a0a;color:#ce9178}
.lvl-D{background:#1a2a3a;color:#569cd6}
.lvl-V{background:#2a2a2a;color:#808080}
/* 来源标签 */
.src{color:#c586c0;flex-shrink:0;font-size:12px;min-width:36px}
/* 消息 */
.msg{flex:1}
/* Stats 冷凝计数 */
.repeat{color:#888;font-size:11px;flex-shrink:0;margin-left:auto;padding:0 4px;background:#2a2a2a;border-radius:3px}
/* 搜索高亮 */
mark{background:#b8952a;color:#1e1e1e;padding:0 2px;border-radius:2px}
</style>
</head>
<body>
<div class="f-bar">
  <input id="filter" type="text" placeholder="过滤 (空格分隔 AND)..." oninput="render()">
  <label><input type="checkbox" id="autoScroll" checked> 自动滚动</label>
  <label><input type="checkbox" id="showTime" checked> 时间</label>
  <span id="count">0</span>
  <span id="usage"></span>
  <span id="hud"></span>
  <span id="status">○ 等待连接...</span>
</div>
<div id="log"></div>
<script>
const logEl=document.getElementById('log'),fEl=document.getElementById('filter'),
    asEl=document.getElementById('autoScroll'),stEl=document.getElementById('showTime'),
    countEl=document.getElementById('count'),statusEl=document.getElementById('status'),
    usageEl=document.getElementById('usage');
let allLines=[],renderedCount=0,prevFilter='';

// ---- 解析一行日志 ----
// 格式: [06:16:00] [I] [Stats] CPU=49% TEMP=69℃
function parseLine(l){
    let time='',level='',src='',msg=l;
    const m=l.match(/^\[([^\]]+)\]\s*\[([^\]]+)\]\s*\[([^\]]+)\]\s*(.*)/);
    if(m){time=m[1];level=m[2];src=m[3];msg=m[4];return{time,level,src,msg}}
    const m2=l.match(/^\[([^\]]+)\]\s*\[([^\]]+)\]\s*(.*)/);
    if(m2){time=m2[1];level=m2[2];src='';msg=m2[3];return{time,level,src,msg}}
    const m3=l.match(/^\[([^\]]+)\]\s*(.*)/);
    if(m3){time=m3[1];level='';src='';msg=m3[2];return{time,level,src,msg}}
    return{time:'',level:'',src:'',msg:l};
}
function esc(s){return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')}
function highlightText(text,keywords){
    if(!keywords.length) return esc(text);
    let lower=text.toLowerCase(),result='',last=0;
    // 找所有匹配位置（不重叠）
    const matches=[];
    for(const kw of keywords){
        let idx=0;
        while((idx=lower.indexOf(kw,idx))!==-1){matches.push({start:idx,end:idx+kw.length});idx+=kw.length}
    }
    if(!matches.length) return esc(text);
    matches.sort((a,b)=>a.start-b.start);
    // 合并重叠
    const merged=[];
    for(const m of matches){
        if(merged.length&&m.start<=merged[merged.length-1].end)
            merged[merged.length-1].end=Math.max(merged[merged.length-1].end,m.end);
        else merged.push(m);
    }
    for(const m of merged){
        result+=esc(text.slice(last,m.start))+'<mark>'+esc(text.slice(m.start,m.end))+'</mark>';
        last=m.end;
    }
    result+=esc(text.slice(last));
    return result;
}
function buildLineHTML(p,showTime,keywords){
    let cls='line';
    if(p.level==='E') cls+=' error';
    if(p.src==='Stats') cls+=' stats';
    let html='<div class="'+cls+'">';
    if(showTime&&p.time) html+='<span class="t">'+esc(p.time)+'</span>';
    if(p.level){
        const lvlCls='lvl-'+p.level;
        html+='<span class="lvl '+lvlCls+'">'+esc(p.level)+'</span>';
    }
    if(p.src) html+='<span class="src">'+esc(p.src)+'</span>';
    if(keywords.length) html+='<span class="msg">'+highlightText(p.msg,keywords)+'</span>';
    else html+='<span class="msg">'+esc(p.msg)+'</span>';
    if(p.repeat>1) html+='<span class="repeat">×'+p.repeat+'</span>';
    html+='</div>';
    return html;
}

// ---- Stats 冷凝：连续 Stats 行合并为一条 + 计数 ----
function condense(arr){
    if(!arr.length) return arr;
    let r=[],statsCount=0,statsEntry=null;
    const flush=()=>{if(statsEntry){statsEntry.repeat=statsCount;r.push(statsEntry);statsEntry=null;statsCount=0}};
    for(let i=0;i<arr.length;i++){
        const p=arr[i];
        if(p.src==='Stats'){
            if(!statsEntry) statsEntry={...p};
            else statsEntry.msg=p.msg; // 保留最新的值
            statsCount++;
        }else{
            flush();
            r.push({...p,repeat:1});
        }
    }
    flush();
    return r;
}

// ---- 选择行点击 ----
logEl.addEventListener('click',e=>{
    const div=e.target.closest('.line');
    if(!div) return;
    // 移除其他选中
    document.querySelectorAll('.line.sel').forEach(d=>d.classList.remove('sel'));
    div.classList.add('sel');
});

// ---- 渲染 ----
function hasSelection(){
    const s=window.getSelection();
    return s&&s.toString().length>0;
}
function fullRebuild(ks,showTime){
    const raw=ks.length?allLines.filter(l=>match(l,ks)):allLines;
    const parsed=condense(raw.map(l=>parseLine(l)));
    logEl.innerHTML=parsed.map(p=>buildLineHTML(p,showTime,ks)).join('');
    renderedCount=allLines.length;
}
function match(l,ks){return !ks.length||ks.every(k=>l.toLowerCase().includes(k))}
function render(){
    const rawKs=fEl.value.trim().toLowerCase().split(/\s+/).filter(Boolean);
    const showTime=stEl.checked;
    const filterStr=rawKs.join(' ');
    const filterChanged=filterStr!==prevFilter;
    prevFilter=filterStr;
    if(hasSelection()) return;
    if(filterChanged||rawKs.length>0){
        fullRebuild(rawKs,showTime);
    }else{
        // 增量追加（新行中也可能有连续 Stats，需冷凝）
        const newParsed=condense(allLines.slice(renderedCount).map(l=>parseLine(l)));
        // 新批首行与 DOM 末行都是 Stats → 合并到末行
        if(newParsed.length&&newParsed[0].src==='Stats'&&logEl.lastElementChild){
            const lastDiv=logEl.lastElementChild;
            if(lastDiv.classList.contains('stats')){
                const p0=newParsed[0];
                const msgEl=lastDiv.querySelector('.msg');
                const repEl=lastDiv.querySelector('.repeat');
                if(msgEl) msgEl.textContent=p0.msg;
                if(repEl){
                    const n=parseInt(repEl.textContent.slice(1))+p0.repeat;
                    repEl.textContent='×'+n;
                }else{
                    const rep=document.createElement('span');
                    rep.className='repeat';
                    rep.textContent='×'+p0.repeat;
                    lastDiv.appendChild(rep);
                }
                newParsed.shift();
            }
        }
        if(newParsed.length){
            const frag=document.createDocumentFragment();
            for(const p of newParsed){
                const div=document.createElement('div');
                div.innerHTML=buildLineHTML(p,showTime,[]);
                frag.appendChild(div.firstElementChild);
            }
            logEl.appendChild(frag);
        }
        renderedCount=allLines.length;
    }
    countEl.textContent=logEl.childElementCount+'/'+allLines.length;
    if(asEl.checked&&!hasSelection()) window.scrollTo(0,document.body.scrollHeight);
}
async function poll(){
    try{
        const r=await fetch('/poll'),t=await r.text();
        if(t){t.split('\n').filter(Boolean).forEach(l=>{allLines.push(l)});
        if(allLines.length>6000){allLines.splice(0,1000);renderedCount=Math.max(0,renderedCount-1000)};render()}
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
