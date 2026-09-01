import http from 'k6/http';
import ws from 'k6/ws';
import { check } from 'k6';
import { Rate } from 'k6/metrics';
import { randomUuid, requireConfirmedTarget, targetUrl, websocketOptions } from '../lib/config.js';
import { staffHeaders, staffSession } from '../lib/staff.js';

const unexpectedStatus = new Rate('unexpected_status');
requireConfirmedTarget();
export const options = websocketOptions();

export default function () {
  staffSession();
  const queue = http.get(`${targetUrl}/api/v1/agent/views/${__ENV.STAFF_VIEW_KEY || 'pending'}/tickets?limit=50`, {
    headers: staffHeaders(),
    tags: { name: 'websocket_ticket_source' },
  });
  if (!check(queue, { 'WebSocket source queue has a ticket': (response) => response.status === 200 && response.json('items').length > 0 })) return;
  const ticketNumber = queue.json('items.0.ticketNumber');
  const jar = http.cookieJar();
  const cookies = jar.cookiesForURL(targetUrl);
  const sessionCookie = cookies.JSESSIONID?.[0];
  if (!check(sessionCookie, { 'staff session cookie exists': (value) => Boolean(value) })) return;

  const socketUrl = targetUrl.replace(/^http/, 'ws') + '/ws/agent/collaboration';
  const response = ws.connect(
    socketUrl,
    {
      headers: {
        Origin: __ENV.WEBSOCKET_ORIGIN || targetUrl,
        Cookie: `JSESSIONID=${sessionCookie}`,
        'X-Request-Id': `load-ws-${randomUuid()}`.slice(0, 100),
      },
      tags: { name: 'collaboration_websocket' },
    },
    (socket) => {
      socket.on('open', () => {
        socket.send(JSON.stringify({ version: 1, type: 'subscribe', ticketNumber }));
        socket.setInterval(() => socket.send(JSON.stringify({ version: 1, type: 'heartbeat' })), 1000);
        socket.setTimeout(() => socket.close(), Number(__ENV.WEBSOCKET_HOLD_MS || 5000));
      });
      socket.on('message', (payload) => {
        const message = JSON.parse(payload);
        if (message.type === 'error') unexpectedStatus.add(true);
        if (message.type === 'presence.snapshot') {
          check(message, { 'presence snapshot matches ticket': (value) => value.ticketNumber === ticketNumber });
        }
      });
      socket.on('error', () => unexpectedStatus.add(true));
    },
  );
  unexpectedStatus.add(response?.status !== 101);
  check(response, { 'collaboration WebSocket upgrades': (result) => result && result.status === 101 });
}
