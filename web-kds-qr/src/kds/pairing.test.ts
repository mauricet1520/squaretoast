import { describe, expect, it } from 'vitest';
import { takePairingToken } from './pairing';

function memoryStorage() {
  const m = new Map<string, string>();
  return {
    getItem: (k: string) => m.get(k) ?? null,
    setItem: (k: string, v: string) => void m.set(k, v),
  };
}

describe('hub pairing token', () => {
  it('moves the token from the URL into session storage and strips it from the address bar', () => {
    const storage = memoryStorage();
    const replaced: string[] = [];
    const token = takePairingToken('http://192.168.1.20:8787/kds?pair=abc_DEF-123&station=Grill#x', storage, (u) => replaced.push(u));

    expect(token).toBe('abc_DEF-123');
    expect(storage.getItem('kds.pair')).toBe('abc_DEF-123');
    expect(replaced).toEqual(['/kds?station=Grill#x']);
  });

  it('falls back to the stored token after a reload, and leaves a clean URL alone', () => {
    const storage = memoryStorage();
    storage.setItem('kds.pair', 'stored');
    const replaced: string[] = [];
    expect(takePairingToken('http://192.168.1.20:8787/kds', storage, (u) => replaced.push(u))).toBe('stored');
    expect(replaced).toEqual([]);
  });

  it('rejects a token with characters a hub never issues', () => {
    const storage = memoryStorage();
    expect(takePairingToken('http://h/kds?pair=%3Cscript%3E', storage, () => {})).toBeNull();
    expect(storage.getItem('kds.pair')).toBeNull();
  });
});
