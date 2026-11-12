import {test} from 'node:test';
import assert from 'node:assert/strict';
import {recommend,validatePickupPoint} from './pickup-planner.mjs';
import {createInvitationServer} from './server.mjs';
import {once} from 'node:events';
test('nearby candidate, unsupported region, invalid GPS and forged recommendation',()=>{
  const proposal=recommend({latitude:37.55815,longitude:127.04375,accuracy:10,source:'demo'},'a'.repeat(32));
  assert.equal(proposal.kind,'candidate');assert.equal(proposal.candidate.verification,'demo');assert.ok(proposal.candidate.distanceMeters>100);
  assert.equal(recommend({latitude:35,longitude:129,accuracy:10,source:'gps'},'b'.repeat(32)).kind,'none');
  assert.throws(()=>recommend({latitude:NaN,longitude:127,accuracy:0,source:'gps'},'c'.repeat(32)));
  assert.throws(()=>validatePickupPoint({latitude:0,longitude:0,label:'fake',source:'recommended',locationSource:'gps',candidateId:'hanyang-seoul-main-demo'}));
});
test('friend recommends, changes pin, confirms; driver receives final coordinates',async t=>{
 const server=createInvitationServer();server.listen(0,'127.0.0.1');await once(server,'listening');t.after(()=>{server.closeAllConnections();server.close();});
 const base=`http://127.0.0.1:${server.address().port}`;
 async function call(path,body,headers={}){const res=await fetch(base+path,{method:body?'POST':'GET',headers:{'Content-Type':'application/json',...headers},...(body?{body:JSON.stringify(body)}:{})});return {status:res.status,data:await res.json(),cookie:res.headers.get('set-cookie')?.split(';')[0]};}
 const owner=await call('/api/driver/session',{}),cookie={Cookie:owner.cookie};
 const invite=await call('/api/pickups',{contactId:'demo-contact-01'},cookie);
 const auth={Authorization:`Bearer ${new URL(invite.data.inviteUrl).hash.slice(1)}`};
 const proposal=await call('/api/friend/recommendation',{requestId:'d'.repeat(32),location:{latitude:37.55815,longitude:127.04375,accuracy:10,source:'demo'}},auth);
 assert.equal(proposal.data.kind,'candidate');
 assert.equal((await call('/api/friend/invitation',{status:'accepted'},auth)).status,400);
 const point={latitude:37.5573,longitude:127.044,label:'정문 안쪽에서 만나요',source:'manual',locationSource:'demo'};
 assert.equal((await call('/api/friend/invitation',{status:'accepted',pickupPoint:point},auth)).status,200);
 const driver=await call('/api/pickups/'+invite.data.id,null,cookie);
 assert.deepEqual(driver.data.pickupPoint,point);assert.equal(driver.data.meetingPoint,point.label);
 const repeated=await call('/api/friend/invitation',{status:'accepted',pickupPoint:{...point,latitude:0}},auth);
 assert.deepEqual(repeated.data.pickupPoint,point);
 assert.equal((await call('/api/friend/recommendation',{requestId:'d'.repeat(32),location:{latitude:37.55815,longitude:127.04375,accuracy:10,source:'demo'}},auth)).status,409);
});
