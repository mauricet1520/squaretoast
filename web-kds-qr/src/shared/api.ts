// Same-origin JSON calls to /api/v1 (the cloud backend, or the LAN hub when the KDS is served by it).

export const API = '/api/v1';

export class ApiError extends Error {
  constructor(readonly status: number, readonly code: string | null) {
    super(`HTTP ${status}${code ? ' ' + code : ''}`);
  }
}

export async function call<T>(path: string, init: { method?: string; body?: unknown; token?: string | null } = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (init.body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  if (init.token) {
    headers.Authorization = `Bearer ${init.token}`;
  }
  const r = await fetch(API + path, {
    method: init.method ?? (init.body === undefined ? 'GET' : 'POST'),
    headers,
    body: init.body === undefined ? null : JSON.stringify(init.body),
    credentials: 'omit',
    referrerPolicy: 'no-referrer',
  });
  const text = await r.text();
  if (!r.ok) {
    let code: string | null = null;
    try {
      const parsed = JSON.parse(text) as { error?: unknown };
      code = typeof parsed.error === 'string' ? parsed.error : null;
    } catch {
      // no JSON body
    }
    throw new ApiError(r.status, code);
  }
  return (text === '' ? undefined : JSON.parse(text)) as T;
}

/** A browser-local store that never throws (private windows can refuse storage). */
export function store(kind: 'local' | 'session') {
  const get = () => (kind === 'local' ? window.localStorage : window.sessionStorage);
  return {
    getItem(k: string): string | null {
      try {
        return get().getItem(k);
      } catch {
        return null;
      }
    },
    setItem(k: string, v: string): void {
      try {
        get().setItem(k, v);
      } catch {
        // storage unavailable; the value lives only for this page
      }
    },
    removeItem(k: string): void {
      try {
        get().removeItem(k);
      } catch {
        // ignore
      }
    },
  };
}
