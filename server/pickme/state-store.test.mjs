import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,rmSync,statSync,writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createStateStore} from './state-store.mjs';
test('state survives reopening with private file permissions and isolated snapshots',t=>{
 const dir=mkdtempSync(join(tmpdir(),'pickme-state-'));t.after(()=>rmSync(dir,{recursive:true,force:true}));const file=join(dir,'state.json');
 const store=createStateStore(file);assert.equal(store.get('relay'),undefined);
 store.set('relay',{sessions:[['fixture',123]]});store.set('firebaseAuth',{uid:'fixture'});
 const saved=store.get('relay');saved.sessions.length=0;
 const reopened=createStateStore(file);assert.equal(reopened.get('relay').sessions.length,1);assert.equal(reopened.get('firebaseAuth').uid,'fixture');
 assert.equal(statSync(file).mode & 0o777,0o600);
 writeFileSync(file,'broken');assert.throws(()=>createStateStore(file));
});

test('relay restart preserves owner access and the same friend invitation',async t=>{
 const {createInvitationServer}=await import('./server.mjs');const {once}=await import('node:events');
 const dir=mkdtempSync(join(tmpdir(),'pickme-restart-'));const file=join(dir,'state.json');let server;
 t.after(()=>{server?.closeAllConnections();server?.close();rmSync(dir,{recursive:true,force:true});});
 async function start(){server=createInvitationServer({publicBaseUrl:'https://example.com',stateStore:createStateStore(file)});server.listen(0,'127.0.0.1');await once(server,'listening');return `http://127.0.0.1:${server.address().port}`;}
 let base=await start();
 const session=await fetch(base+'/api/driver/session',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'});const cookie=session.headers.get('set-cookie').split(';')[0];
 const created=await fetch(base+'/api/pickups',{method:'POST',headers:{'Content-Type':'application/json',Cookie:cookie},body:JSON.stringify({contactId:'demo-contact-01'})});assert.equal(created.status,201);const invite=await created.json();
 await new Promise(resolve=>{server.close(resolve);server.closeAllConnections();});base=await start();
 const restored=await fetch(base+'/api/pickups/'+invite.id,{headers:{Cookie:cookie}});assert.equal(restored.status,200);assert.equal((await restored.json()).id,invite.id);
 const friend=await fetch(base+'/api/friend/invitation',{headers:{Authorization:'Bearer '+new URL(invite.inviteUrl).hash.slice(1)}});assert.equal(friend.status,200);assert.equal((await friend.json()).passengerName,'친구 1');
});
