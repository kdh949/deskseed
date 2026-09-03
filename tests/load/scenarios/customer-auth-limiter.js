import { customerAuthLimiterFlow } from '../flows/customer-auth-limiter.js';
import { authOptions, handleSummaryFor, requireConfirmedTarget } from '../lib/config.js';

requireConfirmedTarget();
export const options = authOptions();
export const handleSummary = handleSummaryFor('customer-auth-limiter');
export default customerAuthLimiterFlow;
