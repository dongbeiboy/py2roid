const logEl=document.getElementById('log'),fEl=document.getElementById('filter'),
    asEl=document.getElementById('autoScroll'),stEl=document.getElementById('showTime'),
    countEl=document.getElementById('count'),statusEl=document.getElementById('status'),
    usageEl=document.getElementById('usage'),hudEl=document.getElementById('hud');
let allLines=[],renderedCount=0,prevFilter='';

// ======== 日志解析 ========
// 格式: [06:16:00] [I] [Stats] CPU=49% TEMP=69℃ GPU=-1
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

// ======== Stats HUD ========
// 从 Stats 行提取 CPU/TEMP/GPU
function parseStats(msg){
    const cpu=(msg.match(/CPU=([\d.]+)%/)||[])[1];
    const temp=(msg.match(/TEMP=([\d.]+)℃/)||[])[1];
    const gpu=(msg.match(/GPU=(-?[\d.]+)/)||[])[1];
    return{cpu,temp,gpu};
}
function updateHUD(p){
    if(!p||p.src!=='Stats') return;
    const s=parseStats(p.msg);
    let html='';
    if(s.cpu) html+='<span class="h-cpu">CPU '+s.cpu+'%</span>';
    if(s.temp) html+='<span class="h-temp">'+s.temp+'℃</span>';
    if(s.gpu!==undefined) html+='<span class="h-gpu'+(s.gpu==='-1'?' h-na':'')+'">GPU '+s.gpu+'</span>';
    hudEl.innerHTML=html;
}

// ======== 工具函数 ========
function esc(s){return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')}
function match(l,ks){return !ks.length||ks.every(k=>l.toLowerCase().includes(k))}

function highlightText(text,keywords){
    if(!keywords.length) return esc(text);
    let lower=text.toLowerCase(),result='',last=0;
    const matches=[];
    for(const kw of keywords){
        let idx=0;
        while((idx=lower.indexOf(kw,idx))!==-1){matches.push({start:idx,end:idx+kw.length});idx+=kw.length}
    }
    if(!matches.length) return esc(text);
    matches.sort((a,b)=>a.start-b.start);
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
    let html='<div class="'+cls+'">';
    if(showTime&&p.time) html+='<span class="t">'+esc(p.time)+'</span>';
    if(p.level){
        const lvlCls='lvl-'+p.level;
        html+='<span class="lvl '+lvlCls+'">'+esc(p.level)+'</span>';
    }
    if(p.src) html+='<span class="src">'+esc(p.src)+'</span>';
    if(keywords.length) html+='<span class="msg">'+highlightText(p.msg,keywords)+'</span>';
    else html+='<span class="msg">'+esc(p.msg)+'</span>';
    html+='</div>';
    return html;
}

// ======== 选择行点击 ========
logEl.addEventListener('click',e=>{
    const div=e.target.closest('.line');
    if(!div) return;
    document.querySelectorAll('.line.sel').forEach(d=>d.classList.remove('sel'));
    div.classList.add('sel');
});

// ======== 渲染 ========
function hasSelection(){
    const s=window.getSelection();
    return s&&s.toString().length>0;
}
function fullRebuild(ks,showTime){
    const raw=ks.length?allLines.filter(l=>match(l,ks)):allLines;
    const parsed=raw.map(l=>parseLine(l));
    logEl.innerHTML=parsed.map(p=>buildLineHTML(p,showTime,ks)).join('');
    renderedCount=allLines.length;
}
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
        // 增量追加
        const frag=document.createDocumentFragment();
        for(let i=renderedCount;i<allLines.length;i++){
            const p=parseLine(allLines[i]);
            const div=document.createElement('div');
            div.innerHTML=buildLineHTML(p,showTime,[]);
            frag.appendChild(div.firstElementChild);
        }
        if(frag.childNodes.length) logEl.appendChild(frag);
        renderedCount=allLines.length;
    }
    countEl.textContent=logEl.childElementCount+'/'+allLines.length;
    if(asEl.checked&&!hasSelection()) window.scrollTo(0,document.body.scrollHeight);
}

// ======== 轮询 ========
async function poll(){
    try{
        const r=await fetch('/poll'),t=await r.text();
        if(t){
            const newLines=t.split('\n').filter(Boolean);
            for(const line of newLines){
                // Stats 行 → HUD，不入日志流
                const p=parseLine(line);
                if(p.src==='Stats'){
                    updateHUD(p);
                    continue;
                }
                allLines.push(line);
            }
            if(allLines.length>6000){allLines.splice(0,1000);renderedCount=Math.max(0,renderedCount-1000)}
            render();
        }
        statusEl.textContent='● 已连接';statusEl.style.color='#4ec9b0';
        usageEl.textContent=allLines.length+' total';
    }catch(e){
        statusEl.textContent='○ 断开';statusEl.style.color='#f44747';
    }
}

// ======== 首次加载历史 ========
fetch('/log').then(r=>r.text()).then(text=>{
    if(text){
        const lines=text.split('\n').filter(Boolean);
        for(const line of lines){
            const p=parseLine(line);
            if(p.src==='Stats'){
                updateHUD(p);
                continue;
            }
            allLines.push(line);
        }
        render();
    }
});

setInterval(poll,600);
render();
