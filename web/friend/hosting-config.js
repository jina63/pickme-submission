// Public Firebase endpoint only. No API key, access token or admin credential.
window.PICKME_FIREBASE_DATABASE_URL = [
  'pickme-ca1cd-friend.web.app', 'pickme-ca1cd-friend.firebaseapp.com',
].includes(location.hostname)
  ? 'https://pickme-ca1cd-default-rtdb.asia-southeast1.firebasedatabase.app'
  : null;
