import {test} from 'node:test';
import assert from 'node:assert/strict';
import {liveSnapshot} from './live-tracking.mjs';
import {createFirebaseAuth} from './firebase-auth.mjs';
test('tracking validates coordinates, preserves expiry and clears location on stop',()=>{
 const previous={expiresAt:100000,sharingAllowed:true,isEmulator:true,destinationLabel:'CGV'};
 const live=liveSnapshot({state:'on_way',position:{latitude:37.4,longitude:127.1},pickupEtaSeconds:300},previous,1000);
 assert.equal(live.pickupEtaSeconds,300);assert.equal(live.expiresAt,100000);
 assert.throws(()=>liveSnapshot({state:'on_way',position:{latitude:0,longitude:0}},previous,1000));
 assert.throws(()=>liveSnapshot({state:'on_way',position:{latitude:37,longitude:127},pickupEtaSeconds:-1},previous,1000));
 const paused=liveSnapshot({state:'paused'},live,100001);assert.equal(paused.position,undefined);assert.equal(paused.pickupEtaSeconds,undefined);
});
test('server auth caches and refreshes without exposing credentials',async()=>{
 let now=0,calls=0;
 const auth=createFirebaseAuth({apiKey:'test-only',now:()=>now,fetchImpl:async()=>{calls++;return {ok:true,json:async()=>calls===1?{idToken:'one',localId:'driver',refreshToken:'refresh',expiresIn:'3600'}:{id_token:'two',user_id:'driver',refresh_token:'refresh',expires_in:'3600'}};}});
 await Promise.all([auth.credentials(),auth.credentials()]);assert.equal(calls,1);
 now=3500000;assert.equal((await auth.credentials()).idToken,'two');assert.equal(calls,2);
});

test('cafe detour uses fresh confirmed snapshots and clears on removal or pause',()=>{
 const previous={expiresAt:100000,sharingAllowed:true,isEmulator:true};
 const body={state:'on_way',position:{latitude:37.4,longitude:127.1},pickupEtaSeconds:720,cafeVisit:{name:'가는 길 카페',phase:'before_pickup',position:{latitude:37.42,longitude:127.12}},cafeVisitCapturedAt:1000};
 const live=liveSnapshot(body,previous,2000);
 assert.deepEqual(live.cafeVisit,body.cafeVisit);assert.equal(live.pickupEtaSeconds,720);
 for(const phase of ['at_cafe','passed'])assert.equal(liveSnapshot({...body,cafeVisit:{name:'카페',phase}},previous,2000).cafeVisit.phase,phase);
 assert.equal(liveSnapshot({...body,cafeVisitCapturedAt:1},previous,22000).cafeVisit,undefined);
 assert.equal(liveSnapshot({...body,cafeVisitCapturedAt:3000},previous,2000).cafeVisit,undefined);
 assert.throws(()=>liveSnapshot({...body,cafeVisitCapturedAt:undefined},previous,2000));
 assert.throws(()=>liveSnapshot({...body,cafeVisit:{...body.cafeVisit,position:{latitude:0,longitude:0}}},previous,2000));
 assert.throws(()=>liveSnapshot({...body,cafeVisit:{name:'카페',phase:'collected'}},previous,2000));
 assert.equal(liveSnapshot({state:'on_way',position:body.position},live,3000).cafeVisit,undefined);
 assert.equal(liveSnapshot({state:'paused'},live,3000).cafeVisit,undefined);
});
