/** Optional demo bridge. Uses invitation-specific capability keys, never an admin credential. */
export function createFirebaseBridge({ databaseUrl, fetchImpl = fetch, now = Date.now, authClient = null }) {
  const base = databaseUrl.replace(/\/$/, '');
  if (!/^https:\/\/[a-z0-9-]+\.(?:[a-z0-9-]+\.)?(?:firebasedatabase\.app|firebaseio\.com)$/.test(base)) throw new Error('Firebase 데이터베이스 HTTPS 주소를 확인해 주세요.');
  const url = token => {
    if (!/^[A-Za-z0-9_-]{32}$/.test(token)) throw new Error('잘못된 초대 키입니다.');
    return `${base}/pickmeInvites/${token}.json`;
  };
  async function request(token, method = 'GET', body, idToken = null) {
    const response = await fetchImpl(url(token)+(idToken?`?auth=${encodeURIComponent(idToken)}`:''), { method, headers: { 'Content-Type': 'application/json' }, signal: AbortSignal.timeout(10000), ...(body ? { body: JSON.stringify(body) } : {}) });
    if (!response.ok) throw { status: 502, message: 'Firebase 초대 연결 실패. 배포 상태와 규칙을 확인해 주세요.' };
    const data = await response.json();
    if (!data) throw { status: 404, message: '초대가 없거나 만료됐어요.' };
    return data;
  }
  return {
    async readReady(token) {
      const response=await fetchImpl(`${base}/pickmeReady/${token}.json`,{signal:AbortSignal.timeout(10000)});
      if(!response.ok)throw {status:502,message:'탑승자 준비 상태를 읽지 못했어요.'};
      return (await response.json())?.readyAt??null;
    },
    async markReady(token) {
      if(await this.readReady(token))return;
      const response=await fetchImpl(`${base}/pickmeReady/${token}.json`,{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({readyAt:{'.sv':'timestamp'}}),signal:AbortSignal.timeout(10000)});
      if(!response.ok && !await this.readReady(token))throw {status:502,message:'준비 상태를 저장하지 못했어요.'};
    },
    async writeLive(token, live) {
      if(!authClient)throw {status:503,message:'Firebase Authentication 익명 로그인 연결이 필요해요. 차량 위치 공유를 시작하지 않았어요.'};
      const {idToken,uid}=await authClient.credentials();
      const endpoint=`${base}/pickmeLive/${token}.json?auth=${encodeURIComponent(idToken)}`;
      const response=await fetchImpl(endpoint,{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({public:{...live,updatedAt:{'.sv':'timestamp'}}}),signal:AbortSignal.timeout(10000)});
      if(!response.ok)throw {status:503,message:'차량 위치 공유 권한을 확인하지 못했어요. Firebase 인증·규칙을 확인해 주세요.'};
    },
    async create(token, invitation) {
      if (!invitation.contactId) throw { status: 400, message: 'Firebase 시연은 연락처에서 친구를 선택해 주세요.' };
      const credentials=authClient?await authClient.credentials():null;
      await request(token, 'PUT', {
        ...(credentials?{trackingOwnerUid:credentials.uid}:{}),
        contactId: invitation.contactId, passengerName: invitation.passengerName,
        driverName: invitation.driverName, status: 'pending',
        ...(invitation.trip ? {journey:{destinationLabel:invitation.trip.destination.label,source:invitation.trip.source}} : {}),
        createdAt: { '.sv': 'timestamp' }, expiresAt: now() + 24 * 60 * 60 * 1000,
      },credentials?.idToken);
    },
    read: token => request(token),
    propose: (token, proposal) => request(token, 'PATCH', {proposal}),
    async respond(token, status, pickupPoint) {
      const current = await request(token);
      if (current.status === status) return current;
      if (current.status !== 'pending') throw { status: 409, message: '이미 응답한 초대예요.' };
      await request(token, 'PATCH', { status, ...(pickupPoint ? {pickupPoint} : {}), locationRequest:null, proposal:null, respondedAt: { '.sv': 'timestamp' } });
      return request(token);
    },
  };
}
