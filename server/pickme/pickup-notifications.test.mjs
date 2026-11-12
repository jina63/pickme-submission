import {test} from 'node:test';
import assert from 'node:assert/strict';
import {once} from 'node:events';
import {createInvitationServer} from './server.mjs';

for(const arrivalFails of [false,true]) test(`contact mapping, ready state and one automatic arrival mail (SMTP failure=${arrivalFails})`,async t=>{
  let clock=Date.now(),failArrival=false;const sent=[];
  const server=createInvitationServer({publicBaseUrl:'https://example.com',now:()=>clock,mailSender:async m=>{sent.push(m);if(failArrival && m.kind==='arrival')throw Error('SMTP unknown');}});
  server.listen(0,'127.0.0.1');await once(server,'listening');t.after(()=>{server.closeAllConnections();server.close();});
  const base=`http://127.0.0.1:${server.address().port}`;
  async function call(path,body,headers={}){const r=await fetch(base+path,{method:body?'POST':'GET',headers:{'Content-Type':'application/json',...headers},...(body?{body:JSON.stringify(body)}:{})});return {status:r.status,data:await r.json(),cookie:r.headers.get('set-cookie')?.split(';')[0]};}
  const cookie=(await call('/api/driver/session',{})).cookie,owner={Cookie:cookie};
  const expected=['friend1@example.invalid','friend2@example.invalid','friend3@example.invalid','friend4@example.invalid'];
  const contacts=(await call('/api/demo/contacts')).data.contacts;
  assert.equal(contacts.length,4);
  for(let n=0;n<4;n++){
    const invite=(await call('/api/pickups',{contactId:contacts[n].id},owner)).data;
    assert.equal((await call(`/api/pickups/${invite.id}/email`,{},owner)).status,200);
    assert.equal(sent.at(-1).to,expected[n]);
    assert.equal((await call(`/api/pickups/${invite.id}/email`,{email:expected[(n+1)%4]},owner)).status,400);
  }
  const invite=(await call('/api/pickups',{contactId:'demo-contact-03'},owner)).data;
  await call(`/api/pickups/${invite.id}/email`,{},owner);
  const auth={Authorization:`Bearer ${new URL(invite.inviteUrl).hash.slice(1)}`};
  assert.equal((await call('/api/friend/ready',{},auth)).status,409);
  assert.equal((await call('/api/friend/ready',{}, {Authorization:'Bearer wrong'})).status,404);
  await call('/api/friend/invitation',{status:'accepted',pickupPoint:{latitude:37.4,longitude:127.1,label:'테스트 탑승 장소',source:'manual',locationSource:'demo'}},auth);
  const first=(await call('/api/friend/ready',{},auth)).data.readyAt;
  clock+=1000;assert.equal((await call('/api/friend/ready',{},auth)).data.readyAt,first);
  assert.equal((await call(`/api/pickups/${invite.id}`,null,owner)).data.passengerReadyAt,first);
  const path=`/api/pickups/${invite.id}`;
  await call(path+'/driver-accept',{sharingAllowed:false,isEmulator:true},owner);
  const moving={state:'on_way',position:{latitude:37.4,longitude:127.1},pickupEtaSeconds:300};
  assert.equal((await call(path+'/live',moving,owner)).status,409);
  await call(path+'/driver-accept',{sharingAllowed:true,isEmulator:true},owner);
  await call(path+'/live',{...moving,pickupEtaSeconds:301},owner);
  await call(path+'/live',{...moving,pickupEtaCapturedAt:clock-21000},owner);
  await call(path+'/live',{state:'on_way',position:moving.position},owner);
  assert.equal(sent.filter(m=>m.kind==='arrival').length,0);
  failArrival=arrivalFails;
  await Promise.all([call(path+'/live',{...moving,pickupEtaCapturedAt:clock},owner),call(path+'/live',moving,owner)]);
  await new Promise(r=>setTimeout(r,25));
  await call(path+'/live',{...moving,pickupEtaSeconds:60},owner);
  assert.equal(sent.filter(m=>m.kind==='arrival').length,1);
  assert.equal((await call(path,null,owner)).data.arrivalMailState,arrivalFails?'unknown':'submitted');
  assert.equal(sent.at(-1).to,'friend3@example.invalid');
});
