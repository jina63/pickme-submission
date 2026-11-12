const $ = id => document.getElementById(id);
let invitation;
let timer;
let contacts = [];
let selectedId = '';
let busy = false;
async function api(path, method = 'GET', body) {
  const response = await fetch(path, { method, headers: { 'Content-Type': 'application/json' }, ...(body ? { body: JSON.stringify(body) } : {}) });
  const data = await response.json();
  if (!response.ok) throw new Error(data.error);
  return data;
}
const session = api('/api/driver/session', 'POST', {});
session.catch(() => { $('message').textContent = '서버에 연결할 수 없어요. 서버 실행 상태를 확인해 주세요.'; });
function renderContacts() {
  const query = $('search').value.trim().toLocaleLowerCase();
  const visible = contacts.filter(c => c.name.toLocaleLowerCase().includes(query));
  const legend = document.createElement('legend');
  legend.className = 'sr-only'; legend.textContent = '초대할 친구 선택';
  $('contacts').replaceChildren(legend);
  if (!visible.length) {
    const empty = document.createElement('p');
    empty.textContent = contacts.length ? '검색 결과가 없어요. 다른 이름으로 찾아보세요.' : '연락처를 불러오는 중이에요.';
    $('contacts').append(empty);
  }
  for (const contact of visible) {
    const label = document.createElement('label'); label.className = 'contact-option';
    const radio = document.createElement('input'); radio.type = 'radio'; radio.name = 'contact'; radio.value = contact.id;
    radio.checked = contact.id === selectedId;
    radio.addEventListener('change', () => { selectedId = contact.id; updateSelection(); });
    const detail = document.createElement('span');
    const name = document.createElement('strong'); name.textContent = contact.name;
    const subtitle = document.createElement('small'); subtitle.textContent = contact.subtitle;
    detail.append(name, subtitle); label.append(radio, detail); $('contacts').append(label);
  }
  updateSelection();
}
function updateSelection() {
  const selected = contacts.find(c => c.id === selectedId);
  $('selection').textContent = selected ? `${selected.name}님에게 픽업 초대를 만들어요.` : '친구를 선택해 주세요.';
  $('create').disabled = busy || !selected;
  $('create').textContent = busy ? '초대 만드는 중…' : selected ? `${selected.name}님 초대 링크 만들기` : '초대 링크 만들기';
}
api('/api/demo/contacts').then(data => { contacts = data.contacts; renderContacts(); }).catch(() => { $('message').textContent = '시연 연락처를 불러오지 못했어요. 서버를 확인하고 새로고침해 주세요.'; $('contacts').textContent = '연락처 연결 실패'; });
$('search').addEventListener('input', renderContacts);
async function refresh() {
  if (!invitation) return;
  const id = invitation.id;
  try {
    const data = await api(`/api/pickups/${id}`);
    if (id !== invitation?.id) return;
    if (data.pickupPoint) $('summary').textContent = `${data.passengerName}님이 탑승 장소를 확정했어요: ${data.pickupPoint.label}`;
    $('status').textContent = { pending: '수락 대기', accepted: '수락 완료', declined: '거절 완료' }[data.status];
    $('message').textContent = data.status === 'accepted' ? `${data.passengerName}님의 탑승 장소: ${data.pickupPoint?.label || "미정"}${data.pickupPoint ? ` (위도 ${data.pickupPoint.latitude}, 경도 ${data.pickupPoint.longitude})${data.pickupPoint.locationSource === "demo" ? " · 한양대 시연 위치 기준" : ""}` : ""}. 내비 경유지 연결과 도착 시간은 다음 단계예요.` : data.status === 'declined' ? `${data.passengerName}님이 이번 픽업을 거절했어요.` : '';
    if (data.status === 'pending') timer = setTimeout(refresh, 2000);
  } catch (e) { $('message').textContent = `${e.message || '연결 실패'} 새로고침 후 초대를 다시 만들어 주세요.`; }
}
$('form').addEventListener('submit', async event => {
  event.preventDefault();
  if (!selectedId || busy) return;
  busy = true; updateSelection(); clearTimeout(timer);
  try {
    await session;
    invitation = await api('/api/pickups', 'POST', { contactId: selectedId });
    $('result').hidden = false; $('form').hidden = true;
    $('invite').href = invitation.inviteUrl;
    $('summary').textContent = `${invitation.passengerName}님에게 픽업 초대를 만들었어요. 만날 지점과 시간은 아직 정하지 않았어요.`;
    $('message').textContent = '';
    await refresh();
  } catch (e) { $('message').textContent = e.message || '연결 실패. 다시 시도해 주세요.'; }
  finally { busy = false; updateSelection(); }
});
$('copy').addEventListener('click', async () => {
  try { await navigator.clipboard.writeText(invitation.inviteUrl); $('message').textContent = '링크를 복사했어요. 친구에게 직접 전달해 주세요.'; }
  catch { $('message').textContent = '친구 화면 열기 링크를 길게 누르거나 우클릭해 주소를 복사해 주세요.'; }
});
$('new').addEventListener('click', () => {
  clearTimeout(timer); invitation = null; selectedId = ''; $('search').value = '';
  $('form').hidden = false; $('result').hidden = true; $('message').textContent = ''; renderContacts();
});
window.addEventListener('pagehide', () => clearTimeout(timer));
