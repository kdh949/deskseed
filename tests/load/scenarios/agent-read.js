import { agentReadFlow } from '../flows/agent-read.js';
import { handleSummaryFor, requireConfirmedTarget, standardOptions } from '../lib/config.js';

requireConfirmedTarget();
export const options = standardOptions('agent-read');
export const handleSummary = handleSummaryFor('agent-read');
export default agentReadFlow;
