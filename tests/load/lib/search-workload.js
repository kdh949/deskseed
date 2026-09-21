// Pure workload selection: no expected result sets, counts, or ranking assertions.
export const queryClasses = ['ticket-number', 'requester', 'phrase', 'topic', 'common', 'short', 'internal', 'absent'];

function requireCondition(condition, message) {
  if (!condition) throw new Error(message);
}

export function createWorkload(corpus, seed = 20260919) {
  requireCondition(corpus?.version === 1 && Array.isArray(corpus.groups), 'Search corpus must use version 1 and groups');
  requireCondition(Number.isInteger(seed) && seed >= 0 && seed <= 0xffffffff, 'SEARCH_SEED must be a uint32');
  requireCondition(typeof corpus.datasetId === 'string' && /^[a-zA-Z0-9._-]{1,100}$/.test(corpus.datasetId), 'Invalid corpus datasetId');
  const seenClasses = new Set();
  const seenQueries = new Set();
  const groups = corpus.groups.map((group) => {
    requireCondition(queryClasses.includes(group.queryClass) && !seenClasses.has(group.queryClass), 'Invalid or repeated search queryClass');
    seenClasses.add(group.queryClass);
    requireCondition(Number.isInteger(group.weight) && group.weight > 0 && group.weight <= 100, 'Search group weight must be 1..100');
    requireCondition(Array.isArray(group.queries) && group.queries.length >= Math.ceil(group.weight / 5), 'Each query must have at most 5% of workload weight');
    const queries = group.queries.map((query) => {
      requireCondition(typeof query === 'string' && query.trim().length > 0 && query.length <= 500 && !/[\x00-\x1f\x7f]/.test(query), 'Invalid search query');
      const normalized = query.trim().toLowerCase();
      requireCondition(!seenQueries.has(normalized), 'Duplicate search query in corpus');
      seenQueries.add(normalized);
      return query;
    });
    return { ...group, queries: shuffle(queries, seed ^ hash(group.queryClass)) };
  });
  requireCondition(groups.length === queryClasses.length, 'All eight search groups are required');
  requireCondition(groups.reduce((sum, group) => sum + group.weight, 0) === 100, 'Search weights must sum to 100');
  requireCondition(seenQueries.size >= 80 && seenQueries.size <= 10000, 'Corpus must contain 80..10000 distinct queries');
  const slots = shuffle(groups.flatMap((group, index) => Array(group.weight).fill(index)), seed);
  const offsets = groups.map(() => 0);
  const schedule = slots.map((index) => ({ index, ordinal: offsets[index]++ }));
  return {
    datasetId: corpus.datasetId,
    seed,
    groups: groups.map(({ queryClass, weight, queries }) => ({ queryClass, weight, size: queries.length })),
    select(iteration) {
      requireCondition(Number.isSafeInteger(iteration) && iteration >= 0, 'Iteration must be a nonnegative safe integer');
      const slot = schedule[iteration % 100];
      const group = groups[slot.index];
      const occurrence = Math.floor(iteration / 100) * group.weight + slot.ordinal;
      const queryIndex = occurrence % group.queries.length;
      return { query: group.queries[queryIndex], queryClass: group.queryClass, caseIndex: queryIndex };
    },
  };
}

export function durationSeconds(value) {
  const match = /^(\d+)(ms|s|m|h)$/.exec(value);
  requireCondition(match !== null, 'Duration must be an integer with ms, s, m, or h suffix');
  const seconds = Number(match[1]) * { ms: 0.001, s: 1, m: 60, h: 3600 }[match[2]];
  requireCondition(seconds > 0 && seconds <= 86400, 'Duration must be positive and at most 24 hours');
  return seconds;
}

function hash(text) {
  let value = 2166136261;
  for (let i = 0; i < text.length; i++) value = Math.imul(value ^ text.charCodeAt(i), 16777619);
  return value >>> 0;
}

function shuffle(values, seed) {
  const result = [...values];
  let state = seed >>> 0;
  for (let i = result.length - 1; i > 0; i--) {
    state = (Math.imul(state, 1664525) + 1013904223) >>> 0;
    const j = Math.floor((state / 4294967296) * (i + 1));
    [result[i], result[j]] = [result[j], result[i]];
  }
  return result;
}
