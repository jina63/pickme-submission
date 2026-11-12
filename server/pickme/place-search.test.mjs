import {test} from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFile} from 'node:fs/promises';
const source=await readFile(new URL('../../web/friend/place-search.js',import.meta.url),'utf8');
const friendSource=await readFile(new URL('../../web/friend/friend.js',import.meta.url),'utf8');
const feature=(name='왕십리역',coordinates=[127.038,37.56])=>({geometry:{type:'Point',coordinates},properties:{name,city:'서울',street:'왕십리로'}});
function runtime(){const ctx=vm.createContext({URL,URLSearchParams,fetch,Map});vm.runInContext(source,ctx);return ctx.PickmePlaceSearch;}
test('place lookup validates and confines results, deduplicates and keeps plain labels',()=>{
 const api=runtime();const rows=api.normalize([feature(),feature(),feature('outside',[13,52]),feature('bad',[null,37]),feature('<img src=x>')]);
 assert.equal(rows.length,2);assert.equal(rows[1].label,'<img src=x>');assert.equal(rows[0].address,'서울 왕십리로');
});
test('place lookup uses explicit search only, caches and sends no invitation or location context',async()=>{
 const api=runtime();let calls=0;
 const fetchImpl=async(url,options)=>{calls++;assert.equal(url.origin,'https://photon.komoot.io');assert.equal(url.searchParams.get('q'),'왕십리역');assert.equal(url.searchParams.has('lat'),false);assert.equal(url.searchParams.has('token'),false);assert.equal(options.credentials,'omit');assert.equal(options.referrerPolicy,'origin');return {ok:true,json:async()=>({features:[feature()]})};};
 await assert.rejects(api.search('a',{fetchImpl}));assert.equal(calls,0);
 assert.equal((await api.search('왕십리역',{fetchImpl})).length,1);
 await api.search(' 왕십리역 ',{fetchImpl});assert.equal(calls,1);
 await assert.rejects(api.search('오류',{fetchImpl:async()=>({ok:false})}),/검색/);
});
function ui(search,proposal={kind:'none'}){
 const elements=new Map();
 function el(){return {hidden:false,disabled:false,value:'',children:[],listeners:{},attributes:{},classList:{toggle(){},remove(){},add(){}},dataset:{},addEventListener(type,fn){this.listeners[type]=fn;},setAttribute(k,v){this.attributes[k]=v;},replaceChildren(...nodes){this.children=nodes;},append(...nodes){this.children.push(...nodes);},querySelectorAll(){const out=[];const walk=n=>{for(const c of n.children){if(c.className==='pick')out.push(c);walk(c);}};walk(this);return out;},scrollIntoView(){}};}
 const document={body:el(),hidden:false,getElementById(id){if(!elements.has(id))elements.set(id,el());return elements.get(id);},createElement:el,addEventListener(){}};
 const map={on(){},invalidateSize(){},setView(){return this;},removeLayer(){}};
 const marker={on(){},setLatLng(){},dragging:{enable(){},disable(){}},addTo(){return this;}};
 const requests=[];
 const ctx=vm.createContext({document,window:{addEventListener(){},isSecureContext:true},navigator:{geolocation:{getCurrentPosition:ok=>ok({coords:{latitude:37.3950,longitude:127.1105,accuracy:25}})}},location:{hash:''},setTimeout,clearTimeout,AbortController,URL,URLSearchParams,console,crypto,fetch:async(url,options)=>{requests.push({url,body:JSON.parse(options?.body??'null')});return {ok:true,json:async()=>({requestId:'r',...proposal})};},matchMedia:()=>({matches:true}),PickmePlaceSearch:{search},L:{map:()=>map,control:{zoom:()=>({addTo(){}})},tileLayer:()=>({addTo(){return this;},on(){}}),marker:()=>marker,divIcon:()=>({}),circle:()=>({addTo(){return this;}})}});
 ctx.window.L=ctx.L;
 vm.runInContext(friendSource,ctx);vm.runInContext("active=true;token='a'.repeat(32);",ctx);
 return {ctx,e:id=>document.getElementById(id),requests};
}
test('search selection sets an unconfirmed pickup; moving pin discards old place name',async()=>{
 const {ctx,e}=ui(async()=>[{label:'왕십리역',address:'서울',latitude:37.56,longitude:127.038}]);
 e('place-query').value='왕십리역';await e('place-search-form').listeners.submit({preventDefault(){}});
 assert.equal(e('place-results').children.length,1);
 e('place-results').children[0].children[0].listeners.click();
 assert.equal(e('point-name').value,'왕십리역');assert.equal(e('manual-actions').hidden,false);
 assert.equal(vm.runInContext('active',ctx),true);assert.equal(vm.runInContext('selected.source',ctx),'manual');
 vm.runInContext('selectManual({lat:37.561,lng:127.039})',ctx);
 assert.equal(e('point-name').value,'');assert.equal(vm.runInContext('selected.label',ctx),'직접 선택한 탑승 장소');
});
test('searched place lists itself first, then up to three nearby picks; a pick becomes the recommended pickup',async()=>{
 const near=[{id:'demo-pangyo-station',label:'판교역 부근',latitude:37.394373,longitude:127.111152,verification:'demo',note:'시연용',distanceMeters:52},{id:'osm-node-1',label:'주차장',latitude:37.395,longitude:127.111,verification:'map-unverified',note:'지도상',distanceMeters:300}];
 const {ctx,e,requests}=ui(async()=>[{label:'판교역',address:'성남',latitude:37.3948,longitude:127.1112}],{kind:'candidate',candidate:near[0],candidates:near});
 e('place-query').value='판교역';await e('place-search-form').listeners.submit({preventDefault(){}});
 e('place-results').children[0].children[0].listeners.click();
 await new Promise(r=>setTimeout(r,0));
 // Only the searched coordinate is sent, marked as a search, never a device location.
 assert.deepEqual({...requests[0].body.location},{latitude:37.3948,longitude:127.1112,accuracy:0,source:'search'});
 const picks=e('nearby-list').children.map(li=>li.children[0]);
 assert.deepEqual(picks.map(b=>b.dataset.id),['origin','demo-pangyo-station','osm-node-1']);
 assert.equal(picks[0].attributes['aria-pressed'],'true');assert.equal(e('nearby-picks').hidden,false);
 assert.equal(picks[2].children[1].textContent,'걸어서 약 4분');
 picks[2].listeners.click();
 assert.equal(vm.runInContext('selected.source',ctx),'recommended');assert.equal(vm.runInContext('selected.candidateId',ctx),'osm-node-1');
 assert.equal(picks[2].attributes['aria-pressed'],'true');assert.equal(picks[0].attributes['aria-pressed'],'false');
 picks[0].listeners.click();
 assert.equal(vm.runInContext('selected.source',ctx),'manual');assert.equal(e('point-name').value,'판교역');
});
test('searched place with no nearby picks keeps the searched pin and says so',async()=>{
 const {e}=ui(async()=>[{label:'부산역',address:'부산',latitude:35.115,longitude:129.041}],{kind:'none'});
 e('place-query').value='부산역';await e('place-search-form').listeners.submit({preventDefault(){}});
 e('place-results').children[0].children[0].listeners.click();
 await new Promise(r=>setTimeout(r,0));
 assert.equal(e('nearby-list').children.length,1);assert.match(e('nearby-message').textContent,/500m 안에 추천할 곳이 없어요/);
 assert.equal(e('point-name').value,'부산역');
});
test('my location places the pin and lists nearby picks with my location first',async()=>{
 const near=[{id:'demo-pangyo-station',label:'판교역 부근',latitude:37.394373,longitude:127.111152,verification:'demo',note:'시연용',distanceMeters:80}];
 const {ctx,e,requests}=ui(async()=>[],{kind:'candidate',candidate:near[0],candidates:near});
 await e('locate').listeners.click();
 await new Promise(r=>setTimeout(r,0));
 assert.deepEqual({...requests[0].body.location},{latitude:37.395,longitude:127.1105,accuracy:25,source:'gps'});
 assert.equal(e('nearby-title').textContent,'내 위치 주변에서 타기 좋은 곳');
 const picks=e('nearby-list').children.map(li=>li.children[0]);
 assert.deepEqual(picks.map(b=>b.dataset.id),['origin','demo-pangyo-station']);
 assert.equal(picks[0].children[1].textContent,'내 위치');assert.equal(picks[0].attributes['aria-pressed'],'true');
 picks[1].listeners.click();
 assert.equal(vm.runInContext('selected.candidateId',ctx),'demo-pangyo-station');assert.equal(vm.runInContext('selected.locationSource',ctx),'gps');
 picks[0].listeners.click();
 // Keeping my location sends only the allowed pickup fields (no helper fields).
 assert.deepEqual(Object.keys(vm.runInContext('selected',ctx)).sort(),['label','latitude','locationSource','longitude','source']);
});
test('late search response is ignored after query changes or invite generation changes',async()=>{
 let resolve;const {ctx,e}=ui(()=>new Promise(r=>resolve=r));e('place-query').value='왕십리역';
 const pending=e('place-search-form').listeners.submit({preventDefault(){}});
 e('place-query').listeners.input();resolve([{label:'old',latitude:37.56,longitude:127.03}]);await pending;
 assert.equal(e('place-results').children.length,0);
 const pending2=e('place-search-form').listeners.submit({preventDefault(){}});vm.runInContext('generation++',ctx);
 resolve([{label:'old',latitude:37.56,longitude:127.03}]);await pending2;assert.equal(e('place-results').children.length,0);
});

