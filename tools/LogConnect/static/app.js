const logEl=document.getElementById('log'),fEl=document.getElementById('filter'),
    asEl=document.getElementById('autoScroll'),stEl=document.getElementById('showTime'),
    countEl=document.getElementById('count'),statusEl=document.getElementById('status'),
    usageEl=document.getElementById('usage'),hudEl=document.getElementById('hud'),
    perfBarEl=document.getElementById('perfBar'),modelBarEl=document.getElementById('modelBar'),
    cpEl=document.getElementById('condensePerf');
let allLines=[],renderedCount=0,prevFilter='';

// ── 模型会话追踪 ──
let currentModelName='(无)',currentProvider='';
let perfStats=null;
let modelHistory=[];

// ── 预设过滤（OR 逻辑）──
let activePreset=null;
const PRESETS={
    '':null,
    '问题':{or:['[E] ','[W] ']},
    '性能':{or:['[Perf]','[Result]']},
    '模型':{or:['[Route]','Model loaded','Detection started','provider=']},
};

// ── 日志解析 ──
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

// ── Stats HUD ──
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

// ── 检测模型切换&性能行 ──
function detectModelSwitch(p,rawLine){
    if(p.src==='Route' && p.msg.includes('backend=')){
        const m=p.msg.match(/model=(\S+)/);
        if(m) currentModelName=m[1];
    }
    if(p.msg.includes('provider=')){
        const m=p.msg.match(/provider=(\S+)/);
        if(m) currentProvider=m[1];
    }
    if(p.msg==='Detection started'){
        onModelSessionStart();
    }
}

function onModelSessionStart(){
    perfStats={model:currentModelName,provider:currentProvider,count:0,pre:[],inf:[],total:[]};
    modelHistory.push({name:currentModelName,provider:currentProvider,time:new Date().toLocaleTimeString()});
    updateModelBar();
    updatePerfBar();
}

// ── 解析 Perf 行 ──
function parsePerf(msg){
    const m=msg.match(/pre=(\d+)ms\s+inf=(\d+)ms\s+total=(\d+)ms/);
    if(!m) return null;
    return{pre:+m[1],inf:+m[2],total:+m[3]};
}

function feedPerf(p){
    const perf=parsePerf(p.msg);
    if(!perf||!perfStats) return;
    perfStats.count++;
    perfStats.pre.push(perf.pre);
    perfStats.inf.push(perf.inf);
    perfStats.total.push(perf.total);
    updatePerfBar();
}

function statsSummary(arr){
    if(!arr||!arr.length) return{min:'-',avg:'-',max:'-'};
    const sum=arr.reduce((a,b)=>a+b,0);
    return{min:Math.min(...arr).toFixed(0),avg:(sum/arr.length).toFixed(0),max:Math.max(...arr).toFixed(0)};
}

function updatePerfBar(){
    if(!perfStats||!perfStats.count){
        perfBarEl.innerHTML=''; perfBarEl.style.display='none';
        return;
    }
    const pre=statsSummary(perfStats.pre);
    const inf=statsSummary(perfStats.inf);
    const tot=statsSummary(perfStats.total);
    const fps=(1000/perfStats.total.reduce((a,b)=>a+b,0)/perfStats.total.length).toFixed(1);
    perfBarEl.style.display='flex';
    perfBarEl.innerHTML=
        `<span class="pm-name">${esc(currentModelName)}</span>`+
        `<span class="pm-provider">${esc(currentProvider)}</span>`+
        `<span class="pm-sep">|</span>`+
        `<span class="pm-label">Pre</span>`+
        `<span class="pm-val">${pre.avg}</span>`+
        `<span class="pm-dim">${pre.min}-${pre.max}</span>`+
        `<span class="pm-sep">|</span>`+
        `<span class="pm-label">Inf</span>`+
        `<span class="pm-val hi">${inf.avg}</span>`+
        `<span class="pm-dim">${inf.min}-${inf.max}</span>`+
        `<span class="pm-sep">|</span>`+
        `<span class="pm-label">总</span>`+
        `<span class="pm-val">${tot.avg}</span>`+
        `<span class="pm-dim">${tot.min}-${tot.max}</span>`+
        `<span class="pm-sep">|</span>`+
        `<span class="pm-fps">${fps} FPS</span>`+
        `<span class="pm-cnt">${perfStats.count}帧</span>`;
}

function updateModelBar(){
    if(!currentModelName){
        modelBarEl.innerHTML=''; modelBarEl.style.display='none';
        return;
    }
    modelBarEl.style.display='flex';
    modelBarEl.innerHTML=
        `<span class="mb-key">模型:</span><span class="mb-val">${esc(currentModelName)}</span>`+
        `<span class="mb-key">后端:</span><span class="mb-val">${esc(currentProvider||'?')}</span>`+
        `<span class="mb-key">会话:</span><span class="mb-val">${modelHistory.length}</span>`;
}

// ── 过滤 ──
// AND 过滤（手动输入，空格分隔）
function matchAnd(l,ks){
    return !ks.length||ks.every(k=>l.toLowerCase().includes(k));
}
// OR 过滤（预设按钮）
function matchOr(l,ks){
    if(!ks||!ks.length) return true;
    return ks.some(k=>l.includes(k));
}
function shouldShow(l,andKs,orKs){
    if(!matchOr(l,orKs||null)) return false;
    return matchAnd(l,andKs||[]);
}

