import { render } from 'preact';
import { ClaimApp } from './guest/ClaimApp';
import { TableApp } from './guest/TableApp';
import { KdsApp } from './kds/KdsApp';
import './styles.css';

function App() {
  const path = window.location.pathname;
  if (path === '/kds' || path.startsWith('/kds/')) {
    return <KdsApp />;
  }
  const table = /^\/t\/([A-Za-z0-9_-]{1,128})\/?$/.exec(path);
  if (table?.[1]) {
    return <TableApp qr={table[1]} />;
  }
  if (path === '/c' || path === '/c/') {
    return <ClaimApp fragment={window.location.hash} />;
  }
  return <main class="narrow"><h1>Not found</h1></main>;
}

render(<App />, document.getElementById('app')!);

// Offline app shell only on the HTTPS cloud origin. The LAN hub is plain HTTP, where service workers cannot run.
if ('serviceWorker' in navigator && window.location.protocol === 'https:') {
  void navigator.serviceWorker.register('/sw.js', { scope: '/' });
}
