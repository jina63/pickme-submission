export function liveSnapshot(body,previous,now=Date.now()) {
  if(!previous || (previous.expiresAt<=now && body?.state!=='paused'))throw {status:410,message:'차량 위치 공유 시간이 끝났어요. 새 초대를 만들어 주세요.'};
  if(body?.state==='paused')return {state:'paused',source:'pleos',sharingAllowed:false,isEmulator:previous.isEmulator,expiresAt:previous.expiresAt,updatedAt:now,destinationLabel:previous.destinationLabel??null};
  if(!previous.sharingAllowed)throw {status:409,message:'운전자가 차량 위치 공유를 먼저 시작해야 해요.'};
  const p=body?.position;
  if(body?.state!=='on_way' || !p || !Number.isFinite(p.latitude) || !Number.isFinite(p.longitude) || Math.abs(p.latitude)>85 || Math.abs(p.longitude)>180 || (p.latitude===0 && p.longitude===0))throw {status:400,message:'유효한 Pleos 차량 위치가 필요해요.'};
  const etaAge=body.pickupEtaCapturedAt==null?0:now-body.pickupEtaCapturedAt;
  const eta=Number.isFinite(etaAge) && etaAge>=0 && etaAge<=20000?body.pickupEtaSeconds:null;
  if(eta!=null && (!Number.isInteger(eta) || eta<=0 || eta>86400))throw {status:400,message:'픽업 예상 시간을 확인해 주세요.'};
  let cafeVisit;
  if(body.cafeVisit!=null) {
    const cafe=body.cafeVisit,age=now-body.cafeVisitCapturedAt;
    if(typeof cafe.name!=='string' || !cafe.name.trim() || cafe.name.length>120 || !['before_pickup','at_cafe','passed'].includes(cafe.phase) || !Number.isFinite(age))throw {status:400,message:'카페 경유 정보를 확인해 주세요.'};
    const position=cafe.position;
    if(position!=null && (!Number.isFinite(position.latitude) || !Number.isFinite(position.longitude) || Math.abs(position.latitude)>85 || Math.abs(position.longitude)>180 || (position.latitude===0 && position.longitude===0)))throw {status:400,message:'카페 위치를 확인해 주세요.'};
    if(age>=0 && age<=20000)cafeVisit={name:cafe.name.trim(),phase:cafe.phase,...(position?{position:{latitude:position.latitude,longitude:position.longitude}}:{})};
  }
  const destinationLabel=typeof body.destinationLabel==='string' && body.destinationLabel.trim() && body.destinationLabel.length<=120?body.destinationLabel.trim():previous.destinationLabel??null;
  return {state:'on_way',source:'pleos',sharingAllowed:true,isEmulator:previous.isEmulator,expiresAt:previous.expiresAt,updatedAt:now,destinationLabel,
    position:{latitude:p.latitude,longitude:p.longitude,...(Number.isFinite(p.heading)&&p.heading>=0&&p.heading<360?{heading:p.heading}:{})},
    ...(cafeVisit?{cafeVisit}:{}),
    ...(eta!=null?{pickupEtaSeconds:eta,etaSource:'pleos-waypoint'}:{}),etaStatus:eta!=null?'available':'sdk-unavailable'};
}
