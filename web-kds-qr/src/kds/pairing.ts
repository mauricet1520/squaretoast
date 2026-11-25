// LAN-hub pairing. The hub's QR code opens /kds?pair=TOKEN. The token moves to sessionStorage and leaves the
// address bar at once, so it is not bookmarked, shared or left on screen.

const KEY = 'kds.pair';
const TOKEN = /^[A-Za-z0-9_-]{1,128}$/; // base64url, as the hub issues it

type SessionStore = { getItem(k: string): string | null; setItem(k: string, v: string): void };

export function takePairingToken(href: string, storage: SessionStore, replaceUrl: (pathAndQuery: string) => void): string | null {
  const url = new URL(href);
  const fromUrl = url.searchParams.get('pair');
  if (fromUrl !== null) {
    url.searchParams.delete('pair');
    replaceUrl(url.pathname + url.search + url.hash);
    if (!TOKEN.test(fromUrl)) {
      return null;
    }
    storage.setItem(KEY, fromUrl);
    return fromUrl;
  }
  return storage.getItem(KEY);
}
