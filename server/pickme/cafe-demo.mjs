/** Explicit synthetic cloud smoke test. Uses the running relay; never calls email/order APIs. */
import {createInterface} from 'node:readline';
const base=process.env.PICKME_TEST_RELAY_URL || 'http://127.0.0.1:8787';
let cookie='',invite,token,timer;
async function api(path,body,friend=false) {
 const r=await fetch(base+path,{signal:AbortSignal.timeout(15000),method:body?'POST':'GET',headers:{'Content-Type':'application/json',...(friend?{Authorization:`Bearer ${token}`}:{Cookie:cookie})},...(body?{body:JSON.stringify(body)}:{})});
 if(path==='/api/driver/session')cookie=r.headers.get('set-cookie')?.split(';')[0]??'';
 const data=await r.json();if(!r.ok)throw Error(`${path}: HTTP ${r.status}`);return data;
}
await api('/api/driver/session',{});
invite=await api('/api/pickups',{contactId:'demo-contact-03',driverName:'카페 경유 시연',trip:{source:'demo',capturedAt:Date.now(),origin:{latitude:37.412,longitude:127.099},destination:{latitude:37.4027,longitude:127.108,label:'CGV 판교 · 시연'}}});
token=new URL(invite.inviteUrl).hash.slice(1);
await api('/api/friend/invitation',{status:'accepted',pickupPoint:{latitude:37.402773,longitude:127.106818,label:'판교 시연 탑승 장소',source:'manual',locationSource:'demo'}},true);
await api(`/api/pickups/${invite.id}/driver-accept`,{sharingAllowed:true,isEmulator:true});
const samples={
 before_pickup:{position:{latitude:37.412,longitude:127.099},pickupEtaSeconds:720},
 at_cafe:{position:{latitude:37.407,longitude:127.103},pickupEtaSeconds:420},
 passed:{position:{latitude:37.405,longitude:127.105},pickupEtaSeconds:180},
 arriving:{position:{latitude:37.403,longitude:127.1066},pickupEtaSeconds:60},
};
let stage='before_pickup',upload=Promise.resolve();
function publish() {
 const payload=stage==='paused'?{state:'paused'}:{state:'on_way',...samples[stage],pickupEtaCapturedAt:Date.now(),cafeVisit:{name:'픽미 시연 카페',phase:stage==='arriving'?'passed':stage,position:{latitude:37.407,longitude:127.103}},cafeVisitCapturedAt:Date.now()};
 upload=upload.catch(()=>{}).then(()=>api(`/api/pickups/${invite.id}/live`,payload));
 return upload;
}

await publish();
console.log('SYNTHETIC TEST ONLY — no email/order sent. Native GPS/arrival is not simulated by this tool.');
console.log('FRIEND_URL '+invite.inviteUrl);
console.log('Commands: before_pickup / at_cafe / passed / arriving / paused / quit');
timer=setInterval(()=>publish().catch(e=>console.error('Publish failed:',e.message)),5000);
const lines=createInterface({input:process.stdin});
for await(const command of lines) {
 const next=command.trim();
 if(next==='quit')break;
 if(next!=='paused' && !Object.hasOwn(samples,next)){console.log('Unknown stage');continue;}
 if(stage==='paused'){console.log('Sharing ended. Start a new demo.');continue;}
 stage=next;await publish();console.log('STAGE '+stage);
 if(stage==='paused')clearInterval(timer);
}
clearInterval(timer);lines.close();process.stdin.pause();stage='paused';await publish().catch(()=>{});
