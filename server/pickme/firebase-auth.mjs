/**
 * Server-only Firebase anonymous identity. Tokens never go to logs or source.
 * 배포 서버는 initialSession/onSession으로 로그인 정보를 상태 파일에 보관해서,
 * 재시작 후에도 같은 익명 계정(uid)으로 진행 중인 위치 공유(pickmeLive)를 이어서 쓴다.
 */
export function createFirebaseAuth({apiKey,fetchImpl=fetch,now=Date.now,emulatorHost=null,initialSession=null,onSession=null}) {
  if(!apiKey)throw new Error('Firebase Web API key is required for server authentication.');
  if(emulatorHost && !/^(127\.0\.0\.1|localhost):\d+$/.test(emulatorHost))throw new Error('Auth emulator must be local.');
  let session=initialSession?.refreshToken && initialSession?.uid ? {...initialSession} : null,pending=null;
  function url(host,path){return emulatorHost?`http://${emulatorHost}/${host}${path}?key=${encodeURIComponent(apiKey)}`:`https://${host}${path}?key=${encodeURIComponent(apiKey)}`;}
  async function login() {
    const refreshing=!!session?.refreshToken;
    const response=await fetchImpl(refreshing?url('securetoken.googleapis.com','/v1/token'):url('identitytoolkit.googleapis.com','/v1/accounts:signUp'),{
      method:'POST',headers:{'Content-Type':refreshing?'application/x-www-form-urlencoded':'application/json'},
      body:refreshing?new URLSearchParams({grant_type:'refresh_token',refresh_token:session.refreshToken}).toString():JSON.stringify({returnSecureToken:true}),signal:AbortSignal.timeout(15000),
    });
    const body=await response.json();
    if(!response.ok)throw {status:503,message:'Firebase Authentication의 익명 로그인을 먼저 설정해 주세요. 차량 위치 공유는 아직 시작하지 않았어요.'};
    const idToken=body.idToken??body.id_token,uid=body.localId??body.user_id,refreshToken=body.refreshToken??body.refresh_token;
    if(!idToken || !uid || !refreshToken)throw {status:503,message:'Firebase 인증 응답을 확인하지 못했어요.'};
    session={idToken,uid,refreshToken,expiresAt:now()+Number(body.expiresIn??body.expires_in)*1000};
    onSession?.({...session});
    return {idToken,uid};
  }
  return {async credentials(){
    if(session && session.expiresAt-now()>120000)return {idToken:session.idToken,uid:session.uid};
    if(!pending)pending=login().finally(()=>{pending=null;});
    return pending;
  }};
}
