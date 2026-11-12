import {test} from 'node:test';
import assert from 'node:assert/strict';
import {once} from 'node:events';
import {createInvitationServer} from './server.mjs';
test('driver acceptance, consent, live update, stop, owner isolation and TTL',async t=>{
 let now=Date.now();const server=createInvitationServer({now:()=>now});server.listen(0,'127.0.0.1');await once(server,'listening');t.after(()=>{server.closeAllConnections();server.close();});
 const base=`http://127.0.0.1:${server.address().port}`;
 async function call(path,body,headers={}){const r=await fetch(base+path,{method:body?'POST':'GET',headers:{'Content-Type':'application/json',...headers},...(body?{body:JSON.stringify(body)}:{})});return {status:r.status,data:await r.json(),cookie:r.headers.get('set-cookie')?.split(';')[0]};}
 const owner=await call('/api/driver/session',{}),stranger=await call('/api/driver/session',{}),headers={Cookie:owner.cookie};
 const invite=await call('/api/pickups',{contactId:'demo-contact-03'},headers),id=invite.data.id,friend={Authorization:`Bearer ${new URL(invite.data.inviteUrl).hash.slice(1)}`};
 assert.equal((await call(`/api/pickups/${id}/driver-accept`,{sharingAllowed:true},headers)).status,409);
 await call('/api/friend/invitation',{status:'accepted',pickupPoint:{latitude:37.4,longitude:127.1,label:'픽업',source:'manual',locationSource:'demo'}},friend);
 assert.equal((await call(`/api/pickups/${id}/driver-accept`,{sharingAllowed:true},{Cookie:stranger.cookie})).status,404);
 assert.equal((await call(`/api/pickups/${id}/driver-accept`,{sharingAllowed:false},headers)).status,200);
 const sample={state:'on_way',position:{latitude:37.41,longitude:127.11},pickupEtaSeconds:540};
 assert.equal((await call(`/api/pickups/${id}/live`,sample,headers)).status,409);
 await call(`/api/pickups/${id}/driver-accept`,{sharingAllowed:true,isEmulator:true},headers);
 assert.equal((await call(`/api/pickups/${id}/live`,sample,friend)).status,401);
 assert.equal((await call(`/api/pickups/${id}/live`,sample,headers)).status,200);
 const cafeVisit={name:'시연 카페',phase:'before_pickup',position:{latitude:37.42,longitude:127.12}};
 assert.equal((await call(`/api/pickups/${id}/live`,{...sample,cafeVisit,cafeVisitCapturedAt:now},headers)).status,200);
 assert.equal((await call('/api/friend/live',null,friend)).data.cafeVisit.phase,'before_pickup');
 for(const phase of ['at_cafe','passed']) {
  await call(`/api/pickups/${id}/live`,{...sample,cafeVisit:{...cafeVisit,phase},cafeVisitCapturedAt:now},headers);
  assert.equal((await call('/api/friend/live',null,friend)).data.cafeVisit.phase,phase);
 }
 const live=await call('/api/friend/live',null,friend);assert.equal(live.data.pickupEtaSeconds,540);assert.equal(live.data.position.latitude,37.41);
 await call(`/api/pickups/${id}/live`,{state:'paused'},headers);
 assert.equal((await call('/api/friend/live',null,friend)).data.position,undefined);
 assert.equal((await call('/api/friend/live',null,friend)).data.cafeVisit,undefined);
 now+=3600001;
 assert.equal((await call('/api/friend/live',null,friend)).data,null);
 assert.equal((await call(`/api/pickups/${id}/driver-accept`,{sharingAllowed:true},headers)).status,410);
});
