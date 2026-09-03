import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';
import { handleSummaryFor, randomUuid, requireConfirmedTarget, standardOptions, targetUrl } from '../lib/config.js';
import { staffHeaders, staffSession } from '../lib/staff.js';

const unexpectedStatus = new Rate('unexpected_status');
requireConfirmedTarget();
export const options = standardOptions('agent-read');
export const handleSummary = handleSummaryFor('agent-read');

export default function () {
  staffSession();
  const viewKey = __ENV.STAFF_VIEW_KEY || 'pending';
  const queue = http.get(`${targetUrl}/api/v1/agent/views/${viewKey}/tickets?limit=50`, {
    headers: staffHeaders(),
    tags: { name: 'agent_view_tickets' },
  });
  unexpectedStatus.add(queue.status !== 200);
  if (!check(queue, { 'agent queue returns tickets': (response) => response.status === 200 && response.json('items') !== undefined })) return;

  const items = queue.json('items');
  if (!check(items, { 'agent queue is non-empty': (value) => Array.isArray(value) && value.length > 0 })) return;
  const ticket = items[__ITER % items.length];
  const detail = http.get(`${targetUrl}/api/v1/agent/tickets/${ticket.ticketNumber}`, {
    headers: staffHeaders({
      'X-Interaction-Id': randomUuid(),
      'X-Deskseed-Read-Intent': 'NAVIGATION',
    }),
    tags: { name: 'agent_ticket_detail' },
  });
  unexpectedStatus.add(detail.status !== 200);
  check(detail, { 'agent ticket detail succeeds': (response) => response.status === 200 });

  const search = http.post(
    `${targetUrl}/api/v1/agent/search`,
    JSON.stringify({
      query: __ENV.STAFF_SEARCH_QUERY || 'loadtest',
      filters: {},
      sort: 'score:desc,ticketNumber:desc',
      limit: 25,
    }),
    {
      headers: staffHeaders({ 'Content-Type': 'application/json' }),
      tags: { name: 'agent_search' },
    },
  );
  unexpectedStatus.add(search.status !== 200);
  check(search, { 'agent search succeeds': (response) => response.status === 200 });
}
