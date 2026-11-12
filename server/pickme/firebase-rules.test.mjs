import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
const host = process.env.FIREBASE_DATABASE_EMULATOR_HOST;
if (!host) throw new Error('Firebase emulator is required; never run this test on a live database.');
const base = `http://${host}`;
const ns = 'demo-pickme-invites-default-rtdb';
async function call(path, method = 'GET', body) {
  const response = await fetch(`${base}/${path}.json?ns=${ns}`, { method, headers: {'Content-Type':'application/json'}, ...(body ? {body:JSON.stringify(body)} : {}) });
  const data = await response.json();
  return { ok: response.ok, data, status: response.status };
}
function record() { return {contactId:'demo-contact-01',passengerName:'친구 1',driverName:'운전자',status:'pending',createdAt:{'.sv':'timestamp'},expiresAt:Date.now()+23*3600000}; }
test('invite rules: unguessable link access, immutable fields, single response, expiry', async () => {
  assert.equal((await call('pickmeInvites')).ok, false);
  assert.equal((await call('')).ok, false);
  assert.equal((await call('pickmeInvites/short','PUT',record())).ok, false);
  const key=randomBytes(24).toString('base64url');
  const path=`pickmeInvites/${key}`;
  assert.equal((await call(path,'PUT',record())).ok,true);
  assert.equal((await call(path)).data.passengerName,'친구 1');
  assert.equal((await call(path,'PATCH',{driverName:'tampered'})).ok,false);
  assert.equal((await call(path,'PATCH',{phone:'010-real-number'})).ok,false);
  assert.equal((await call(path,'PATCH',{status:'accepted'})).ok,false);
  const location={latitude:37.55815,longitude:127.04375,accuracy:10,source:'demo'};
  assert.equal((await call(path,'PATCH',{locationRequest:{requestId:'a'.repeat(32),location}})).ok,true);
  assert.equal((await call(path,'PATCH',{locationRequest:{requestId:'a'.repeat(32),location:{...location,latitude:91}}})).ok,false);
  assert.equal((await call(path,'PATCH',{status:'accepted',respondedAt:{'.sv':'timestamp'},pickupPoint:{latitude:91,longitude:127,label:'invalid',source:'manual',locationSource:'gps'},locationRequest:null})).ok,false);
  assert.equal((await call(path,'PATCH',{locationRequest:null})).ok,true);
  assert.equal((await call(path,'PATCH',{status:'accepted',respondedAt:{'.sv':'timestamp'},pickupPoint:{latitude:37.55715,longitude:127.04375,label:'직접 선택한 탑승 장소',source:'manual',locationSource:'demo'}})).ok,true);
  assert.equal((await call(path,'PATCH',{status:'declined',respondedAt:{'.sv':'timestamp'}})).ok,false);
  assert.equal((await call(path,'DELETE')).ok,false);
  const parkingPath=`pickmeInvites/${randomBytes(24).toString('base64url')}`;
  assert.equal((await call(parkingPath,'PUT',{...record(),journey:{destinationLabel:'서울숲',source:'pleos'}})).ok,true);
  assert.equal((await call(parkingPath,'PATCH',{journey:{destinationLabel:'변경',source:'pleos'}})).ok,false);
  const candidate={id:'osm-node-123',label:'테스트 주차장',latitude:37.5665,longitude:126.978,verification:'map-unverified',distanceMeters:100,note:'지도상 후보 · 현장 확인 필요'};
  assert.equal((await call(parkingPath,'PATCH',{proposal:{requestId:'b'.repeat(32),kind:'candidate',candidate}})).ok,true);
  const point={latitude:candidate.latitude,longitude:candidate.longitude,label:candidate.label,candidateId:candidate.id,source:'recommended',locationSource:'gps'};
  assert.equal((await call(parkingPath,'PATCH',{status:'accepted',respondedAt:{'.sv':'timestamp'},pickupPoint:{...point,latitude:0},proposal:null})).ok,false);
  assert.equal((await call(parkingPath,'PATCH',{status:'accepted',respondedAt:{'.sv':'timestamp'},pickupPoint:point,proposal:null})).ok,true);
  const invalid=`pickmeInvites/${randomBytes(24).toString('base64url')}`;
  assert.equal((await call(invalid,'PUT',{...record(),passengerName:'arbitrary'})).ok,false);
  assert.equal((await call(invalid,'PUT',{...record(),expiresAt:Date.now()-1})).ok,false);
  assert.equal((await call(invalid,'PUT',{...record(),expiresAt:Date.now()+25*3600000})).ok,false);
  assert.equal((await call(invalid,'PUT',{...record(),extra:'unexpected'})).ok,false);
  assert.equal((await call('stores')).ok,true);
  assert.equal((await call('stores/test','PUT',{name:'invalid write'})).ok,false);
});
test('registered demo candidates must match their exact coordinates', async () => {
  const path=`pickmeInvites/${randomBytes(24).toString('base64url')}`;
  assert.equal((await call(path,'PUT',record())).ok,true);
  const candidate={id:'demo-pangyo-station',label:'판교역 부근',latitude:37.394373,longitude:127.111152,verification:'demo',distanceMeters:150,note:'시연용 후보'};
  assert.equal((await call(path,'PATCH',{proposal:{requestId:'c'.repeat(32),kind:'candidate',candidate:{...candidate,latitude:37.4}}})).ok,false);
  assert.equal((await call(path,'PATCH',{proposal:{requestId:'c'.repeat(32),kind:'candidate',candidate:{...candidate,id:'demo-made-up'}}})).ok,false);
  assert.equal((await call(path,'PATCH',{proposal:{requestId:'c'.repeat(32),kind:'candidate',candidate}})).ok,true);
  const point={latitude:candidate.latitude,longitude:candidate.longitude,label:candidate.label,candidateId:candidate.id,source:'recommended',locationSource:'gps'};
  assert.equal((await call(path,'PATCH',{status:'accepted',respondedAt:{'.sv':'timestamp'},pickupPoint:point,proposal:null})).ok,true);
});
test('searched place: up to three picks, any of them can be confirmed, forged picks are rejected', async () => {
  const path=`pickmeInvites/${randomBytes(24).toString('base64url')}`;
  assert.equal((await call(path,'PUT',record())).ok,true);
  const location={latitude:37.394373,longitude:127.111152,accuracy:0,source:'search'};
  assert.equal((await call(path,'PATCH',{locationRequest:{requestId:'d'.repeat(32),location}})).ok,true);
  assert.equal((await call(path,'PATCH',{locationRequest:{requestId:'d'.repeat(32),location:{...location,source:'typed'}}})).ok,false);
  const station={id:'demo-pangyo-station',label:'판교역 부근',latitude:37.394373,longitude:127.111152,verification:'demo',distanceMeters:0,note:'시연용 후보'};
  const lot={id:'osm-node-1',label:'테스트 주차장',latitude:37.3947,longitude:127.111152,verification:'map-unverified',distanceMeters:40,note:'지도상 후보'};
  const third={id:'osm-way-2',label:'세 번째 주차장',latitude:37.3950,longitude:127.1115,verification:'map-unverified',distanceMeters:80,note:'지도상 후보'};
  const fourth={...third,id:'osm-way-3'};
  assert.equal((await call(path,'PATCH',{proposal:{requestId:'d'.repeat(32),kind:'candidate',candidate:station,candidates:[station,lot,third,fourth]}})).ok,false);
  assert.equal((await call(path,'PATCH',{proposal:{requestId:'d'.repeat(32),kind:'none',candidates:[lot]}})).ok,false);
  assert.equal((await call(path,'PATCH',{proposal:{requestId:'d'.repeat(32),kind:'candidate',candidate:station,candidates:[station,{...lot,id:'demo-made-up',verification:'demo'}]}})).ok,false);
  assert.equal((await call(path,'PATCH',{proposal:{requestId:'d'.repeat(32),kind:'candidate',candidate:station,candidates:[station,lot,third]}})).ok,true);
  const pick=c=>({latitude:c.latitude,longitude:c.longitude,label:c.label,candidateId:c.id,source:'recommended',locationSource:'manual'});
  assert.equal((await call(path,'PATCH',{status:'accepted',respondedAt:{'.sv':'timestamp'},pickupPoint:{...pick(third),latitude:37.5},locationRequest:null,proposal:null})).ok,false);
  assert.equal((await call(path,'PATCH',{status:'accepted',respondedAt:{'.sv':'timestamp'},pickupPoint:pick(third),locationRequest:null,proposal:null})).ok,true);
});
test('kakao picks are accepted only with kakao ids and can be confirmed', async () => {
  const path=`pickmeInvites/${randomBytes(24).toString('base64url')}`;
  assert.equal((await call(path,'PUT',record())).ok,true);
  const lot={id:'kakao-12345',label:'왕십리역 공영주차장',latitude:37.5615,longitude:127.0381,verification:'kakao',distanceMeters:40,note:'카카오맵 주차장'};
  assert.equal((await call(path,'PATCH',{proposal:{requestId:'e'.repeat(32),kind:'candidate',candidate:lot,candidates:[{...lot,id:'kakao-abc'}]}})).ok,false);
  assert.equal((await call(path,'PATCH',{proposal:{requestId:'e'.repeat(32),kind:'candidate',candidate:{...lot,verification:'demo'},candidates:[lot]}})).ok,false);
  assert.equal((await call(path,'PATCH',{proposal:{requestId:'e'.repeat(32),kind:'candidate',candidate:lot,candidates:[lot]}})).ok,true);
  const point={latitude:lot.latitude,longitude:lot.longitude,label:lot.label,candidateId:lot.id,source:'recommended',locationSource:'gps'};
  assert.equal((await call(path,'PATCH',{status:'accepted',respondedAt:{'.sv':'timestamp'},pickupPoint:point,locationRequest:null,proposal:null})).ok,true);
});