test('friend detour shows cafe phases as plain text and drops stale or stopped information',async()=>{
 const {ctx,e}=ui(async()=>[]);
 vm.runInContext("active=false;token='a'.repeat(32);",ctx);
 let data={state:'on_way',sharingAllowed:true,updatedAt:Date.now(),expiresAt:Date.now()+60000,pickupEtaSeconds:720,cafeVisit:{name:'<img src=x>',phase:'before_pickup'}};
 ctx.fetch=async()=>({ok:true,json:async()=>data});ctx.AbortSignal=AbortSignal;
 const refresh=async()=>{await vm.runInContext('refreshLive()',ctx);vm.runInContext('clearTimeout(liveTimer)',ctx);};
 await refresh();assert.equal(e('cafe-visit').hidden,false);assert.equal(e('cafe-name').textContent,'<img src=x>');assert.match(e('live-eta').textContent,/12분/);assert.match(e('cafe-eta-note').textContent,/대기 시간/);
 for(const phase of ['at_cafe','passed']){data.cafeVisit.phase=phase;await refresh();assert.equal(e('cafe-visit').hidden,false);}
 data.updatedAt=Date.now()-21000;await refresh();assert.equal(e('cafe-visit').hidden,true);
 data.updatedAt=Date.now();await refresh();assert.equal(e('cafe-visit').hidden,false);
 delete data.cafeVisit;await refresh();assert.equal(e('cafe-visit').hidden,true);
 data.cafeVisit={name:'카페',phase:'before_pickup'};data.state='paused';await refresh();assert.equal(e('cafe-visit').hidden,true);
 data.state='on_way';await refresh();assert.equal(e('cafe-visit').hidden,false);
 ctx.fetch=async()=>{throw Error('offline');};await refresh();assert.equal(e('cafe-visit').hidden,true);
});

