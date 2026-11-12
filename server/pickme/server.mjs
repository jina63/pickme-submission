import http from 'node:http';
import {loadMailRecipients} from './mail-recipients.mjs';
import { createGmailSender } from './invitation-mail.mjs';
import {createFirebaseAuth} from './firebase-auth.mjs';
import {liveSnapshot} from './live-tracking.mjs';
import {validateTrip} from './trip-context.mjs';
import { createParkingSearch } from './parking-search.mjs';
import { createRoutePreview } from './route-preview.mjs';
import { validateLocation, validatePickupPoint } from './pickup-planner.mjs';
import { createFirebaseBridge } from './firebase-bridge.mjs';
import { createStateStore } from './state-store.mjs';
import { randomBytes, createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const webRoot = new URL('../../web/friend/', import.meta.url);
// 연락처 파일을 읽고 형식을 확인한다. 필수: id, name, subtitle / 선택: phone, email (서버 안에만 보관)
async function loadContacts(fileUrl) {
  const list = JSON.parse(await readFile(fileUrl, 'utf8'));
  if (!Array.isArray(list) || list.length === 0) throw new Error('연락처 파일은 비어 있지 않은 배열이어야 해요.');
  const ids = new Set();
  for (const c of list) {
    for (const key of ['id', 'name', 'subtitle']) {
      if (typeof c?.[key] !== 'string' || !c[key].trim()) throw new Error(`연락처에 ${key} 값이 없어요: ${JSON.stringify(c)}`);
    }
    if (ids.has(c.id)) throw new Error(`연락처 id가 겹쳐요: ${c.id}`);
    ids.add(c.id);
  }
  return list;
}
const demoContacts = await loadContacts(new URL('./demo-contacts.json', import.meta.url));
// 앱·웹에 보내도 되는 필드만 남긴다. 전화번호·이메일은 절대 응답에 넣지 않는다.
const publicContact = ({ id, name, subtitle }) => ({ id, name, subtitle });
const demoMailRecipients = JSON.parse(await readFile(new URL('./demo-mail-recipients.json', import.meta.url), 'utf8'));
const hash = value => createHash('sha256').update(value).digest('hex');
const random = () => randomBytes(24).toString('base64url');
const assets = new Map([
  ['/', ['driver.html', 'text/html; charset=utf-8']],
  ['/friend', ['index.html', 'text/html; charset=utf-8']],
  ['/styles.css', ['styles.css', 'text/css; charset=utf-8']],
  ['/place-search.js', ['place-search.js', 'text/javascript; charset=utf-8']],
  ['/friend.js', ['friend.js', 'text/javascript; charset=utf-8']],
  ['/hosting-config.js', ['hosting-config.js', 'text/javascript; charset=utf-8']],
  ['/driver.js', ['driver.js', 'text/javascript; charset=utf-8']],
  ['/vendor/leaflet/leaflet.js', ['vendor/leaflet/leaflet.js', 'text/javascript; charset=utf-8']],
  ['/vendor/leaflet/leaflet.css', ['vendor/leaflet/leaflet.css', 'text/css; charset=utf-8']],
]);

export function createInvitationServer({ publicBaseUrl, now = Date.now, ttlMs = 24 * 60 * 60 * 1000, firebaseBridge = null, parkingSearch = createParkingSearch(), routePreview = createRoutePreview(), mailSender = null, contacts = demoContacts, mailRecipients = demoMailRecipients, stateStore = null } = {}) {
  // stateStore가 없으면(로컬 개발) 서버를 재시작할 때 초대와 세션이 모두 사라진다.
  // 배포 서버는 stateStore로 파일에 저장해서 재시작 후에도 이어서 쓴다.
  const saved = stateStore?.get('relay');
  const sessions = new Map(saved?.sessions ?? []);
  const invitations = new Map((saved?.invitations ?? []).map(invite => [invite.id, invite]));
  const friendTokens = new Map([...invitations.values()].map(invite => [invite.tokenHash, invite.id]));
  const persist = () => stateStore?.set('relay', { sessions: [...sessions], invitations: [...invitations.values()] });
  const mailLimits = new Map();
  const publicInvite = i => ({
    id: i.id, driverName: i.driverName, passengerName: i.passengerName, contactId: i.contactId,
    driverAccepted: !!i.live,
    passengerReadyAt: i.passengerReadyAt ?? null, arrivalMailState:i.arrivalMailState??null,
    liveTracking: i.live ?? null,
    journey: i.trip ? {destinationLabel:i.trip.destination.label,source:i.trip.source} : null,
    pickupPoint: i.pickupPoint ?? null, proposal: i.proposal ?? null,
    meetingPoint: i.meetingPoint, destination: i.destination, status: i.status,
    createdAt: i.createdAt, respondedAt: i.respondedAt, expiresAt: i.expiresAt,
  });
  const server = http.createServer(async (req, res) => {
    const send = (status, value, headers = {}) => {
      res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', ...headers });
      res.end(JSON.stringify(value));
    };
    // 요청 처리 중 바뀐 세션·초대를 저장한다 (정적 파일 요청은 제외).
    if (stateStore && !assets.has(req.url?.split('?')[0])) res.on('finish', persist);
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('Referrer-Policy', 'strict-origin-when-cross-origin');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Content-Security-Policy', "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self'; img-src 'self' data: https://tile.openstreetmap.org; connect-src 'self' https://photon.komoot.io; frame-ancestors 'none'; base-uri 'none'");
    try {
      const url = new URL(req.url, 'http://localhost');
      const origin = req.headers.origin;
      if (origin && origin !== `http://${req.headers.host}` && origin !== `https://${req.headers.host}`) {
        return send(403, { error: '다른 사이트에서 보낸 요청은 허용되지 않아요.' });
      }
      for (const [key, expiry] of sessions) if (expiry <= now()) sessions.delete(key);
      for (const [id, invite] of invitations) {
        if (now() >= Date.parse(invite.expiresAt)) {
          invitations.delete(id);
          friendTokens.delete(invite.tokenHash);
        }
      }
      if (req.method === 'GET' && assets.has(url.pathname)) {
        const [file, type] = assets.get(url.pathname);
        const content = await readFile(new URL(file, webRoot));
        res.writeHead(200, { 'Content-Type': type });
        return res.end(content);
      }
      if (req.method === 'GET' && url.pathname === '/api/demo/contacts') return send(200, { contacts: contacts.map(publicContact), source: 'demo' });
      if (req.method === 'GET' && url.pathname === '/health') return send(200, { ok: true, mode: stateStore ? 'deployed' : 'local-development' });
      const readBody = async () => {
        if (!req.headers['content-type']?.startsWith('application/json')) throw { status: 415, message: 'JSON 요청이 필요해요.' };
        let size = 0;
        const chunks = [];
        for await (const chunk of req) {
          size += chunk.length;
          if (size > 8192) throw { status: 413, message: '입력 내용이 너무 길어요.' };
          chunks.push(chunk);
        }
        try { return JSON.parse(Buffer.concat(chunks).toString()); }
        catch { throw { status: 400, message: '입력 형식을 확인해 주세요.' }; }
      };
      if (req.method === 'POST' && url.pathname === '/api/driver/session') {
        await readBody();
        if (sessions.size >= 500) return send(503, { error: '개발 서버가 가득 찼어요. 서버를 다시 시작해 주세요.' });
        const token = random();
        sessions.set(hash(token), now() + ttlMs);
        return send(201, { ok: true }, { 'Set-Cookie': `pickme_session=${token}; HttpOnly; SameSite=Strict; Path=/; Max-Age=${Math.floor(ttlMs / 1000)}` });
      }
      const cookie = req.headers.cookie?.split(';').map(v => v.trim()).find(v => v.startsWith('pickme_session='))?.slice(15);
      const owner = cookie && sessions.has(hash(cookie)) ? hash(cookie) : null;
      const requireOwner = () => { if (!owner) throw { status: 401, message: '운전자 연결이 만료됐어요. 새로 시작해 주세요.' }; };
      const requireInvitation = id => {
        const invitation = invitations.get(id);
        if (!invitation) throw { status: 404, message: '초대가 없거나 만료됐어요. 운전자에게 새 링크를 요청해 주세요.' };
        return invitation;
      };
      if (req.method === 'GET' && url.pathname === '/api/driver/mail-recipients') {
        requireOwner();
        return send(200, {recipients:mailRecipients});
      }
      if (req.method === 'POST' && url.pathname === '/api/pickups') {
        requireOwner();
        const body = await readBody();
        const text = (key, fallback) => {
          const value = body?.[key] ?? fallback;
          if (typeof value !== 'string' || !value.trim() || value.trim().length > 120) throw { status: 400, message: '이름과 장소를 1~120자로 입력해 주세요.' };
          return value.trim();
        };
        const contact = contacts.find(c => c.id === body?.contactId);
        if (body?.contactId != null && !contact) return send(400, { error: '시연 연락처에서 친구를 선택해 주세요.' });
        const optionalText = key => body?.[key] == null || body[key] === '' ? null : text(key);
        const trip = validateTrip(body?.trip, now());
        const fields = {
          trip,
          driverName: text('driverName', '운전자'),
          passengerName: contact?.name ?? text('passengerName'),
          contactId: contact?.id ?? null,
          meetingPoint: optionalText('meetingPoint'), destination: optionalText('destination'),
        };
        if(fields.driverName.length>40)return send(400,{error:'운전자 이름은 40자 이내로 입력해 주세요.'});
        if (invitations.size >= 1000) return send(503, { error: '개발 서버가 가득 찼어요.' });
        const id = random();
        const token = random();
        const invite = { id, ...fields, owner, tokenHash: hash(token), status: 'pending', createdAt: new Date(now()).toISOString(), expiresAt: new Date(now() + ttlMs).toISOString(), respondedAt: null };
        if (firebaseBridge) {
          await firebaseBridge.create(token, invite);
          invite.remoteKey = token; // Only held in server memory, never returned to driver API.
        }
        invitations.set(id, invite);
        friendTokens.set(invite.tokenHash, id);
        const base = publicBaseUrl ?? `http://${req.headers.host}`;
        invite.inviteUrl = `${base.replace(/\/$/, "")}/friend#${token}`;
        return send(201, { ...publicInvite(invite), inviteUrl: `${base.replace(/\/$/, '')}/friend#${token}` });
      }
      const mailRoute = url.pathname.match(/^\/api\/pickups\/([A-Za-z0-9_-]+)\/email$/);
      if (req.method === 'POST' && mailRoute) {
        requireOwner();
        const invite = requireInvitation(mailRoute[1]);
        if (invite.owner !== owner) return send(404, {error:'초대를 찾을 수 없어요.'});
        if (invite.status !== 'pending') return send(409, {error:'이미 응답한 초대는 메일로 보낼 수 없어요.'});
        const body = await readBody();
        const recipient = mailRecipients.find(entry=>entry.name===invite.passengerName)?.email ?? '';
        if(body?.email != null && (typeof body.email !== 'string' || body.email.trim().toLowerCase() !== recipient.toLowerCase())) return send(400,{error:'선택한 연락처의 등록 이메일로만 보낼 수 있어요.'});
        if (recipient.length > 254 || !/^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\.[A-Za-z]{2,}$/.test(recipient)) return send(400, {error:'이메일 주소를 확인해 주세요.'});
        if (!mailRecipients.some(entry => entry.email.toLowerCase() === recipient.toLowerCase())) return send(400, {error:'시연용으로 등록된 네 주소 중에서 선택해 주세요.'});
        if (!mailSender) return send(503, {error:'서버에 메일 발송 설정이 필요해요.'});
        if (!invite.inviteUrl?.startsWith('https://')) return send(409, {error:'메일 초대에는 HTTPS 친구 웹 주소를 설정해 주세요.'});
        if (invite.mailState) return send(409, {error:'이미 발송을 요청한 초대예요. 메일함과 스팸함을 확인해 주세요.'});
        for (const [key, entry] of mailLimits) if (entry.until <= now()) mailLimits.delete(key);
        const limit = mailLimits.get(owner) ?? {count:0,until:now()+3600000};
        if (limit.count >= 5) return send(429, {error:'시간당 5회까지 보낼 수 있어요. 잠시 후 다시 시도해 주세요.'});
        limit.count++; mailLimits.set(owner, limit);
        invite.mailState = 'sending';
        try {
          await mailSender({to:recipient,inviteUrl:invite.inviteUrl,driverName:invite.driverName,passengerName:invite.passengerName,destinationLabel:invite.trip?.destination.label??invite.destination});
          invite.mailState = 'submitted';
          invite.mailRecipient=recipient;
          return send(200, {status:'submitted',recipient,message:`${invite.passengerName}님 등록 주소 ${recipient}로 메일 서버에 접수됐어요. 해당 메일함과 스팸함에서 확인해 주세요.`});
        } catch {
          // SMTP timeouts can occur after acceptance. Do not retry automatically.
          invite.mailState = 'unknown';
          return send(502, {error:'발송 결과를 확인하지 못했어요. 중복 발송을 피하려면 메일함을 먼저 확인해 주세요. 미수신이면 새 초대를 만들어 주세요.'});
        }
      }
      const liveRoute=url.pathname.match(/^\/api\/pickups\/([A-Za-z0-9_-]+)\/(driver-accept|live)$/);
      if(req.method==='POST' && liveRoute) {
        requireOwner();
        const invite=requireInvitation(liveRoute[1]);
        if(invite.owner!==owner)return send(404,{error:'초대를 찾을 수 없어요.'});
        if(firebaseBridge && invite.remoteKey) {
          const remote=await firebaseBridge.read(invite.remoteKey);
          invite.status=remote.status;invite.pickupPoint=remote.pickupPoint??null;
        }
        if(invite.status!=='accepted' || !invite.pickupPoint)return send(409,{error:'친구가 탑승 장소를 먼저 확정해야 해요.'});
        const body=await readBody();
        if(liveRoute[2]==='driver-accept') {
          if(invite.live?.expiresAt<=now())return send(410,{error:'공유 시간이 끝났어요. 새 초대를 만들어 주세요.'});
          if(!invite.live || invite.live.state==='paused' || invite.live.sharingAllowed!==(body.sharingAllowed===true)) {
            const live={state:'accepted',source:'pleos',sharingAllowed:body.sharingAllowed===true,isEmulator:body.isEmulator===true,updatedAt:now(),expiresAt:invite.live?.expiresAt??Math.min(Date.parse(invite.expiresAt),now()+3600000),destinationLabel:invite.trip?.destination.label??null};
            if(firebaseBridge)await firebaseBridge.writeLive(invite.remoteKey,live);
            invite.live=live;
          }
        } else {
          const live=liveSnapshot(body,invite.live,now());
          // 예상 도로 경로: 차량 → (카페에 들르기 전이면 카페) → 친구. 계산은 뒤에서 하고 다음 갱신에 실린다.
          const cafeStop=live.cafeVisit?.phase==='before_pickup'?live.cafeVisit.position:null;
          const route=live.state==='on_way'?routePreview(invite.id,live.position,cafeStop,invite.pickupPoint):null;
          if(route)live.routePreview=route;
          if(firebaseBridge)await firebaseBridge.writeLive(invite.remoteKey,live);
          invite.live=live;
          if(mailSender && invite.mailState==='submitted' && invite.mailRecipient && !invite.arrivalMailState && live.state==='on_way' && live.sharingAllowed && live.etaSource==='pleos-waypoint' && Number.isInteger(live.pickupEtaSeconds) && live.pickupEtaSeconds>0 && live.pickupEtaSeconds<=300 && now()-live.updatedAt<=20000) {
            invite.arrivalMailState='sending';
            Promise.resolve().then(()=>mailSender({to:invite.mailRecipient,inviteUrl:invite.inviteUrl,kind:'arrival',driverName:invite.driverName,passengerName:invite.passengerName,destinationLabel:invite.trip?.destination.label??invite.destination}))
              .then(()=>{invite.arrivalMailState='submitted';},()=>{invite.arrivalMailState='unknown';}).finally(persist);
          }
        }
        return send(200,{liveTracking:invite.live,ttlMillis:Math.max(0,invite.live.expiresAt-now())});
      }
      const ownerRoute = url.pathname.match(/^\/api\/pickups\/([A-Za-z0-9_-]+)$/);
      if (req.method === 'GET' && ownerRoute) {
        requireOwner();
        const invite = requireInvitation(ownerRoute[1]);
        if (invite.owner !== owner) return send(404, { error: '초대를 찾을 수 없어요.' });
        if (firebaseBridge && invite.remoteKey) {
          const remote = await firebaseBridge.read(invite.remoteKey);
          invite.status = remote.status;
          invite.respondedAt = remote.respondedAt ? new Date(remote.respondedAt).toISOString() : null;
          invite.pickupPoint = remote.pickupPoint ?? null;
          invite.meetingPoint = remote.pickupPoint?.label ?? invite.meetingPoint;
          invite.passengerReadyAt = remote.status==='accepted' ? await firebaseBridge.readReady(invite.remoteKey) : null;
        }
        return send(200, publicInvite(invite));
      }
      if (url.pathname === '/api/friend/recommendation' && req.method === 'POST') {
        const token = req.headers.authorization?.match(/^Bearer ([A-Za-z0-9_-]+)$/)?.[1];
        if (!token || !friendTokens.has(hash(token))) return send(404, {error:'초대가 없거나 만료됐어요.'});
        const invite = requireInvitation(friendTokens.get(hash(token)));
        if (invite.status !== 'pending') return send(409, {error:'이미 확정된 초대예요.'});
        const body = await readBody();
        const location = validateLocation(body.location);
        if (!/^[A-Za-z0-9_-]{16,40}$/.test(body.requestId ?? '')) return send(400, {error:'위치 요청을 다시 시도해 주세요.'});
        invite.proposal = await parkingSearch(location, body.requestId, invite.trip);
        return send(200, invite.proposal);
      }
      if(url.pathname==='/api/friend/ready' && ['GET','POST'].includes(req.method)) {
        const token=req.headers.authorization?.match(/^Bearer ([A-Za-z0-9_-]{32})$/)?.[1];
        if(!token || !friendTokens.has(hash(token)))return send(404,{error:'초대 링크를 확인해 주세요.'});
        const invite=requireInvitation(friendTokens.get(hash(token)));
        if(firebaseBridge && invite.remoteKey) {
          const remote=await firebaseBridge.read(invite.remoteKey);invite.status=remote.status;
        }
        if(invite.status!=='accepted')return send(409,{error:'탑승 장소를 먼저 확정해 주세요.'});
        if(req.method==='POST') {
          await readBody();
          if(firebaseBridge)await firebaseBridge.markReady(invite.remoteKey);
          else invite.passengerReadyAt??=now();
        }
        if(firebaseBridge)invite.passengerReadyAt=await firebaseBridge.readReady(invite.remoteKey);
        return send(200,{readyAt:invite.passengerReadyAt??null});
      }
      if(url.pathname==='/api/friend/live' && req.method==='GET') {
        const token=req.headers.authorization?.match(/^Bearer ([A-Za-z0-9_-]{32})$/)?.[1];
        if(!token || !friendTokens.has(hash(token)))return send(404,{error:'초대 링크를 확인해 주세요.'});
        const invite=requireInvitation(friendTokens.get(hash(token)));
        return send(200,invite.live && invite.live.expiresAt>now()?invite.live:null);
      }
      if (url.pathname === '/api/friend/invitation'  && ['GET', 'POST'].includes(req.method)) {
        const token = req.headers.authorization?.match(/^Bearer ([A-Za-z0-9_-]+)$/)?.[1];
        if (!token || !friendTokens.has(hash(token))) return send(404, { error: '초대 링크가 없거나 만료됐어요. 새 링크를 요청해 주세요.' });
        const invite = requireInvitation(friendTokens.get(hash(token)));
        if (firebaseBridge && invite.remoteKey) {
          const remote = await firebaseBridge.read(invite.remoteKey);
          invite.status = remote.status;
          invite.pickupPoint = remote.pickupPoint ?? null;
          invite.meetingPoint = remote.pickupPoint?.label ?? invite.meetingPoint;
          invite.respondedAt = remote.respondedAt ? new Date(remote.respondedAt).toISOString() : null;
        }
        if (req.method === 'POST') {
          const body = await readBody();
          if (!['accepted', 'declined'].includes(body?.status)) return send(400, { error: '수락 또는 거절을 선택해 주세요.' });
          if (invite.status !== 'pending') {
            if (invite.status === body.status) return send(200, publicInvite(invite));
            return send(409, { error: '이미 응답한 초대예요. 변경하려면 운전자에게 알려 주세요.' });
          }
          const pickupPoint = body.status === 'accepted' ? validatePickupPoint(body.pickupPoint, invite.proposal?.candidates ?? invite.proposal?.candidate) : null;
          if (firebaseBridge && invite.remoteKey) {
            const remote = await firebaseBridge.respond(invite.remoteKey, body.status, pickupPoint);
            invite.status = remote.status;
            invite.respondedAt = remote.respondedAt ? new Date(remote.respondedAt).toISOString() : null;
          } else if (invite.status === 'pending') { invite.status = body.status; invite.respondedAt = new Date(now()).toISOString(); }
          if (pickupPoint) { invite.pickupPoint = pickupPoint; invite.meetingPoint = pickupPoint.label; }
        }
        return send(200, publicInvite(invite));
      }
      return send(404, { error: '요청한 페이지를 찾을 수 없어요.' });
    } catch (error) {
      if (!res.headersSent) send(error.status ?? 500, { error: error.status ? error.message : '서버 오류가 발생했어요. 잠시 후 다시 시도해 주세요.' });
      else res.end();
    }
  });
  let planning = false;
  const planner = firebaseBridge ? setInterval(async () => {
    if (planning) return;
    planning = true;
    try {
      for (const invite of invitations.values()) {
        if (!invite.remoteKey || invite.status !== 'pending' || Date.parse(invite.expiresAt) <= now()) continue;
        try {
          const remote = await firebaseBridge.read(invite.remoteKey);
          if (remote.status !== 'pending') {
            invite.status = remote.status;
            invite.pickupPoint = remote.pickupPoint ?? null;
            invite.meetingPoint = remote.pickupPoint?.label ?? invite.meetingPoint;
            invite.respondedAt = remote.respondedAt ? new Date(remote.respondedAt).toISOString() : null;
            continue;
          }
          const request = remote.locationRequest;
          if (request && request.requestId !== remote.proposal?.requestId) {
            const proposal = await parkingSearch(request.location, request.requestId, invite.trip);
            invite.proposal = proposal;
            await firebaseBridge.propose(invite.remoteKey, proposal);
          }
        } catch { /* A response/expiry may race planning; next poll retries without logging location. */ }
      }
    } finally { planning = false; persist(); }
  }, 2000) : null;
  planner?.unref();
  server.on('close', () => { clearInterval(planner); if (stateStore) { persist(); stateStore.flush(); } });
  return server;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const port = Number(process.env.PORT || 8787);
  const host = process.env.HOST || '127.0.0.1';
  const publicBaseUrl = process.env.PUBLIC_BASE_URL || `http://127.0.0.1:${port}`;
  const databaseUrl = process.env.PICKME_FIREBASE_DATABASE_URL;
  const apiKey=process.env.PICKME_FIREBASE_WEB_API_KEY;
  // PICKME_STATE_FILE이 있으면(배포 서버) 초대·세션·서버 로그인 정보를 그 파일에 저장한다.
  const stateStore = process.env.PICKME_STATE_FILE ? createStateStore(process.env.PICKME_STATE_FILE) : null;
  const authClient=apiKey?createFirebaseAuth({apiKey,initialSession:stateStore?.get('firebaseAuth'),onSession:session=>stateStore?.set('firebaseAuth',session)}):null;
  const firebaseBridge = databaseUrl ? createFirebaseBridge({ databaseUrl, authClient }) : null;
  // .env에 PICKME_CONTACTS_FILE이 있으면 그 파일(server/pickme 폴더 기준)을, 없으면 demo-contacts.json을 쓴다.
  const contactsFile = process.env.PICKME_CONTACTS_FILE;
  const contacts = contactsFile ? await loadContacts(new URL(contactsFile, import.meta.url)) : demoContacts;
  if (contactsFile) console.log(`연락처 파일: ${contactsFile} (${contacts.length}명)`);
  const mailRecipients = loadMailRecipients(process.env.PICKME_MAIL_RECIPIENTS_JSON, contacts);
  const kakaoKey = process.env.KAKAO_REST_API_KEY || null;
  console.log(kakaoKey ? '주변 주차장 검색·예상 경로: 카카오' : '주변 주차장 검색: OpenStreetMap(Overpass), 예상 경로 없음 — 카카오 키가 없어요');
  const server = createInvitationServer({ publicBaseUrl, firebaseBridge, contacts, mailRecipients, mailSender: mailRecipients.length ? createGmailSender(process.env) : null, parkingSearch: createParkingSearch({ kakaoKey }), routePreview: createRoutePreview({ kakaoKey }), stateStore });
  if (stateStore) console.log('상태 저장: 켜짐 (서버를 재시작해도 초대가 유지돼요)');
  for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, () => { server.close(() => process.exit(0)); server.closeAllConnections(); });
  server.listen(port, host, () => console.log(`픽미 로컬 개발 서버: http://${host}:${port}\n친구 링크 기본 주소: ${publicBaseUrl}${stateStore ? '' : '\n서버를 종료하면 운전자 세션이 초기화됩니다.'}`));
}
