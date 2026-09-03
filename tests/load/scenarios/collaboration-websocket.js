import { collaborationWebSocketFlow } from '../flows/collaboration-websocket.js';
import { handleSummaryFor, requireConfirmedTarget, websocketOptions } from '../lib/config.js';

requireConfirmedTarget();
export const options = websocketOptions();
export const handleSummary = handleSummaryFor('collaboration-websocket');
export default collaborationWebSocketFlow;
