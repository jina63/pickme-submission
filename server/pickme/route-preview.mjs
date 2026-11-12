import { distanceMeters } from './pickup-planner.mjs';

// 친구 웹에 보여 줄 "예상 도로 경로". Pleos SDK는 경로 좌표를 주지 않아 카카오모빌리티 길찾기로 계산한다.
// 실제 Pleos 안내 경로와 다를 수 있으므로 화면에는 '예상 경로'로만 표시한다.
const endpoint = 'https://apis-navi.kakaomobility.com/v1/directions';
const MAX_POINTS = 160;
const REFRESH_MS = 60000;
const MOVE_METERS = 300;

/** Google encoded polyline (precision 5). One short string fits a single Firebase field. */
export function encodePolyline(points) {
  let lastLat = 0, lastLng = 0, out = '';
  const enc = v => { v = v < 0 ? ~(v << 1) : v << 1; let s = ''; while (v >= 0x20) { s += String.fromCharCode((0x20 | (v & 0x1f)) + 63); v >>= 5; } return s + String.fromCharCode(v + 63); };
  for (const [lat, lng] of points) {
    const a = Math.round(lat * 1e5), b = Math.round(lng * 1e5);
    out += enc(a - lastLat) + enc(b - lastLng);
    lastLat = a; lastLng = b;
  }
  return out;
}

/** Keeps first/last and evenly thins the middle so the web map stays light. */
export function thinPath(points, max = MAX_POINTS) {
  if (points.length <= max) return points;
  const step = (points.length - 1) / (max - 1);
  return Array.from({ length: max }, (_, i) => points[Math.round(i * step)]);
}

export function pathFromDirections(data) {
  const route = data?.routes?.[0];
  if (!route || route.result_code !== 0 || !Array.isArray(route.sections)) return null;
  const points = [];
  for (const section of route.sections) for (const road of section.roads ?? []) {
    const v = road.vertexes ?? [];
    for (let i = 0; i + 1 < v.length; i += 2) {
      const lng = Number(v[i]), lat = Number(v[i + 1]);
      if (!Number.isFinite(lat) || !Number.isFinite(lng) || lat < 33 || lat > 39 || lng < 124 || lng > 132) continue;
      const last = points[points.length - 1];
      if (!last || last[0] !== lat || last[1] !== lng) points.push([lat, lng]);
    }
  }
  return points.length >= 2 ? points : null;
}

const valid = p => p && Number.isFinite(p.latitude) && Number.isFinite(p.longitude) && Math.abs(p.latitude) <= 85 && Math.abs(p.longitude) <= 180 && (p.latitude !== 0 || p.longitude !== 0);
const lngLat = p => `${p.longitude.toFixed(5)},${p.latitude.toFixed(5)}`;

/**
 * Returns a function (inviteId, vehicle, cafe|null, pickup) => cached preview or null.
 * It never waits for Kakao: a refresh runs in the background and the next live update carries it.
 */
export function createRoutePreview({ kakaoKey = null, fetchImpl = fetch, now = Date.now } = {}) {
  const cache = new Map();
  return function preview(inviteId, vehicle, cafe, pickup) {
    if (!kakaoKey || !valid(vehicle) || !valid(pickup)) return null;
    const via = valid(cafe) ? cafe : null;
    const target = `${lngLat(pickup)}|${via ? lngLat(via) : '-'}`;
    const entry = cache.get(inviteId);
    const fresh = entry && entry.target === target && now() - entry.at < REFRESH_MS && distanceMeters(entry.origin, vehicle) < MOVE_METERS;
    if (!fresh && !entry?.busy) {
      const next = { ...(entry ?? {}), busy: true };
      cache.set(inviteId, next);
      const url = new URL(endpoint);
      url.search = new URLSearchParams({ origin: lngLat(vehicle), destination: lngLat(pickup), ...(via ? { waypoints: lngLat(via) } : {}), summary: 'false' }).toString();
      Promise.resolve()
        .then(() => fetchImpl(url, { headers: { Authorization: `KakaoAK ${kakaoKey}` }, signal: AbortSignal.timeout(8000) }))
        .then(r => r.ok ? r.json() : null)
        .then(data => {
          const path = data && pathFromDirections(data);
          cache.set(inviteId, path
            ? { busy: false, target, at: now(), origin: { latitude: vehicle.latitude, longitude: vehicle.longitude }, value: { polyline: encodePolyline(thinPath(path)), viaCafe: !!via, source: 'kakao-directions' } }
            : { ...next, busy: false, at: now(), target, origin: vehicle, value: entry?.target === target ? entry.value : null });
        }, () => cache.set(inviteId, { ...next, busy: false, at: now(), target, origin: vehicle, value: entry?.target === target ? entry.value : null }));
      if (cache.size > 200) cache.delete(cache.keys().next().value);
    }
    const current = cache.get(inviteId);
    return current?.target === target ? current.value ?? null : null;
  };
}
