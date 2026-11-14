'use strict';
const $ = id => document.getElementById(id);
const join = $('join'), unauthorized = $('notAuthorized'), controller = $('controller');
const token = new URL(location.href).hash.match(/^#pair=([a-f0-9]{48})$/)?.[1] || null;
// Quitar el secreto del historial y de la barra antes de iniciar la sesión.
if (location.hash) history.replaceState(null, '', '/');
let timer = null, pending = false, channelSignature = '';
function message(t){$('toast').textContent=t;setTimeout(()=>{if($('toast').textContent===t)$('toast').textContent='';},3800);}
function show(section){join.hidden=section!==join;unauthorized.hidden=section!==unauthorized;controller.hidden=section!==controller;}
async function request(path, data){
  const r=await fetch(path,{method:data?'POST':'GET',credentials:'same-origin',cache:'no-store',headers:data?{'Content-Type':'application/x-www-form-urlencoded'}:{},body:data?new URLSearchParams(data).toString():undefined});
  if(r.status===401){clearInterval(timer);show(unauthorized);throw new Error('Teléfono no autorizado');}
  if(!r.ok)throw new Error(await r.text());
  return r;
}
async function pair(){try{$('pairButton').disabled=true;let r=await request('/api/pair',{token:token,name:$('phoneName').value.trim()||'Celular'});message(await r.text());await load();if(!timer)timer=setInterval(load,3500);}catch(e){message(e.message);$('pairButton').disabled=false;}}
$('pairButton').addEventListener('click',pair);
async function command(action,number){if(pending)return;pending=true;try{await request('/api/cmd',{action,...(Number.isInteger(number)?{number}:{} )});setTimeout(load,280);}catch(e){message('No se pudo enviar: '+e.message)}finally{pending=false;}}
async function sendPlaylist(){const url=$('playlistUrl').value.trim();if(!/^https?:\/\//i.test(url)){message('La dirección tiene que empezar con http:// o https://');return;}try{$('playlistButton').disabled=true;const r=await request('/api/playlist',{url});message(await r.text());setTimeout(load,4000);}catch(e){message('No se pudo enviar: '+e.message)}finally{$('playlistButton').disabled=false;}}
$('playlistButton').addEventListener('click',sendPlaylist);
document.querySelectorAll('[data-cmd]').forEach(b=>b.addEventListener('click',()=>command(b.dataset.cmd)));
function render(s){
  show(controller);
  const current=s.channels[s.channel];
  $('current').textContent=current?`${s.channel+1}. ${current}`:'Sin canales';
  $('connection').textContent=s.playing?'Reproduciendo':'Pausado / esperando señal';
  $('volume').textContent=s.volume;
  ['back','forward'].forEach(action=>document.querySelector(`[data-cmd="${action}"]`).disabled=!s.seekable);
  document.querySelector('[data-cmd="live"]').disabled=!s.live;
  $('pauseButton').disabled=!s.seekable;
  const signature=JSON.stringify(s.channels);
  if(signature!==channelSignature){
    channelSignature=signature;$('channels').replaceChildren();
    s.channels.forEach((name,i)=>{let b=document.createElement('button');b.textContent=`${i+1}. ${name}`;b.addEventListener('click',()=>command('channel',i));b.dataset.index=String(i);$('channels').appendChild(b);});
  }
  for(let b of $('channels').children)b.classList.toggle('active',Number(b.dataset.index)===s.channel);
}
async function load(){try{const r=await request('/api/state');render(await r.json());}catch(e){if(!unauthorized.hidden)return;$('connection').textContent='Sin conexión con la TV';}}
if(token){show(join);}else{load();timer=setInterval(load,3500);}
