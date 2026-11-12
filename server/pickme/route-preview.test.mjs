import {test} from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFile} from 'node:fs/promises';
import {encodePolyline,thinPath,pathFromDirections,createRoutePreview} from './route-preview.mjs';
const friendSource=await readFile(new URL('../../web/friend/friend.js',import.meta.url),'utf8');
const decode=new Function(friendSource.match(/function decodePolyline\(text\)\{[\s\S]*?return pts;\}/)[0]+';return decodePolyline;')();
const car={latitude:37.4127,longitude:127.0947},cafe={latitude:37.3947,longitude:127.1115},pickup={latitude:37.4873,longitude:127.1017};
const directions=(pts)=>({routes:[{result_code:0,sections:[{roads:[{vertexes:pts.flatMap(([a,b])=>[b,a])}]}]}]});
test('polyline: web decoder restores what the server encoded',()=>{
 const pts=[[37.41270,127.09470],[37.39470,127.11150],[37.48730,127.10170]];
 assert.deepEqual(decode(encodePolyline(pts)),pts);
 assert.equal(thinPath(Array.from({length:500},(_,i)=>[37+i/1e4,127])).length,160);
});
test('directions: vertexes become lat/lng points; failures give null',()=>{
 assert.deepEqual(pathFromDirections(directions([[37.1,127.1],[37.2,127.2]])),[[37.1,127.1],[37.2,127.2]]);
 assert.equal(pathFromDirections({routes:[{result_code:104,sections:[]}]}),null);
 assert.equal(pathFromDirections(null),null);
});
test('preview: via cafe, key header, cached until the car moves 300m, no key means no route',async()=>{
 let calls=[];const fetchImpl=async(url,options)=>{calls.push({url,options});return {ok:true,json:async()=>directions([[car.latitude,car.longitude],[cafe.latitude,cafe.longitude],[pickup.latitude,pickup.longitude]])};};
 const preview=createRoutePreview({kakaoKey:'k',fetchImpl});
 assert.equal(preview('a',car,cafe,pickup),null); // first call only starts the lookup
 await new Promise(r=>setTimeout(r,0));
 const route=preview('a',car,cafe,pickup);
 assert.equal(route.viaCafe,true);assert.equal(route.source,'kakao-directions');assert.equal(decode(route.polyline).length,3);
 assert.equal(calls.length,1);assert.equal(calls[0].url.searchParams.get('waypoints'),'127.11150,37.39470');assert.equal(calls[0].options.headers.Authorization,'KakaoAK k');
 preview('a',{latitude:car.latitude+0.001,longitude:car.longitude},cafe,pickup);assert.equal(calls.length,1); // ~110m: reuse
 preview('a',{latitude:car.latitude+0.01,longitude:car.longitude},cafe,pickup);
 await new Promise(r=>setTimeout(r,0));assert.equal(calls.length,2); // ~1.1km: refresh
 assert.equal(preview('a',car,null,pickup),null); // cafe passed -> different route, old one not reused
 assert.equal(createRoutePreview({fetchImpl})('b',car,cafe,pickup),null);
});
