import {test} from 'node:test';
import assert from 'node:assert/strict';
import {parkingCandidates,createParkingSearch} from './parking-search.mjs';
import {validatePickupPoint} from './pickup-planner.mjs';
const here={latitude:37.5665,longitude:126.978,accuracy:15,source:'gps'};
const item=(id,tags={},lat=here.latitude)=>({type:'node',id,lat,lon:here.longitude,tags:{amenity:'parking',name:`주차장 ${id}`,...tags}});
test('parking filter excludes restricted/roadside/timed facilities and sorts by walking distance',()=>{
 const result=parkingCandidates([item(1,{access:'private'}),item(2,{access:'customers'}),item(3,{parking:'street_side'}),item(4,{motorcar:'no'}),item(5,{opening_hours:'Mo-Fr 09:00-18:00'}),item(6,{access:'yes',fee:'yes'},here.latitude+0.002),item(7),item(8,{access:'yes'},35),item(9,{'access:conditional':'no @ (Mo-Fr)'})],here);
 // The nearer lot wins even when its public access is unconfirmed.
 assert.deepEqual(result.map(c=>c.id),['osm-node-7','osm-node-6']);
 assert.equal(result[1].verification,'map-unverified');assert.ok(result[1].note.includes('유료'));assert.ok(result[0].note.includes('미확인'));
 assert.equal(validatePickupPoint({...result[1],source:'recommended',candidateId:result[1].id,locationSource:'gps'},result).candidateId,'osm-node-6');
});
test('external search uses rounded coordinates and cache; outage differs from no candidates',async()=>{
 let calls=0;
 const search=createParkingSearch({fetchImpl:async(url,options)=>{calls++;const query=new URLSearchParams(options.body).get('data');assert.ok(query.includes('37.566,126.978') || query.includes('37.567,126.978'));return {ok:true,json:async()=>({elements:[item(1,{access:'yes'})]})};}});
 assert.equal((await search(here,'a'.repeat(32))).kind,'candidate');
 assert.equal((await search(here,'b'.repeat(32))).requestId,'b'.repeat(32));assert.equal(calls,1);
 const failed=createParkingSearch({fetchImpl:async()=>{throw Error('offline');}});
 assert.equal((await failed(here,'c'.repeat(32))).kind,'unavailable');
 const empty=createParkingSearch({fetchImpl:async()=>({ok:true,json:async()=>({elements:[]})})});
 assert.equal((await empty(here,'d'.repeat(32))).kind,'none');
 const demo=await failed({...here,latitude:37.55815,longitude:127.04375,source:'demo'},'e'.repeat(32));assert.equal(demo.candidate.verification,'demo');
});
test('registered demo spots answer real GPS when the external search is down',async()=>{
 let calls=0;
 const search=createParkingSearch({fetchImpl:async()=>{calls++;throw Error('offline');}});
 const nearStation=await search({latitude:37.3950,longitude:127.1100,accuracy:20,source:'gps'},'f'.repeat(32));
 assert.equal(nearStation.kind,'candidate');assert.equal(nearStation.candidate.id,'demo-pangyo-station');assert.equal(calls,1);
 assert.equal(validatePickupPoint({latitude:nearStation.candidate.latitude,longitude:nearStation.candidate.longitude,label:nearStation.candidate.label,candidateId:nearStation.candidate.id,source:'recommended',locationSource:'gps'}).candidateId,'demo-pangyo-station');
 assert.equal((await search({latitude:35.1,longitude:129.0,accuracy:20,source:'gps'},'g'.repeat(32))).kind,'unavailable');assert.equal(calls,2);
});

test('picks: nearest three within 500m, team spots and map lots mixed by distance',async()=>{
 const station={latitude:37.394373,longitude:127.111152};
 const at=(id,m)=>({type:'node',id,lat:station.latitude+m/111000,lon:station.longitude,tags:{amenity:'parking',name:`주차장 ${id}`}});
 const search=createParkingSearch({fetchImpl:async()=>({ok:true,json:async()=>({elements:[at(1,40),at(2,450),at(3,520),at(4,900)]})})});
 const result=await search({...station,accuracy:0,source:'search'},'h'.repeat(32));
 assert.equal(result.kind,'candidate');
 // Team station spot (0m), lot 40m, then 데모 현대백화점 (~180m) — the 450m lot is 4th and lots over 500m never appear.
 assert.deepEqual(result.candidates.map(c=>c.id),['demo-pangyo-station','osm-node-1','demo-hyundai-pangyo']);
 assert.ok(result.candidates.every(c=>c.distanceMeters<=500));
 assert.equal(result.candidate.id,result.candidates[0].id);
 const second=result.candidates[1];
 assert.equal(validatePickupPoint({latitude:second.latitude,longitude:second.longitude,label:second.label,candidateId:second.id,source:'recommended',locationSource:'manual'},result.candidates).candidateId,'osm-node-1');
 assert.throws(()=>validatePickupPoint({latitude:1,longitude:1,label:second.label,candidateId:second.id,source:'recommended',locationSource:'manual'},result.candidates));
});
test('picks: nothing within 500m is reported as none, not as a far spot',async()=>{
 const far={latitude:37.3990,longitude:127.1110,accuracy:0,source:'search'}; // ~520m north of 판교역 spot
 const search=createParkingSearch({fetchImpl:async()=>({ok:true,json:async()=>({elements:[]})})});
 assert.equal((await search(far,'i'.repeat(32))).kind,'none');
});
test('kakao: rounded coordinate and key header only, real names, 500m filter, nearest first',async()=>{
 const wangsimni={latitude:37.5612,longitude:127.0381,accuracy:0,source:'search'};
 const doc=(id,dLat,name)=>({id:String(id),place_name:name,x:String(127.0381),y:String(37.5612+dLat/111000),road_address_name:'서울 성동구 왕십리광장로 17'});
 let seen;
 const search=createParkingSearch({kakaoKey:'test-key',fetchImpl:async(url,options)=>{seen={url,options};return {ok:true,json:async()=>({documents:[doc(3,300,'왕십리역 공영주차장'),doc(1,70,'비트플렉스 주차장'),doc(2,620,'먼 주차장'),{id:'x',place_name:'잘못된 id',x:'127',y:'37'}]})};}});
 const result=await search(wangsimni,'k'.repeat(32));
 assert.equal(seen.url.origin,'https://dapi.kakao.com');assert.equal(seen.url.searchParams.get('category_group_code'),'PK6');
 assert.equal(seen.url.searchParams.get('y'),'37.561');assert.equal(seen.url.searchParams.get('x'),'127.038');
 assert.equal(seen.options.headers.Authorization,'KakaoAK test-key');assert.equal(seen.url.searchParams.has('token'),false);
 assert.deepEqual(result.candidates.map(c=>c.label),['비트플렉스 주차장','왕십리역 공영주차장']);
 assert.equal(result.candidates[0].id,'kakao-1');assert.equal(result.candidates[0].verification,'kakao');
 assert.ok(result.candidates[0].note.includes('카카오맵'));
 const pick=result.candidates[1];
 assert.equal(validatePickupPoint({latitude:pick.latitude,longitude:pick.longitude,label:pick.label,candidateId:pick.id,source:'recommended',locationSource:'manual'},result.candidates).candidateId,'kakao-3');
 const down=createParkingSearch({kakaoKey:'test-key',fetchImpl:async()=>({ok:false})});
 assert.equal((await down(wangsimni,'l'.repeat(32))).kind,'unavailable');
});
