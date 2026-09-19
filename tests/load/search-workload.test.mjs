import test from 'node:test';
import assert from 'node:assert/strict';
import { createWorkload, durationSeconds } from './lib/search-workload.js';

export function fixtureCorpus() {
  return { version: 1, datasetId: 'synthetic-test', groups: [
    ['ticket-number', 10], ['requester', 10], ['phrase', 20], ['topic', 20],
    ['common', 15], ['short', 10], ['internal', 10], ['absent', 5],
  ].map(([queryClass, weight]) => ({ queryClass, weight, queries: Array.from({ length: 20 }, (_, i) => `${queryClass}-synthetic-${i}`) })) };
}

test('fixed weights and broad coverage hold over complete cycles', () => {
  const corpus = fixtureCorpus();
  const workload = createWorkload(corpus);
  const counts = {}, used = new Set();
  for (let i = 0; i < 10000; i++) {
    const input = workload.select(i);
    counts[input.queryClass] = (counts[input.queryClass] || 0) + 1;
    used.add(input.query);
  }
  for (const group of corpus.groups) assert.equal(counts[group.queryClass], group.weight * 100);
  assert.equal(used.size, 160);
});

test('same seed survives VU interleaving and cycles all values before reuse', () => {
  const corpus = fixtureCorpus(), a = createWorkload(corpus, 77), b = createWorkload(corpus, 77);
  const seen = new Map();
  for (let vu = 0; vu < 7; vu++) for (let i = vu; i < 1000; i += 7) assert.deepEqual(a.select(i), b.select(i));
  for (let i = 0; i < 1000; i++) {
    const input = a.select(i), list = seen.get(input.queryClass) || [];
    if (list.length < 20) list.push(input.query);
    seen.set(input.queryClass, list);
  }
  for (const list of seen.values()) assert.equal(new Set(list).size, 20);
  assert.notDeepEqual(Array.from({ length: 30 }, (_, i) => a.select(i)), Array.from({ length: 30 }, (_, i) => createWorkload(corpus, 88).select(i)));
});

test('invalid or privacy-unsafe corpus is rejected without echoing content', () => {
  for (const change of [
    (c) => { c.groups[0].weight = 11; },
    (c) => { c.groups[0].queries = ['private-value']; },
    (c) => { c.groups[0].queries[0] = 'private-value\ncontrol'; },
    (c) => { c.groups[0].queries[0] = c.groups[1].queries[0]; },
    (c) => { c.groups[0].queryClass = 'secret-email@example.invalid'; },
  ]) {
    const corpus = fixtureCorpus(); change(corpus);
    assert.throws(() => createWorkload(corpus), (error) => !error.message.includes('private-value') && !error.message.includes('secret-email'));
  }
  assert.throws(() => createWorkload(fixtureCorpus(), NaN));
});

test('duration parsing rejects ambiguous or unbounded run windows', () => {
  assert.equal(durationSeconds('3m'), 180);
  assert.equal(durationSeconds('500ms'), 0.5);
  for (const value of ['0s', '300', '-1s', '25h', '3mgarbage']) assert.throws(() => durationSeconds(value));
});
