// Private recipient configuration is injected by the hosting environment, never committed.
export function loadMailRecipients(raw, contacts) {
  if (!raw?.trim()) return [];
  let list;
  try { list = JSON.parse(raw); } catch { throw new Error('PICKME_MAIL_RECIPIENTS_JSON must be a JSON array'); }
  if (!Array.isArray(list) || list.length > contacts.length) throw new Error('Invalid mail recipient list');
  const names = new Set();
  return list.map(entry => {
    if (!entry || !contacts.some(c => c.name === entry.name) || names.has(entry.name) ||
        typeof entry.email !== 'string' || entry.email.length > 254 ||
        !/^[^\s@<>]+@[^\s@<>]+\.[^\s@<>]+$/.test(entry.email) ||
        /\.invalid$/i.test(entry.email)) throw new Error('Invalid mail recipient configuration');
    names.add(entry.name);
    return {name: entry.name, email: entry.email};
  });
}
