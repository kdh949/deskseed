import { Counter, Rate } from 'k6/metrics';

export const unexpectedStatus = new Rate('unexpected_status');
export const expectedThrottles = new Counter('expected_throttles');