test('map uses side-view car SVG, fits cafe with pickup and removes cafe marker on stop',async()=>{
 const {ctx,e}=ui(async()=>[]);const markers=[],removed=[];let bounds;
 ctx.L.marker=(latlng,options)=>{const marker={latlng,options,addTo(){markers.push(this);return this;},setLatLng(value){this.latlng=value;return this;},setOpacity(){return this;},getLatLng(){return this.latlng;}};return marker;};
 ctx.L.divIcon=options=>options;ctx.L.latLngBounds=points=>{bounds=points;return {pad(){return this;}};};
 ctx.fixtureMap={removeLayer(marker){removed.push(marker);},fitBounds(){}};
 vm.runInContext("map=fixtureMap;marker={getLatLng:()=>[37.55715,127.04375]};active=false;token='a'.repeat(32);",ctx);
 let data={state:'on_way',sharingAllowed:true,updatedAt:Date.now(),expiresAt:Date.now()+60000,pickupEtaSeconds:720,position:{latitude:37.56,longitude:127.04},cafeVisit:{name:'카페',phase:'before_pickup',position:{latitude:37.559,longitude:127.041}}};
 ctx.fetch=async()=>({ok:true,json:async()=>data});ctx.AbortSignal=AbortSignal;
 const refresh=async()=>{await vm.runInContext('refreshLive()',ctx);vm.runInContext('clearTimeout(liveTimer)',ctx);};
 await refresh();assert.equal(markers.length,2);assert.equal(bounds.length,3);
 const car=markers.find(m=>m.options.title==='운전자 차량 위치');assert.match(car.options.icon.html,/<svg/);assert.match(car.options.icon.html,/viewBox="0 0 64 40"/);
 const cafe=markers.find(m=>m.options.title==='경유 카페');assert.deepEqual(Array.from(cafe.latlng),[37.559,127.041]);
 data.cafeVisit.phase='passed';await refresh();assert.ok(removed.includes(cafe));assert.equal(e('cafe-visit').hidden,false);
 data.state='paused';await refresh();assert.ok(removed.includes(car));assert.equal(e('cafe-visit').hidden,true);
});
