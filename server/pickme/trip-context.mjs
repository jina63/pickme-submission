export function validateTrip(value, now=Date.now()) {
  if(value == null)return null;
  function coordinate(point) {
    if(!point || !Number.isFinite(point.latitude) || Math.abs(point.latitude)>85 || !Number.isFinite(point.longitude) || Math.abs(point.longitude)>180 || (point.latitude===0 && point.longitude===0))throw {status:400,message:'차량 위치와 목적지 좌표를 확인해 주세요.'};
    return {latitude:point.latitude,longitude:point.longitude};
  }
  if(!['pleos','demo'].includes(value.source) || !Number.isFinite(value.capturedAt) || value.capturedAt>now+60000 || now-value.capturedAt>300000 || typeof value.destination?.label!=='string' || !value.destination.label.trim() || value.destination.label.length>120)throw {status:400,message:'내비 정보를 다시 가져온 뒤 초대해 주세요. (5분 이내 정보 필요)'};
  const heading=Number.isFinite(value.heading)&&value.heading>=0&&value.heading<360?value.heading:null;
  return {origin:coordinate(value.origin),destination:{...coordinate(value.destination),label:value.destination.label.trim()},source:value.source,capturedAt:value.capturedAt,...(heading!==null?{heading}:{})};
}