function esc(s){return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')}

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
    else if(p.level==='W') cls+=' warn';
    let html='<div class="'+cls+'">';
    if(showTime&&p.time) html+='<span class="t">'+esc(p.time)+'</span>';
    if(p.level){
        const lvlCls='lvl-'+p.level;
        html+='<span class="lvl '+lvlCls+'">'+esc(p.level)+'</span>';
    }
    if(p.src) html+='<span class="src">'+esc(p.src)+'</span>';
    if(keywords.length){
        html+='<span class="msg">'+highlightText(p.msg,keywords)+'</span>';
    }else if(p.src==='Perf'){
        const perf=parsePerf(p.msg);
        if(perf){
            const fps=(1000/perf.total).toFixed(1);
            html+=`<span class="msg perf-line">${esc(p.msg)} <span class="perf-fps">${fps}FPS</span></span>`;
        }else html+='<span class="msg">'+esc(p.msg)+'</span>';
    }else{
        html+='<span class="msg">'+esc(p.msg)+'</span>';
    }
    html+='</div>';
    return html;
}

// ── 预设过滤按钮 ──
document.getElementById('presetFilters').addEventListener('click',e=>{
    const btn=e.target.closest('button');
    if(!btn||!btn.dataset.preset) return;
    document.querySelectorAll('#presetFilters .active').forEach(b=>b.classList.remove('active'));
    btn.classList.add('active');
    activePreset=btn.dataset.preset||null;
    render();
});

// ── 过滤输入延迟触发 ──
let filterTimer=null;
function onFilterInput(){
    clearTimeout(filterTimer);
    filterTimer=setTimeout(()=>render(),100);
}

// ── 选择行点击 ──
logEl.addEventListener('click',e=>{
    const div=e.target.closest('.line');
    if(!div) return;
    document.querySelectorAll('.line.sel').forEach(d=>d.classList.remove('sel'));
    div.classList.add('sel');
});

// ── 渲染 ──
function hasSelection(){
    const s=window.getSelection();
    return s&&s.toString().length>0;
}

function render(){
    const andKs=fEl.value.trim().toLowerCase().split(/\s+/).filter(Boolean);
    const orDef=activePreset?PRESETS[activePreset]:null;
    const orKs=orDef?orDef.or:null;
    const showTime=stEl.checked;
    const condense=cpEl.checked;
    const filterKey=JSON.stringify({and:andKs,or:activePreset});
    const filterChanged=filterKey!==prevFilter;
    prevFilter=filterKey;

    if(hasSelection()) return;

    // 有过滤或折叠时全量重建
    if(filterChanged||andKs.length||orKs||condense){
        fullRebuild(andKs,orKs,showTime,condense);
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

function fullRebuild(andKs,orKs,showTime,condense){
    // 过滤
    let raw=allLines.filter(l=>shouldShow(l,andKs,orKs));

    // 折叠连续 Perf 行
    if(condense && !andKs.length && !orKs){
        const collapsed=[];
        let i=0;
        while(i<raw.length){
            const p=parseLine(raw[i]);
            if(p.src==='Perf' && p.level==='I'){
                // 统计连续 Perf 数
                let cnt=1;
                while(i+cnt<raw.length){
                    const pn=parseLine(raw[i+cnt]);
                    if(pn.src==='Perf' && pn.level==='I') cnt++;
                    else break;
                }
                if(cnt>1){
                    const m=p.msg.match(/(pre=\d+ms inf=\d+ms total=\d+ms)/);
                    if(m) collapsed.push('['+p.time+'] [I] [Perf] '+m[1]+' ×'+cnt);
                    else collapsed.push(raw[i]);
                }else{
                    collapsed.push(raw[i]);
                }
                i+=cnt;
            }else{
                collapsed.push(raw[i]);
                i++;
            }
        }
        raw=collapsed;
    }

    const parsed=raw.map(l=>parseLine(l));
    logEl.innerHTML=parsed.map(p=>buildLineHTML(p,showTime,andKs)).join('');
    renderedCount=allLines.length;
}

// ── 外部新增行处理 ──
function processNewLine(rawLine){
    const p=parseLine(rawLine);
    if(p.src==='Stats'){updateHUD(p);return false;}
    detectModelSwitch(p,rawLine);
    if(p.src==='Perf' && p.level==='I') feedPerf(p);
    return true;
}

// ── 轮询 ──
async function poll(){
    try{
        const r=await fetch('/poll'),t=await r.text();
        if(t){
            const newLines=t.split('\n').filter(Boolean);
            for(const line of newLines){
                if(processNewLine(line)) allLines.push(line);
            }
            if(allLines.length>8000){allLines.splice(0,1500);renderedCount=Math.max(0,renderedCount-1500)}
            render();
        }
        statusEl.textContent='● 已连接';statusEl.style.color='#4ec9b0';
        usageEl.textContent=allLines.length+' total';
    }catch(e){
        statusEl.textContent='○ 断开';statusEl.style.color='#f44747';
    }
}

// ── 首次加载历史 ──
fetch('/log').then(r=>r.text()).then(text=>{
    if(text){
        const lines=text.split('\n').filter(Boolean);
        for(const line of lines){
            if(processNewLine(line)) allLines.push(line);
        }
        // 从历史中恢复当前模型
        const lastRoute=[...allLines].reverse().find(l=>l.includes('backend=')&&l.includes('model='));
        if(lastRoute){
            const p=parseLine(lastRoute);
            detectModelSwitch(p,lastRoute);
            if(allLines.find(l=>l.includes('Detection started'))) onModelSessionStart();
        }
        render();
    }
});

setInterval(poll,600);
render();
