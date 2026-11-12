import {distanceMeters, validateLocation, recommend, registeredNearby, proposalFrom, MAX_PICKUP_METERS} from './pickup-planner.mjs';
const endpoint='https://overpass-api.de/api/interpreter';
const forbidden=new Set(['no','private','customers','permit','residents','destination','delivery','agricultural','forestry','emergency','designated']);
export function parkingCandidates(elements, location) {
  return elements.flatMap(element=>{
    const t=element.tags ?? {};
    if(t.amenity!=='parking' || t.disused==='yes' || t.abandoned==='yes' || t['disused:amenity'] || t['access:conditional'] || t['motor_vehicle:conditional'] || t['motorcar:conditional']) return [];
    if(['access','vehicle','motor_vehicle','motorcar'].some(k=>t[k] && (forbidden.has(t[k]) || !['yes','permissive','public'].includes(t[k])))) return [];
    if(['street_side','lane','on_kerb','half_on_kerb','shoulder'].includes(t.parking))return [];
    if(t.opening_hours && t.opening_hours!=='24/7')return []; // No opening-hours parser: do not guess whether a timed facility is open.
    const latitude=element.lat ?? element.center?.lat,longitude=element.lon ?? element.center?.lon;
    if(!Number.isFinite(latitude) || !Number.isFinite(longitude) || Math.abs(latitude)>85 || Math.abs(longitude)>180 || !['node','way','relation'].includes(element.type) || !Number.isSafeInteger(element.id))return [];
    const distance=Math.round(distanceMeters(location,{latitude,longitude}));
    if(distance>MAX_PICKUP_METERS)return [];
    const explicit=['yes','permissive','public'].includes(t.access) || ['yes','permissive'].includes(t.motorcar);
    const label=(t['name:ko'] || t.name || '지도에 등록된 주차장').slice(0,120);
    const fee=t.fee==='yes'?'유료':t.fee==='no'?'무료 표기':'요금 미확인';
    const note=`OpenStreetMap 주차장 · ${explicit?'일반 접근 허용 표기':'일반 이용 가능 여부 미확인'} · ${fee} · ${t.opening_hours==='24/7'?'24시간 표기':'운영시간 미확인'} · ${element.type==='node'?'지도 지점':'시설 대표 좌표'}예요. 출입구와 승하차 허용 여부는 확인이 필요해요.`;
    return [{id:`osm-${element.type}-${element.id}`,label,latitude,longitude,verification:'map-unverified',note,distanceMeters:distance,rank:explicit?0:1}];
  }).sort((a,b)=>a.distanceMeters-b.distanceMeters || a.rank-b.rank).map(({rank,...candidate})=>candidate);
}
const kakaoEndpoint='https://dapi.kakao.com/v2/local/search/category.json';
/** Kakao Local category PK6 (주차장). Names are real place names; stopping rules are still unknown. */
export function kakaoCandidates(documents, location) {
  return (Array.isArray(documents)?documents:[]).flatMap(d=>{
    const latitude=Number(d?.y),longitude=Number(d?.x);
    if(!/^[0-9]{1,20}$/.test(String(d?.id ?? '')) || !Number.isFinite(latitude) || !Number.isFinite(longitude) || latitude<33 || latitude>39 || longitude<124 || longitude>132)return [];
    const label=String(d.place_name ?? '').trim().slice(0,120);
    if(!label)return [];
    const distance=Math.round(distanceMeters(location,{latitude,longitude}));
    if(distance>MAX_PICKUP_METERS)return [];
    const address=String(d.road_address_name || d.address_name || '').trim().slice(0,80);
    const note=`카카오맵 주차장${address?` · ${address}`:''} · 출입구와 승하차 가능 여부는 확인이 필요해요.`;
    return [{id:`kakao-${d.id}`,label,latitude,longitude,verification:'kakao',note,distanceMeters:distance}];
  }).sort((a,b)=>a.distanceMeters-b.distanceMeters);
}
export function createParkingSearch({fetchImpl=fetch,now=Date.now,kakaoKey=null}={}) {
  const cache=new Map();
  let inFlight=null;
  return async function search(location,requestId,trip=null) {
    const point=validateLocation(location);
    if(point.source==='demo')return {...recommend(point,requestId),routeStatus:'demo'};
    // Team spots and public-map parking compete on distance; team spots still answer when the public map is down.
    const registered=registeredNearby(point);
    const routeStatus=trip?'pleos-context-only':'missing';
    const key=`${point.latitude.toFixed(3)},${point.longitude.toFixed(3)}`;
    try {
      let entry=cache.get(key);
      if(!entry || entry.expires<now()) {
        if(inFlight)throw new Error('busy');
        const lat=point.latitude.toFixed(3),lng=point.longitude.toFixed(3);
        // Only a ~100m-rounded coordinate leaves the server; no invite token or name is sent.
        const task=kakaoKey?(async()=>{
          const url=new URL(kakaoEndpoint);
          url.search=new URLSearchParams({category_group_code:'PK6',x:lng,y:lat,radius:String(MAX_PICKUP_METERS+150),sort:'distance',size:'15'}).toString();
          const response=await fetchImpl(url,{headers:{Authorization:`KakaoAK ${kakaoKey}`},signal:AbortSignal.timeout(8000)});
          if(!response.ok)throw new Error('provider');
          const data=await response.json();
          if(!Array.isArray(data?.documents))throw new Error('incomplete');
          return {provider:'kakao',items:data.documents};
        })():(async()=>{
          const query=`[out:json][timeout:10];nwr["amenity"="parking"](around:${MAX_PICKUP_METERS+150},${lat},${lng});out center tags;`;
          const response=await fetchImpl(endpoint,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded','Accept':'application/json','User-Agent':'PickmePrototype/0.1 (parking candidate search)'},body:new URLSearchParams({data:query}).toString(),signal:AbortSignal.timeout(12000)});
          if(!response.ok)throw new Error('provider');
          const data=await response.json();
          if(data.remark || !Array.isArray(data.elements))throw new Error('incomplete');
          return {provider:'osm',items:data.elements};
        })();
        inFlight=task;
        try {entry={...(await task),expires:now()+300000};cache.set(key,entry);if(cache.size>64)cache.delete(cache.keys().next().value);}
        finally {inFlight=null;}
      }
      const found=entry.provider==='kakao'?kakaoCandidates(entry.items,point):parkingCandidates(entry.items,point);
      return {...proposalFrom(requestId,[...registered,...found]),routeStatus};
    } catch {
      return registered.length?{...proposalFrom(requestId,registered),routeStatus}:{requestId,kind:'unavailable'};
    }
  };
}
