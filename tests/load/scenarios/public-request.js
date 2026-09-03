import { publicRequestFlow } from '../flows/public-request.js';
import { handleSummaryFor, requireConfirmedTarget, standardOptions } from '../lib/config.js';

requireConfirmedTarget({ writes: true });
export const options = standardOptions('public-request');
export const handleSummary = handleSummaryFor('public-request');
export default publicRequestFlow;