test('orders rules: 형식이 맞는 주문만, 삭제·주문 내용 변경은 거부', async () => {
  // 매장 데이터는 앱에서 쓸 수 없으므로 에뮬레이터 관리자 권한으로 준비한다.
  await fetch(`${base}/stores/store-a.json?ns=${ns}`, { method: 'PUT', headers: { Authorization: 'Bearer owner' }, body: JSON.stringify({ name: '테스트 매장' }) });
  const order = { storeId: 'store-a', item: '돌체 라떼', qty: 1, status: 'ordered', etaAt: '2026-11-05T15:20:00+09:00', createdAt: '2026-11-05T15:00:00+09:00' };
  const path = `orders/test-${randomBytes(6).toString('hex')}`;
  assert.equal((await call(path, 'PUT', { ...order, status: 'ready' })).ok, false, '새 주문은 ordered로 시작');
  assert.equal((await call(path, 'PUT', { ...order, storeId: 'no-such-store' })).ok, false, '없는 매장 거부');
  assert.equal((await call(path, 'PUT', { ...order, qty: 0 })).ok, false);
  assert.equal((await call(path, 'PUT', order)).ok, true);
  for (const status of ['accepted', 'making', 'ready', 'picked_up']) assert.equal((await call(`${path}/status`, 'PUT', status)).ok, true);
  assert.equal((await call(`${path}/status`, 'PUT', 'hacked')).ok, false);
  assert.equal((await call(`${path}/etaAt`, 'PUT', '2026-11-05T15:30:00+09:00')).ok, true);
  assert.equal((await call(`${path}/item`, 'PUT', '다른 메뉴')).ok, false, '주문 내용 변경 거부');
  assert.equal((await call(path, 'DELETE')).ok, false, '삭제 거부');
  assert.equal((await call('orders', 'PUT', {})).ok, false, '전체 덮어쓰기 거부');
  assert.equal((await call(path)).ok, true);
});
