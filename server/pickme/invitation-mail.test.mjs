import nodemailer from 'nodemailer';
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {once} from 'node:events';
import {createInvitationServer} from './server.mjs';
import {createGmailSender,invitationMailContent} from './invitation-mail.mjs';

test('Gmail sender uses TLS, app password and the server invitation URL',async()=>{
  assert.equal(createGmailSender({}),null);
  let options, message;
  const sender=createGmailSender({PICKME_GMAIL_USER:'team@example.com',PICKME_GMAIL_APP_PASSWORD:'test only'}, o=>{
    options=o;return {sendMail:async m=>{message=m;return {accepted:[m.to]};}};
  });
  await sender({to:'friend3@example.invalid',inviteUrl:'https://example.com/friend#test'});
  assert.equal(options.secure,true); assert.equal(options.auth.pass,'testonly');
  assert.equal(message.to,'friend3@example.invalid');assert.ok(message.text.includes('https://example.com/friend#test'));
  assert.equal(message.disableFileAccess,true);
});

test('email: owner only, validation, duplicate/concurrent requests, failed submission and hourly limit',async t=>{
  let count=0, fail=false;
  const server=createInvitationServer({publicBaseUrl:'https://example.com',mailSender:async m=>{
    count++;assert.equal(m.to,'friend3@example.invalid');assert.ok(m.inviteUrl.startsWith('https://example.com/friend#'));
    await new Promise(r=>setTimeout(r,30));if(fail)throw Error('private SMTP details');
  }});
  server.listen(0,'127.0.0.1');await once(server,'listening');
  t.after(()=>{server.closeAllConnections();server.close();});
  const base=`http://127.0.0.1:${server.address().port}`;
  async function call(path,body,cookie) {
    const r=await fetch(base+path,{method:'POST',headers:{'Content-Type':'application/json',...(cookie?{Cookie:cookie}:{})},body:JSON.stringify(body)});
    return {status:r.status,data:await r.json(),cookie:r.headers.get('set-cookie')?.split(';')[0]};
  }
  const owner=(await call('/api/driver/session',{})).cookie;
  const other=(await call('/api/driver/session',{})).cookie;
  async function invite(){return (await call('/api/pickups',{contactId:'demo-contact-03'},owner)).data;}
  const i=await invite();const path=`/api/pickups/${i.id}/email`;
  assert.equal((await call(path,{email:'friend3@example.invalid'})).status,401);
  assert.equal((await call(path,{email:'friend3@example.invalid'},other)).status,404);
  assert.equal((await call(path,{email:'friend3@example.invalid\r\nBcc:evil@example.com'},owner)).status,400);
  assert.equal((await call(path,{email:'not-listed@example.com'},owner)).status,400);
  const results=await Promise.all([call(path,{email:'friend3@example.invalid'},owner),call(path,{email:'friend3@example.invalid'},owner)]);
  assert.deepEqual(results.map(x=>x.status).sort(),[200,409]);assert.equal(count,1);
  assert.equal(results.find(x=>x.status===200).data.status,'submitted');
  assert.equal(results.find(x=>x.status===200).data.recipient,'friend3@example.invalid');
  fail=true;const second=await invite();const secondPath=`/api/pickups/${second.id}/email`;
  const failed=await call(secondPath,{email:'friend3@example.invalid'},owner);
  assert.equal(failed.status,502);assert.ok(!JSON.stringify(failed).includes('private SMTP'));
  assert.equal((await call(secondPath,{email:'friend3@example.invalid'},owner)).status,409);
  fail=false;
  for(let n=0;n<3;n++){const j=await invite();assert.equal((await call(`/api/pickups/${j.id}/email`,{email:'friend3@example.invalid'},owner)).status,200);}
  const last=await invite();assert.equal((await call(`/api/pickups/${last.id}/email`,{email:'friend3@example.invalid'},owner)).status,429);
});

test('email is disabled without configuration and expired invitations cannot send',async t=>{
  let clock=Date.now();const server=createInvitationServer({publicBaseUrl:'https://example.com',now:()=>clock,ttlMs:1000});
  server.listen(0,'127.0.0.1');await once(server,'listening');t.after(()=>{server.closeAllConnections();server.close();});
  const base=`http://127.0.0.1:${server.address().port}`;
  const post=(path,body,cookie)=>fetch(base+path,{method:'POST',headers:{'Content-Type':'application/json',...(cookie?{Cookie:cookie}:{})},body:JSON.stringify(body)});
  const session=await post('/api/driver/session',{});const cookie=session.headers.get('set-cookie').split(';')[0];
  const invite=await (await post('/api/pickups',{contactId:'demo-contact-03'},cookie)).json();
  assert.equal((await post(`/api/pickups/${invite.id}/email`,{email:'friend3@example.invalid'},cookie)).status,503);
  clock+=2000;
  const fresh=await post('/api/driver/session',{});const freshCookie=fresh.headers.get('set-cookie').split(';')[0];
  assert.equal((await post(`/api/pickups/${invite.id}/email`,{email:'friend3@example.invalid'},freshCookie)).status,404);
});

test('mail explains driver, passenger and destination and escapes HTML',()=>{
 const content=invitationMailContent({inviteUrl:'https://example.com/friend#test',driverName:'친구 2 <친구>',passengerName:'친구 3',destinationLabel:'CGV 판교'});
 assert.ok(content.text.includes('친구 2 <친구>'));assert.ok(content.text.includes('CGV 판교'));assert.ok(content.text.includes('친구 3'));
 assert.ok(content.html.includes('&lt;친구&gt;'));assert.ok(!content.html.includes('<친구>'));
 const unknown=invitationMailContent({inviteUrl:'https://example.com/friend#test'});assert.ok(unknown.text.includes('아직 미정'));
 const arrival=invitationMailContent({inviteUrl:'https://example.com/friend#test',kind:'arrival',driverName:'친구 2',passengerName:'친구 3',destinationLabel:'CGV 판교'});
 assert.ok(arrival.subject.includes('5분'));assert.ok(arrival.text.includes('CGV 판교'));assert.ok(arrival.html.includes('차량 도착 정보 확인하기'));
});

test('both email links are tappable anchors and preserve the invitation fragment in MIME',async()=>{
 const inviteUrl='https://pickme-ca1cd-friend.web.app/friend#'+'Ab_9-'.repeat(6)+'XY';
 const content=invitationMailContent({inviteUrl,driverName:'김픽미',passengerName:'친구 3',destinationLabel:'CGV 판교'});
 const hrefs=[...content.html.matchAll(/href="([^" ]+)"/g)].map(m=>m[1]);
 assert.equal(hrefs.length,2);
 for(const href of hrefs){assert.equal(href,inviteUrl);assert.equal(new URL(href).hash,new URL(inviteUrl).hash);}
 assert.ok(!content.html.includes('주소를 복사해'));
 const transport=nodemailer.createTransport({streamTransport:true,buffer:true,newline:'windows'});
 const built=await transport.sendMail({from:'team@example.com',to:'friend@example.com',...content});
 const mime=built.message.toString().replace(/=\r?\n/g,'');
 assert.ok(mime.includes('multipart/alternative'));
 assert.ok(mime.includes(inviteUrl));
 assert.throws(()=>invitationMailContent({inviteUrl:'javascript:alert(1)'}),/HTTPS/);
 assert.throws(()=>invitationMailContent({inviteUrl:'http://example.com/friend#test'}),/HTTPS/);
});
