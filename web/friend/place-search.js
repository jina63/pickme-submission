// User-triggered place lookup; invitation tokens and device coordinates are never sent.
(function(scope){
  const cache=new Map();
  function normalize(features){
    return (Array.isArray(features)?features:[]).flatMap(f=>{
      const [longitude,latitude]=f.geometry?.coordinates || [];
      const p=f.properties || {};
      if(f.geometry?.type!=='Point' || !Number.isFinite(latitude) || !Number.isFinite(longitude) || latitude<33 || latitude>39 || longitude<124 || longitude>132)return [];
      const address=[p.state,p.city,p.district,p.street,p.housenumber].filter(Boolean).filter((v,i,a)=>a.indexOf(v)===i).join(' ');
      const label=String(p.name || address || '').trim().slice(0,120);
      return label?[{latitude,longitude,label,address}]:[];
    }).filter((p,i,a)=>a.findIndex(v=>v.latitude===p.latitude&&v.longitude===p.longitude&&v.label===p.label)===i).slice(0,5);
  }
  async function search(query,{signal,fetchImpl=fetch}={}){
    const q=query.trim();
    if(q.length<2 || q.length>100)throw new Error('장소 이름이나 주소를 2~100자로 입력해 주세요.');
    if(cache.has(q))return cache.get(q);
    const url=new URL('https://photon.komoot.io/api/');
    url.search=new URLSearchParams({q,limit:'8',bbox:'124,33,132,39'});
    const response=await fetchImpl(url,{signal,referrerPolicy:'origin',credentials:'omit'});
    if(!response.ok)throw new Error('장소 검색이 잠시 어려워요. 다시 검색하거나 지도에서 직접 선택해 주세요.');
    const points=normalize((await response.json()).features);
    if(cache.size>=30)cache.delete(cache.keys().next().value);
    cache.set(q,points);return points;
  }
  scope.PickmePlaceSearch={search,normalize};
})(globalThis);
