let token = location.hash.slice(1);
const $ = id => document.getElementById(id);
const campus = {latitude:37.55715,longitude:127.04375};
let generation=0, sending=false, locating=false, map, marker, gpsLayer, selected=null, locationSource='manual', manualMode=false, active=false;
const cloud = window.PICKME_FIREBASE_DATABASE_URL;
let cafeMarker=null, cafeMarkerKey="";
let liveTimer=null, vehicleMarker=null, liveSeen=false, firstVehicle=true, arrivalNotified=false, driverName='운전자';
const sleep = ms => new Promise(resolve=>setTimeout(resolve,ms));
function endpoint(key) { return `${cloud}/pickmeInvites/${key}.json`; }
async function readInvite(key=token) {
  const response=await fetch(cloud?endpoint(key):'/api/friend/invitation',{cache:'no-store',headers:cloud?{}:{Authorization:`Bearer ${key}`}});
  const data=await response.json();
  if (!response.ok || !data || (cloud && Date.now()>=data.expiresAt)) throw new Error(data?.error || '초대가 없거나 만료됐어요. 새 링크를 요청해 주세요.');
  return data;
}
async function patchCloud(key, body) {
  const response=await fetch(endpoint(key),{method:'PATCH',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
  if (!response.ok) throw new Error('저장하지 못했어요. 초대가 만료됐거나 이미 응답했을 수 있어요.');
}
function pointText(point) {
  $('point-label').textContent=point.label;

}
function selectManual(latlng) {
  if (!manualMode || !active || sending) return;
  selected={latitude:latlng.lat,longitude:latlng.lng,label:'직접 선택한 탑승 장소',source:'manual',locationSource};
  $('point-name').value=''; marker.setLatLng(latlng); pointText(selected);
  if (nearbyPicks) markNearby();
}
function showMap(point, editable) {
  $('map-step').hidden=false;document.body.classList.add('has-map');
  if (!window.L) throw new Error('지도를 불러오지 못했어요. 새로고침해 주세요.');
  if (!map) {
    map=L.map('map',{zoomControl:false}).setView([point.latitude,point.longitude],17);
    L.control.zoom({position:'bottomright'}).addTo(map);
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'}).addTo(map).on('tileerror',()=>{ $('map-help').textContent='지도 배경을 불러오지 못했어요. 연결을 확인하고 다시 시도해 주세요.'; });
    marker=L.marker([point.latitude,point.longitude],{draggable:true,autoPan:true,icon:L.divIcon({className:'pickup-marker',html:'<span class="pickup-pin-label">여기서 만나요</span><span class="pickup-pin-dot"></span>',iconSize:[112,68],iconAnchor:[56,60]}),title:'탑승 장소 핀'}).addTo(map);
    marker.on('dragend',()=>selectManual(marker.getLatLng()));
    map.on('click',event=>selectManual(event.latlng));
    map.on('keydown',event=>{if(event.originalEvent.key==='Enter') selectManual(map.getCenter());});
  }
  map.invalidateSize(); map.setView([point.latitude,point.longitude],17);
  marker.setLatLng([point.latitude,point.longitude]);
  manualMode=editable;
  if(editable) marker.dragging.enable(); else marker.dragging.disable();
  $('map-help').textContent=editable?'핀을 움직여 만날 입구에 맞춰 주세요.':'승하차가 가능한 곳인지 확인해 주세요.';
  $('recommend-actions').hidden=editable || !active;
  $('manual-actions').hidden=!editable || !active;
  pointText(point);
}
function startManual() {
  if (sending || !active) return;
  generation++; locating=false; setBusy(false); clearNearby();
  $('location-step').hidden=false; $('step-label').textContent='지도에서 핀을 조정하고 탑승 장소를 확정해 주세요'; $('retry').hidden=false;
  const previous=selected ?? {...campus,label:'직접 선택한 탑승 장소'};
  selected={latitude:previous.latitude,longitude:previous.longitude,label:'직접 선택한 탑승 장소',source:'manual',locationSource};
  $('point-name').value='';
  showMap(selected,true);
  $('location-message').textContent=locationSource==='manual'?'현재 위치 없이 한양대 부근에서 시작해요. 원하는 곳으로 핀을 옮겨 주세요.':'추천 장소 대신 원하는 탑승 장소를 선택해 주세요.';
}
// Nearby boarding picks (≤3, nearest first). `origin` is the searched place, offered as the first option.
let nearbyPicks=null;
function walkText(meters) { return `걸어서 약 ${Math.max(1,Math.round(meters/67))}분`; }
function clearNearby() { nearbyPicks=null;$('nearby-picks').hidden=true;$('nearby-list').replaceChildren();$('nearby-message').textContent=''; }
function markNearby() {
  const o=nearbyPicks?.origin,atOrigin=o && selected?.source==='manual' && selected.latitude===o.latitude && selected.longitude===o.longitude;
  for (const b of $('nearby-list').querySelectorAll('.pick')) b.setAttribute('aria-pressed',String(b.dataset.id===(selected?.candidateId ?? (atOrigin?'origin':''))));
}
function chooseRecommended(c) {
  if (!active || sending) return;
  selected={latitude:c.latitude,longitude:c.longitude,label:c.label,source:'recommended',candidateId:c.id,locationSource};
  showMap(selected,false);markNearby();
  $('location-message').textContent='';
}
function chooseOrigin(origin) {
  if (!active || sending) return;
  selected={...origin};$('point-name').value=origin.label;showMap(selected,true);markNearby();
  $('location-message').textContent='';
}
function pickButton(id,title,walk,kind,onClick) {
  const li=document.createElement('li'),button=document.createElement('button');
  button.type='button';button.className='pick';button.dataset.id=id;button.setAttribute('aria-pressed','false');
  const t=document.createElement('strong'),w=document.createElement('span'),k=document.createElement('span');
  t.textContent=title;w.className='pick-walk';w.textContent=walk;k.className='pick-kind';k.textContent=kind;
  button.append(t,w,k);button.addEventListener('click',onClick);li.append(button);return li;
}
// kind: 'search' (a searched place) or 'gps' (the device location). The origin is offered first so the friend can keep it.
const nearbyWords={search:{title:'검색한 곳 주변에서 타기 좋은 곳',tag:'검색한 곳',place:'검색한 곳'},gps:{title:'내 위치 주변에서 타기 좋은 곳',tag:'내 위치',place:'내 위치'}};
function renderNearby(candidates, origin=null, kind='search') {
  nearbyPicks={candidates,origin,kind};
  const words=nearbyWords[kind];
  $('nearby-picks').hidden=false;$('nearby-message').textContent='';
  $('nearby-title').textContent=origin?words.title:'가까운 탑승 후보';
  const items=[];
  if (origin) items.push(pickButton('origin',origin.label,words.tag,'핀을 직접 맞춰요',()=>chooseOrigin(origin)));
  for (const c of candidates) items.push(pickButton(c.id,c.label,walkText(c.distanceMeters),c.verification==='demo'?'팀이 고른 승하차 장소':c.verification==='kakao'?'카카오맵 주차장 · 승하차 확인 필요':'지도상 주차장 · 승하차 확인 필요',()=>chooseRecommended(c)));
  $('nearby-list').replaceChildren(...items);markNearby();
}
async function nearbyFor(origin, location, kind) {
  const run=generation,key=token,id=crypto.randomUUID().replaceAll('-','');
  renderNearby([],origin,kind);$('nearby-message').textContent='주변 탑승 후보를 찾고 있어요…';
  let proposal;
  try { proposal=await recommendation(location,key,id); }
  catch { proposal={kind:'unavailable'}; }
  if (run!==generation || key!==token || !active || nearbyPicks?.origin!==origin) return;
  if (proposal.kind==='candidate') { renderNearby(proposal.candidates ?? [proposal.candidate],origin,kind);return; }
  const place=nearbyWords[kind].place;
  $('nearby-message').textContent=proposal.kind==='unavailable'?`주변 후보를 불러오지 못했어요. ${place}에서 핀을 맞춰 주세요.`:`500m 안에 추천할 곳이 없어요. ${place}에서 핀을 맞춰 주세요.`;
}
function setBusy(value) { for (const id of ['locate','actual-position','manual','yes','change','confirm','retry']) $(id).disabled=value; }
async function recommendation(location,key,requestId) {
  if (!cloud) {
    const response=await fetch('/api/friend/recommendation',{method:'POST',headers:{Authorization:`Bearer ${key}`,'Content-Type':'application/json'},body:JSON.stringify({location,requestId})});
    const data=await response.json();if(!response.ok) throw new Error(data.error);return data;
  }
  await patchCloud(key,{locationRequest:{requestId,location},proposal:null});
  for (let i=0;i<35;i++) {
    const data=await readInvite(key);
    if (data.status!=='pending') throw new Error('이미 확정된 초대예요. 새로고침해 주세요.');
    if (data.proposal?.requestId===requestId) return data.proposal;
    await sleep(1000);
  }
  throw new Error('추천 서버 응답이 늦어요. 운전자 컴퓨터의 서버를 확인하거나 지도에서 직접 선택해 주세요.');
}
async function useLocation(location,run) {
  const key=token;
  locationSource=location.source;
  const id=crypto.randomUUID().replaceAll('-','');
  $('location-message').textContent=location.source==='demo'?'한양대 가상 위치로 추천 중이에요. 실제 GPS 위치가 아니에요.':`위치 정확도 약 ${Math.round(location.accuracy)}m · 가까운 후보를 찾고 있어요.`;
  const proposal=await recommendation(location,key,id);
  if (run!==generation || key!==token) return;
  selected=null;
  if (proposal.kind==='candidate') {
    const list=proposal.candidates ?? [proposal.candidate];
    $('location-step').hidden=false; $('step-label').textContent='추천 장소를 확인하거나 핀을 조정해 주세요';
    chooseRecommended(list[0]);renderNearby(list);
    $('location-message').textContent='';
  } else {
    selected={...location,label:'직접 선택한 탑승 장소',source:'manual',locationSource};
    startManual();
    $('location-message').textContent=proposal.kind==='unavailable'?'주변 추천을 불러오지 못했어요. 지도에서 직접 골라 주세요.':'500m 안에 추천할 곳이 없어요. 지도에서 직접 골라 주세요.';
  }
  if (gpsLayer) { map.removeLayer(gpsLayer);gpsLayer=null; }
  if (location.source==='gps') gpsLayer=L.circle([location.latitude,location.longitude],{radius:Math.min(location.accuracy,1000),color:'#5681c9',fillOpacity:0.08}).addTo(map);
  $('retry').hidden=false;
}
async function locate(demo=false, recommendNearby=false) {
  if (sending || locating || !active) return;
  const run=++generation;
  locating=true;setBusy(true);$('location-message').textContent='위치를 확인하고 있어요…';
  try {
    const location=demo?{latitude:campus.latitude+0.001,longitude:campus.longitude,accuracy:0,source:'demo'}:await new Promise((resolve,reject)=>{
      if(!navigator.geolocation || !window.isSecureContext) return reject(new Error('위치 확인을 지원하지 않는 환경이에요. HTTPS에서 열거나 지도에서 직접 선택해 주세요.'));
      navigator.geolocation.getCurrentPosition(p=>resolve({latitude:p.coords.latitude,longitude:p.coords.longitude,accuracy:p.coords.accuracy,source:'gps'}),e=>reject(new Error(e.code===1?'위치 권한이 꺼져 있어요. 권한을 허용하거나 지도에서 직접 선택해 주세요.':e.code===3?'위치 확인 시간이 초과됐어요. 다시 시도하거나 지도에서 선택해 주세요.':'현재 위치를 확인하지 못했어요. 지도에서 직접 선택할 수 있어요.')),{enableHighAccuracy:true,timeout:15000,maximumAge:0});
    });
    if(run!==generation)return;
    if (demo || recommendNearby) {
      await useLocation(location,run);
    } else {
      // Use only the successful device fix; never substitute the campus/demo coordinate.
      locationSource='gps';
      selected={latitude:location.latitude,longitude:location.longitude,label:'내 현재 위치',source:'manual',locationSource:'gps'};
      startManual();
      selected.label='내 현재 위치';
      $('point-name').value=selected.label;
      pointText(selected);
      if(gpsLayer) map.removeLayer(gpsLayer);
      gpsLayer=L.circle([location.latitude,location.longitude],{radius:location.accuracy,color:'#5681c9',fillOpacity:0.08}).addTo(map);
      $('location-message').textContent='';
      const origin={...selected};
      nearbyFor(origin,{latitude:location.latitude,longitude:location.longitude,accuracy:Math.min(location.accuracy,100000),source:'gps'},'gps');
    }
  } catch(e) { if(run===generation) $('location-message').textContent=e.message || '위치를 확인하지 못했어요.'; }
  finally { if(run===generation) {locating=false;setBusy(false);} }
}
function render(data) {
  $('details').hidden=false;driverName=data.driverName || '운전자';
  document.body.dataset.stage=data.status;
  $('journey').textContent=data.journey?.destinationLabel ? data.journey.destinationLabel : '차량 목적지가 연결되지 않았어요';
  active=data.status==='pending';
  $('page-title').textContent=active?'어디서 만날까요?':data.status==='accepted'?'수락을 기다리고 있어요':'초대가 종료됐어요';
  $('page-intro').textContent=active?`${driverName}님이 초대했어요.`:`${driverName}님에게 탑승 장소를 보냈어요.`;
  $('step-label').textContent=active?'탑승 장소를 선택해 주세요':'운전자 수락 및 도착 정보';
  $('status').dataset.status=data.status;
  $('step-select').classList.toggle('is-active',active);
  $('step-request').classList.toggle('is-active',data.status==='accepted');
  $('step-meet').classList.remove('is-active');
  $('live-card').hidden=data.status!=='accepted';
  $('ready-card').hidden=data.status!=='accepted';
  if(data.status==='accepted')refreshReady();
  $('actual-position').hidden=!active;
  $('status').textContent={pending:'픽업 초대',accepted:'요청 완료',declined:'종료된 초대'}[data.status] || '초대 확인';
  $('message').textContent=data.status==='declined'?'새 초대 링크를 요청해 주세요.':'';
  $('location-step').hidden=!active;
  if(!active) {
    $('recommend-actions').hidden=$('manual-actions').hidden=$('retry').hidden=true;clearNearby();
    if(data.status==='accepted')scheduleLive(0);
    if(data.pickupPoint) {selected=data.pickupPoint;locationSource=selected.locationSource;showMap(selected,false);$('recommend-actions').hidden=true;$('map-help').textContent='';}
  }
}
async function confirm() {
  if(sending || !selected || !active)return;
  const run=generation,key=token;
  const point={...selected};
  if(manualMode) point.label=$('point-name').value.trim() || '직접 선택한 탑승 장소';
  sending=true;setBusy(true);if(marker)marker.dragging.disable();
  $('submit-message').textContent='탑승 장소를 보내고 있어요…';
  try {
    let data;
    if(cloud) {
      const current=await readInvite(key);
      if(current.status==='pending') await patchCloud(key,{status:'accepted',pickupPoint:point,respondedAt:{'.sv':'timestamp'},locationRequest:null,proposal:null});
      data=await readInvite(key);
      if(data.status!=='accepted')throw new Error('이미 종료된 초대예요. 새 링크를 요청해 주세요.');
    } else {
      const response=await fetch('/api/friend/invitation',{method:'POST',headers:{Authorization:`Bearer ${key}`,'Content-Type':'application/json'},body:JSON.stringify({status:'accepted',pickupPoint:point})});
      data=await response.json();if(!response.ok)throw new Error(data.error);
    }
    if(run!==generation || key!==token)return;
    if(gpsLayer){map.removeLayer(gpsLayer);gpsLayer=null;}
    $('location-message').textContent='';$('submit-message').textContent='';render(data);
  } catch(e) { if(run===generation)$('submit-message').textContent=e.message || '전송하지 못했어요. 다시 시도해 주세요.'; }
  finally {sending=false;if(run===generation){setBusy(false);if(manualMode && active)marker.dragging.enable();}}
}
async function loadInvitation() {
  showCafe(null);
  clearTimeout(liveTimer);liveTimer=null;vehicleMarker=null;liveSeen=false;firstVehicle=true;arrivalNotified=false;
  cancelPlaceSearch();clearNearby();
  const run=++generation;token=location.hash.slice(1);active=false;selected=null;locating=false;locationSource='manual';manualMode=false;
  if(map){map.remove();map=marker=gpsLayer=null;}
  routeLine=null;routeKey='';
  document.body.classList.remove('has-map');
  $('details').hidden=$('location-step').hidden=$('map-step').hidden=$('retry').hidden=$('live-card').hidden=$('ready-card').hidden=true;
  $('ready-button').disabled=false;
  $('ready-message').textContent='탑승 장소에 도착하면 눌러 주세요.';
  $('location-message').textContent=$('submit-message').textContent='';$('status').textContent='확인 중';setBusy(false);
  if(!/^[A-Za-z0-9_-]{32}$/.test(token)) {$('status').textContent='링크 확인 필요';$('message').textContent='운전자에게 받은 전체 초대 링크를 열어 주세요.';return;}
  try {const data=await readInvite();if(run===generation)render(data);}catch(e){if(run===generation){$('status').textContent='초대 확인 불가';$('message').textContent=e.message;}}
}
let placeSearchId=0,placeSearchController=null;
function cancelPlaceSearch(){
  placeSearchId++;placeSearchController?.abort();placeSearchController=null;
  $('place-results').replaceChildren();$('place-search-message').textContent='';
  $('place-search-button').disabled=false;
}
$('place-query').addEventListener('input',cancelPlaceSearch);
$('place-search-form').addEventListener('submit',async event=>{
  event.preventDefault();if(!active || sending)return;
  cancelPlaceSearch();
  const id=placeSearchId,run=generation,key=token,query=$('place-query').value.trim();
  const controller=new AbortController();placeSearchController=controller;
  const timeout=setTimeout(()=>controller.abort(),12000);
  $('place-search-button').disabled=true;$('place-search-message').textContent='장소를 찾고 있어요…';
  try{
    const points=await PickmePlaceSearch.search(query,{signal:controller.signal});
    if(id!==placeSearchId || run!==generation || key!==token || !active)return;
    $('place-search-message').textContent=points.length?'':'검색 결과가 없어요. 지역명과 장소명을 함께 입력하거나 지도에서 직접 골라 주세요.';
    for(const point of points){
      const li=document.createElement('li'),button=document.createElement('button');
      button.type='button';button.className='place-result';
      const title=document.createElement('strong'),address=document.createElement('span');
      title.textContent=point.label;address.textContent=point.address || '지도에서 위치를 확인해 주세요.';
      button.append(title,address);li.append(button);$('place-results').append(li);
      button.addEventListener('click',()=>{
        if(!active || sending || id!==placeSearchId || run!==generation || key!==token)return;
        const chosen={latitude:point.latitude,longitude:point.longitude,label:point.label,source:'manual',locationSource:'manual'};
        cancelPlaceSearch();generation++;locating=false;locationSource='manual';setBusy(false);
        selected=chosen;$('point-name').value=chosen.label;showMap(chosen,true);
        if(gpsLayer){map.removeLayer(gpsLayer);gpsLayer=null;}
        $('retry').hidden=false;$('location-message').textContent='';
        nearbyFor(chosen,{latitude:chosen.latitude,longitude:chosen.longitude,accuracy:0,source:'search'},'search');
        $('map-step').scrollIntoView({behavior:matchMedia('(prefers-reduced-motion: reduce)').matches?'instant':'smooth',block:'start'});
      });
    }
  }catch(error){
    if(id===placeSearchId && run===generation && key===token && active)$('place-search-message').textContent=error.name==='AbortError'?'검색 시간이 길어지고 있어요. 다시 검색하거나 지도에서 직접 골라 주세요.':error.message;
  }finally{clearTimeout(timeout);if(id===placeSearchId)$('place-search-button').disabled=false;}
});

$('locate').addEventListener('click',()=>locate());
$('actual-position').addEventListener('click',()=>locate());
$('manual').addEventListener('click',startManual);$('change').addEventListener('click',startManual);
$('yes').addEventListener('click',confirm);$('confirm').addEventListener('click',confirm);
$('retry').addEventListener('click',()=>{cancelPlaceSearch();clearNearby();generation++;locating=false;selected=null;manualMode=false;locationSource='manual';$('location-step').hidden=false;$('map-step').hidden=true;document.body.classList.remove('has-map');$('retry').hidden=true;$('location-message').textContent='';$('submit-message').textContent='';setBusy(false);});
function scheduleLive(delay=3000) {
  clearTimeout(liveTimer);
  if(!document.hidden)liveTimer=setTimeout(refreshLive,delay);
}
function showCafe(cafe,hasEta=false) {
  const valid=cafe && typeof cafe.name==='string' && cafe.name.trim() && ['before_pickup','at_cafe','passed'].includes(cafe.phase);
  $('cafe-visit').hidden=!valid;
  $('cafe-name').textContent=valid?cafe.name:'';
  $('cafe-state').textContent=valid?({before_pickup:'카페에 들렀다가 데리러 가고 있어요.',at_cafe:'차량이 카페 경유지에 도착했어요.',passed:'카페 경유지를 지나 탑승 장소로 오고 있어요.'}[cafe.phase]):'';
  const p=valid && cafe.phase!=='passed'?cafe.position:null;
  const key=p && Number.isFinite(p.latitude) && Number.isFinite(p.longitude) && Math.abs(p.latitude)<=85 && Math.abs(p.longitude)<=180 && (p.latitude!==0 || p.longitude!==0)?`${cafe.name}:${p.latitude}:${p.longitude}`:'';
  if(key!==cafeMarkerKey || !key) {
    if(cafeMarker && map)map.removeLayer(cafeMarker);
    cafeMarker=null;cafeMarkerKey=key;
    if(key && map) {
      cafeMarker=L.marker([p.latitude,p.longitude],{icon:L.divIcon({className:'cafe-marker',html:CAFE_ICON,iconSize:[44,44],iconAnchor:[22,43]}),title:'경유 카페'}).addTo(map);
      fitLive();
    }
  }
  $('cafe-eta-note').textContent=valid?'매장 대기 시간은 포함되지 않아요.':'';
}
function clearVehicle() {
  showCafe(null);showRoute(null);
  if(vehicleMarker && map)map.removeLayer(vehicleMarker);
  vehicleMarker=null;$('fit-live').hidden=true;showArrival(null);
}
// Only fresh Pleos waypoint ETA drives this; stale/paused paths pass null so an old ETA never claims "arriving".
const VEHICLE_ICON='<svg viewBox="0 0 64 40" width="64" height="40" aria-hidden="true" focusable="false"><ellipse cx="32" cy="35" rx="28" ry="3" fill="#142c4526"/><path d="M5 18 L14 16 L21 7 Q23 5 28 5 H38 Q41 5 44 9 L50 17 L57 19 Q61 20 61 25 V29 Q61 32 57 32 H7 Q3 32 3 28 V22 Q3 19 5 18Z" fill="#287dec" stroke="white" stroke-width="2"/><path d="M17 16 L23 9 H29 V16Z M32 9 H37 Q39 9 41 12 L45 16 H32Z" fill="#dceeff"/><path d="M6 24 H11 M55 23 H59" stroke="#fff2b1" stroke-width="3" stroke-linecap="round"/><path d="M25 21 H29 M37 21 H41" stroke="#bcdcff" stroke-width="2" stroke-linecap="round"/><circle cx="16" cy="31" r="6" fill="#24364c" stroke="white" stroke-width="2"/><circle cx="49" cy="31" r="6" fill="#24364c" stroke="white" stroke-width="2"/><circle cx="16" cy="31" r="2" fill="#c7d4e4"/><circle cx="49" cy="31" r="2" fill="#c7d4e4"/></svg>';
const ARRIVING_SECONDS=180;
const CAFE_ICON='<svg viewBox="0 0 56 56" width="44" height="44" aria-hidden="true" focusable="false"><path d="M28 54l-6-8H11a7 7 0 0 1-7-7V11a7 7 0 0 1 7-7h34a7 7 0 0 1 7 7v28a7 7 0 0 1-7 7H34z" fill="#2f6fe0" stroke="#fff" stroke-width="2.5"/><path d="M11 16l2-6h30l2 6z" fill="#fff"/><path d="M11 16c1.4 2 2.8 2 4.2 0s2.8-2 4.2 0 2.8 2 4.2 0 2.8-2 4.2 0 2.8 2 4.2 0 2.8-2 4.2 0 2.8 2 4.2 0 2.8-2 3.4 0" fill="none" stroke="#fff" stroke-width="1.6"/><rect x="12" y="18" width="32" height="22" rx="2" fill="#fff"/><path d="M19 25h18v7a6 6 0 0 1-6 6h-6a6 6 0 0 1-6-6z" fill="#fff" stroke="#6b4220" stroke-width="1.6"/><path d="M37 27h2a2.5 2.5 0 0 1 0 5h-2" fill="none" stroke="#6b4220" stroke-width="1.6"/><ellipse cx="28" cy="25.5" rx="8" ry="2.4" fill="#a8743f"/><path d="M28 27c-2.3-1.3-2.8-2.5-1.5-2.9.8-.2 1.3.3 1.5.8.2-.5.7-1 1.5-.8 1.3.4.8 1.6-1.5 2.9z" fill="#fff"/></svg>';
// 예상 도로 경로(카카오 길찾기). Pleos 안내 경로와 다를 수 있어 '예상 경로'로만 안내한다.
let routeLine=null,routeKey='';
function decodePolyline(text){const pts=[];let i=0,lat=0,lng=0;while(i<text.length){for(const axis of [0,1]){let shift=0,result=0,b;do{b=text.charCodeAt(i++)-63;result|=(b&0x1f)<<shift;shift+=5;}while(b>=0x20&&i<=text.length);const d=(result&1)?~(result>>1):(result>>1);if(axis===0)lat+=d;else lng+=d;}pts.push([lat/1e5,lng/1e5]);}return pts;}
function showRoute(route){
  const ok=route && typeof route.polyline==='string' && route.polyline.length>0 && route.polyline.length<=4000;
  const key=ok?route.polyline:'';
  $('route-note').hidden=!ok;
  if(ok)$('route-note').textContent='파란 선은 예상 경로예요. 실제 내비 경로와 다를 수 있어요.';
  if(key===routeKey)return;
  if(routeLine && map)map.removeLayer(routeLine);
  routeLine=null;routeKey='';
  if(!ok || !map)return; // 지도가 아직 없으면 다음 갱신 때 다시 그린다
  const pts=decodePolyline(route.polyline).filter(([a,b])=>Number.isFinite(a)&&Number.isFinite(b)&&Math.abs(a)<=85&&Math.abs(b)<=180);
  if(pts.length<2)return;
  routeLine=L.polyline(pts,{color:'#3275ed',weight:5,opacity:0.8,lineJoin:'round',interactive:false}).addTo(map);
  routeLine.bringToBack();routeKey=key;
}
function showArrival(etaSeconds) {
  const soon=Number.isInteger(etaSeconds) && etaSeconds>0 && etaSeconds<=ARRIVING_SECONDS;
  $('live-arrival').hidden=!soon;
  if(!soon){arrivalNotified=false;return;}
  $('live-arrival').textContent=etaSeconds<=60?'거의 도착했어요! 탑승 장소에서 차량을 확인해 주세요.':'곧 도착해요! 탑승 장소로 나와 주세요.';
  if(!arrivalNotified){arrivalNotified=true;try{navigator.vibrate?.(200);}catch{}}
}
async function refreshLive() {
  if(active || document.hidden || !/^[A-Za-z0-9_-]{32}$/.test(token))return;
  const key=token,run=generation;
  try {
    const response=await fetch(cloud?`${cloud}/pickmeLive/${key}/public.json`:'/api/friend/live',{cache:'no-store',headers:cloud?{}:{Authorization:`Bearer ${key}`},signal:AbortSignal.timeout(8000)});
    const data=response.ok?await response.json():null;
    if(run!==generation || key!==token)return;
    if(!data) {
      $('page-title').textContent=liveSeen?'위치 공유가 끝났어요':'수락을 기다리고 있어요';
      $('status').textContent=liveSeen?'공유 종료':'요청 완료';$('live-updated').textContent='';
      $('live-state').textContent=liveSeen?'차량 정보 연결이 끊겼거나 공유가 끝났어요.':'운전자 수락을 기다려요.';
      setEta('픽업 장소까지 예상 시간은 아직 확인되지 않았어요.');clearVehicle();return;
    }
    liveSeen=true;
    const age=Date.now()-data.updatedAt,stale=age>20000 || age < -10000 || Date.now()>=data.expiresAt;
    $('live-updated').textContent='';
    if(data.destinationLabel)$('journey').textContent=data.destinationLabel;
    if(data.state==='paused') {
      $('page-title').textContent='위치 공유가 끝났어요';$('status').textContent='공유 중지';$('live-updated').textContent='';
      $('live-state').textContent='운전자가 위치 공유를 중지했어요.';setEta('차량 위치와 예상 시간을 더 이상 공유하지 않아요.');clearVehicle();return;
    }
    if(stale) {
      $('page-title').textContent='차량 정보를 기다려요';$('status').textContent='갱신 지연';$('live-updated').textContent='갱신이 잠시 멈췄어요';
      $('live-state').textContent='새 차량 정보를 기다리고 있어요. 마지막 위치는 현재 위치와 다를 수 있어요.';
      showCafe(null);showRoute(null);
      setEta('연결이 회복되면 픽업 예상 시간을 다시 확인해요.');showArrival(null);
      if(vehicleMarker)vehicleMarker.setOpacity(0.35);return;
    }
    $('page-title').textContent=data.state==='on_way'?'차량이 오고 있어요':'픽업이 수락됐어요';
    $('page-intro').textContent=`${driverName}님과 여기서 만나요.`;
    $('status').textContent='픽업 진행 중';
    $('live-state').textContent=data.state==='on_way'?'탑승 장소까지 예상 시간':data.sharingAllowed?'차량 위치를 연결하고 있어요':'운전자가 위치를 공유하지 않고 있어요';
    if(!data.sharingAllowed){setEta('운전자가 공유를 시작하면 차량 위치와 픽업 예상 시간을 확인할 수 있어요.');clearVehicle();return;}
    const hasEta=Number.isInteger(data.pickupEtaSeconds)&&data.pickupEtaSeconds>0;
    setEta(hasEta?`약 ${Math.max(1,Math.ceil(data.pickupEtaSeconds/60))}분 뒤 도착`:'픽업 예상 시간을 확인하고 있어요.',hasEta);
    showCafe(data.state==='on_way'?data.cafeVisit:null,hasEta);
    showRoute(data.state==='on_way'?data.routePreview:null);
    showArrival(data.state==='on_way'?data.pickupEtaSeconds:null);
    if(data.position && map && Number.isFinite(data.position.latitude) && Number.isFinite(data.position.longitude)) {
      const latlng=[data.position.latitude,data.position.longitude];
      if(!vehicleMarker)vehicleMarker=L.marker(latlng,{icon:L.divIcon({className:'vehicle-marker',html:VEHICLE_ICON,iconSize:[64,40],iconAnchor:[32,20]}),title:'운전자 차량 위치'}).addTo(map);
      else vehicleMarker.setLatLng(latlng).setOpacity(1);
      $('fit-live').hidden=false;
      if(firstVehicle){firstVehicle=false;fitLive();}
    }
  } catch {
    if(run===generation){$('page-title').textContent='차량 정보를 기다려요';$('status').textContent='연결 확인 중';$('live-updated').textContent='연결 확인 중';$('live-state').textContent='차량 정보에 연결하지 못했어요. 다시 연결 중이에요.';setEta('현재 픽업 예상 시간을 확인할 수 없어요.');showCafe(null);showArrival(null);if(vehicleMarker)vehicleMarker.setOpacity(0.35);}
  } finally {if(run===generation && key===token)scheduleLive();}
}
function fitLive(){if(map && marker && vehicleMarker)map.fitBounds(L.latLngBounds([marker.getLatLng(),vehicleMarker.getLatLng(),...(cafeMarker?[cafeMarker.getLatLng()]:[])]).pad(0.3),{maxZoom:17});}
$('fit-live').addEventListener('click',fitLive);
document.addEventListener('visibilitychange',()=>{clearTimeout(liveTimer);if(!document.hidden && !active)scheduleLive(0);});
window.addEventListener('pagehide',()=>clearTimeout(liveTimer));
window.addEventListener('hashchange',loadInvitation);loadInvitation();

function setEta(text,available=false){$('live-eta').textContent=text;$('live-eta').dataset.available=String(available);}
let readyBusy=false;
async function refreshReady() {
  const run=generation,key=token;
  try {
    const response=await fetch(cloud?`${cloud}/pickmeReady/${key}.json`:'/api/friend/ready',{cache:'no-store',headers:cloud?{}:{Authorization:`Bearer ${key}`},signal:AbortSignal.timeout(8000)});
    if(!response.ok)throw Error();
    const data=await response.json();
    if(run!==generation)return false;
    if(data?.readyAt) {
      $('ready-button').disabled=true;
      $('step-request').classList.remove('is-active');$('step-meet').classList.add('is-active');
      $('ready-message').textContent='운전자에게 준비 완료를 알렸어요.';
      return true;
    }
    return false;
  } catch {return false;}
}
$('ready-button').addEventListener('click',async()=>{
  if(readyBusy)return;
  readyBusy=true;$('ready-button').disabled=true;
  const run=generation,key=token;
  try {
    if(await refreshReady())return;
    const response=await fetch(cloud?`${cloud}/pickmeReady/${key}.json`:'/api/friend/ready',{method:cloud?'PUT':'POST',headers:{'Content-Type':'application/json',...(cloud?{}:{Authorization:`Bearer ${key}`})},body:JSON.stringify(cloud?{readyAt:{'.sv':'timestamp'}}:{}),signal:AbortSignal.timeout(10000)});
    if(!response.ok)throw Error();
    if(run===generation){$('step-request').classList.remove('is-active');$('step-meet').classList.add('is-active');$('ready-message').textContent='운전자에게 준비 완료를 알렸어요.';}
  } catch {
    if(run===generation && !await refreshReady()){$('ready-message').textContent='준비 상태를 저장하지 못했어요. 연결을 확인하고 다시 눌러 주세요.';$('ready-button').disabled=false;}
  } finally {readyBusy=false;}
});
