import { agentReadFlow } from '../flows/agent-read.js';
import { collaborationWebSocketFlow } from '../flows/collaboration-websocket.js';
import { customerAuthLimiterFlow } from '../flows/customer-auth-limiter.js';
import { publicRequestFlow } from '../flows/public-request.js';
import { handleSummaryFor, mixedOptions, requireConfirmedTarget } from '../lib/config.js';

const flowNames = ['agent-read', 'public-request', 'customer-auth-limiter', 'collaboration-websocket'];

requireConfirmedTarget({ writes: true });
export const options = mixedOptions();
export const handleSummary = handleSummaryFor('mixed-workload', flowNames);

export function agentRead() {
  agentReadFlow();
}

export function publicRequest() {
  publicRequestFlow();
}

export function customerAuthLimiter() {
  customerAuthLimiterFlow();
}

export function collaborationWebSocket() {
  collaborationWebSocketFlow();
}
