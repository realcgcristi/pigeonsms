package app.pigeonsms.ui.call

import org.json.JSONObject

internal fun buildCallHtml(websocketUrl: String, video: Boolean): String {
    val encodedUrl = JSONObject.quote(websocketUrl)
    val mode = if (video) "video" else "voice"
    return """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<style>
*{box-sizing:border-box}
html,body{margin:0;height:100%;overflow:hidden}
body{background:linear-gradient(180deg,#0b0e16 0%,#131827 48%,#0b0e16 100%);color:#e7ebf4;font:15px/1.3 system-ui,sans-serif;display:flex;flex-direction:column}
#grid{flex:1;display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:10px;padding:96px 14px 132px;align-content:center}
.tile{position:relative;background:linear-gradient(160deg,#1a2030,#141926);border:1px solid rgba(255,255,255,.07);border-radius:24px;overflow:hidden;min-height:150px;display:flex;align-items:center;justify-content:center}
.tile video{width:100%;height:100%;object-fit:cover;position:absolute;inset:0}
.tile .init{width:64px;height:64px;border-radius:50%;background:rgba(255,255,255,.08);display:flex;align-items:center;justify-content:center;font-size:26px;font-weight:600;color:#aeb9d6}
.tile span.name{position:absolute;bottom:10px;left:12px;background:rgba(8,10,16,.62);backdrop-filter:blur(6px);padding:4px 10px;border-radius:10px;font-size:12px;z-index:1}
#local{position:fixed;right:14px;top:96px;width:104px;height:150px;z-index:2;border:1px solid rgba(255,255,255,.18);border-radius:18px;object-fit:cover;background:#141926;box-shadow:0 8px 24px rgba(0,0,0,.45);display:none}
#local.mirror{transform:scaleX(-1)}
</style></head><body>
<video id="local" autoplay muted playsinline class="mirror"></video><div id="grid"></div>
<script>
const WS_URL=$encodedUrl, MODE='$mode', grid=document.getElementById('grid'), localVideo=document.getElementById('local');
let ws=null, selfId='', localStream=null, muted=false, cameraOff=false, facing='user', ended=false, retries=0;
const peers=new Map(), tiles=new Map();
const ice={iceServers:[{urls:'stun:stun.l.google.com:19302'}]};
function native(m){try{if(window.PigeonNative)PigeonNative.post(JSON.stringify(m))}catch(_){}}
function status(s){native({type:'status',state:s})}
function fail(e){
  let msg;
  if(typeof navigator.mediaDevices==='undefined'||(e&&e.name==='TypeError'))msg='secure context unavailable (WebView)';
  else if(e&&e.name==='NotAllowedError')msg='permission denied';
  else if(e&&e.name==='NotFoundError')msg='no mic/camera found';
  else msg=e&&e.name?(e.name+': '+e.message):String(e);
  native({type:'error',message:msg});
}
function signal(x){if(ws&&ws.readyState===1)ws.send(JSON.stringify(x))}
function tile(id,label,stream){
  let t=tiles.get(id);
  if(!t){
    const d=document.createElement('div');d.className='tile';
    const v=document.createElement('video');v.autoplay=true;v.playsInline=true;
    const i=document.createElement('div');i.className='init';i.textContent=(label||id||'?').slice(0,1).toUpperCase();
    const s=document.createElement('span');s.className='name';s.textContent=label||id.slice(0,8);
    d.append(i,v,s);grid.append(d);t={t:d,v};tiles.set(id,t);
  }
  if(stream)t.v.srcObject=stream;
}
function removeTile(id){const x=tiles.get(id);if(x){x.t.remove();tiles.delete(id)}}
function dropPeer(id){const p=peers.get(id);if(p)try{p.close()}catch(_){};peers.delete(id);removeTile(id)}
async function peer(id,offer){
  if(id===selfId)return null;
  let p=peers.get(id);if(p)return p;
  const pc=new RTCPeerConnection(ice);peers.set(id,pc);
  if(localStream)localStream.getTracks().forEach(t=>pc.addTrack(t,localStream));
  pc.onicecandidate=e=>{if(e.candidate)signal({type:'ice',target:id,data:e.candidate})};
  pc.ontrack=e=>tile(id,null,e.streams[0]);
  pc.onconnectionstatechange=()=>{if(['failed','closed','disconnected'].includes(pc.connectionState))dropPeer(id)};
  if(offer){const d=await pc.createOffer();await pc.setLocalDescription(d);signal({type:'offer',target:id,data:d})}
  return pc;
}
function connect(){
  if(ended)return;
  ws=new WebSocket(WS_URL);
  ws.onopen=()=>{retries=0;status('connected')};
  ws.onerror=()=>{};
  ws.onclose=()=>{
    if(ended)return;
    peers.forEach((_,id)=>dropPeer(id));
    if(retries<5){retries++;status('reconnecting');setTimeout(connect,Math.min(1000*retries,4000))}
    else{status('ended')}
  };
  ws.onmessage=async ev=>{
    let m;try{m=JSON.parse(ev.data)}catch(_){return}
    try{
      if(m.type==='ready'){selfId=m.participant.userId;(m.participants||[]).forEach(p=>{if(p.userId!==selfId){tile(p.userId,p.username);if(selfId<p.userId)peer(p.userId,true)}})}
      else if(m.type==='join'){tile(m.participant.userId,m.participant.username);if(selfId<m.participant.userId)peer(m.participant.userId,true)}
      else if(m.type==='leave'){dropPeer(m.participant.userId)}
      else if(m.type==='offer'){const p=await peer(m.from,false);if(p){await p.setRemoteDescription(m.data);const a=await p.createAnswer();await p.setLocalDescription(a);signal({type:'answer',target:m.from,data:a})}}
      else if(m.type==='answer'){const p=peers.get(m.from);if(p)await p.setRemoteDescription(m.data)}
      else if(m.type==='ice'){const p=await peer(m.from,false);if(p&&m.data)try{await p.addIceCandidate(m.data)}catch(_){}}
    }catch(e){fail(e)}
  };
}
async function setup(){
  status('connecting');
  try{
    if(!navigator.mediaDevices)throw new TypeError('mediaDevices undefined');
    localStream=await navigator.mediaDevices.getUserMedia({audio:true,video:MODE==='video'?{facingMode:facing}:false});
  }catch(e){fail(e);return}
  if(MODE==='video'){localVideo.srcObject=localStream;localVideo.style.display='block'}
  connect();
}
window.pigeonCall={
  setMuted(m){muted=!!m;(localStream?localStream.getAudioTracks():[]).forEach(t=>t.enabled=!muted);signal({type:'mute',data:{muted}})},
  setCamera(on){cameraOff=!on;(localStream?localStream.getVideoTracks():[]).forEach(t=>t.enabled=!cameraOff);localVideo.style.opacity=cameraOff?'0.25':'1';signal({type:'camera',data:{off:cameraOff}})},
  async switchCamera(){
    if(MODE!=='video'||!localStream)return;
    facing=facing==='user'?'environment':'user';
    try{
      const s=await navigator.mediaDevices.getUserMedia({video:{facingMode:facing}});
      const nt=s.getVideoTracks()[0];
      const old=localStream.getVideoTracks()[0];
      peers.forEach(pc=>{const sn=pc.getSenders().find(x=>x.track&&x.track.kind==='video');if(sn)sn.replaceTrack(nt)});
      if(old){localStream.removeTrack(old);old.stop()}
      localStream.addTrack(nt);nt.enabled=!cameraOff;
      localVideo.srcObject=localStream;
      localVideo.classList.toggle('mirror',facing==='user');
    }catch(e){facing=facing==='user'?'environment':'user';fail(e)}
  },
  end(){ended=true;try{if(ws)ws.close()}catch(_){};peers.forEach((_,id)=>dropPeer(id));(localStream?localStream.getTracks():[]).forEach(t=>t.stop());status('ended')},
};
setup();
</script></body></html>
""".trimIndent()
}
