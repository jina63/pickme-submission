import { readFile } from 'node:fs/promises';
export const candidates = JSON.parse(await readFile(new URL('./pickup-candidates.json', import.meta.url), 'utf8'));
// A pickup spot should be a short walk: about 7 minutes at 4km/h. Straight-line distance only.
export const MAX_PICKUP_METERS = 500;
export const MAX_PICKS = 3;
export function distanceMeters(a, b) {
  const rad = d => d * Math.PI / 180;
  const p = rad(b.latitude - a.latitude), q = rad(b.longitude - a.longitude);
  const x = Math.sin(p/2)**2 + Math.cos(rad(a.latitude))*Math.cos(rad(b.latitude))*Math.sin(q/2)**2;
  return 6371000 * 2 * Math.atan2(Math.sqrt(x), Math.sqrt(1-x));
}
export function validateLocation(value) {
  if (!value || !Number.isFinite(value.latitude) || Math.abs(value.latitude)>85 || !Number.isFinite(value.longitude) || Math.abs(value.longitude)>180 || !Number.isFinite(value.accuracy) || value.accuracy<0 || value.accuracy>100000 || !['gps','demo','search'].includes(value.source)) throw {status:400,message:'위치를 다시 확인해 주세요.'};
  return {latitude:value.latitude,longitude:value.longitude,accuracy:value.accuracy,source:value.source};
}
export function registeredNearby(point) {
  return candidates.map(c=>({...c,distanceMeters:Math.round(distanceMeters(point,c))})).filter(c=>c.distanceMeters<=MAX_PICKUP_METERS);
}
/** Nearest first, at most MAX_PICKS. `candidate` stays the first pick for older clients. */
export function proposalFrom(requestId, picks) {
  const list=[...picks].sort((a,b)=>a.distanceMeters-b.distanceMeters).filter((c,i,a)=>a.findIndex(v=>v.id===c.id)===i).slice(0,MAX_PICKS);
  return list.length?{requestId,kind:'candidate',candidate:list[0],candidates:list}:{requestId,kind:'none'};
}
export function recommend(location, requestId) {
  // Straight-line proximity only; driving routes and stopping legality are not inferred.
  return proposalFrom(requestId, registeredNearby(validateLocation(location)));
}
export function validatePickupPoint(value, suggested=null) {
  if (!value || !Number.isFinite(value.latitude) || Math.abs(value.latitude)>85 || !Number.isFinite(value.longitude) || Math.abs(value.longitude)>180 || typeof value.label!=='string' || !value.label.trim() || value.label.length>120 || !['recommended','manual'].includes(value.source) || !['gps','demo','manual'].includes(value.locationSource)) throw {status:400,message:'지도에서 탑승 위치를 선택해 주세요.'};
  const point={latitude:value.latitude,longitude:value.longitude,label:value.label.trim(),source:value.source,locationSource:value.locationSource};
  if (value.source==='recommended') {
    const offered=(Array.isArray(suggested)?suggested:[suggested]).filter(Boolean);
    const candidate=offered.find(c=>c.id===value.candidateId) ?? candidates.find(c=>c.id===value.candidateId);
    if (!candidate || candidate.latitude!==value.latitude || candidate.longitude!==value.longitude || candidate.label!==value.label) throw {status:400,message:'추천 장소를 다시 확인해 주세요.'};
    point.candidateId=candidate.id;
  }
  return point;
}
