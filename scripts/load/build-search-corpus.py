#!/usr/bin/env python3
"""Build diverse k6 inputs from an existing synthetic Deskseed COPY fixture.

Reads files only. Does not connect to a database or calculate expected search results.
"""
import argparse
from collections import Counter
import gzip
import hashlib
import json
import os
from pathlib import Path
import random
import re

GROUPS = [('ticket-number', 10), ('requester', 10), ('phrase', 20), ('topic', 20),
          ('common', 15), ('short', 10), ('internal', 10), ('absent', 5)]
TOPICS = ('로그인', '세션', '비밀번호', 'SSO', '인증', '회원', '권한', '구독', '청구', '요금제',
          '결제', '환불', '주문', '취소', '배송', '출고', '반품', '교환', '재고', '상품', '쿠폰',
          '프로모션', '알림', '메시지', '메일', '보고서', '내보내기', 'CSV', 'API', '웹훅',
          '외부 연동', '개인정보', '보안', '데이터 이전', '중복', '모바일', '캐시', '검색',
          '업로드', '다운로드', '첨부', '이메일', '계정', '정산', '포인트', '할인', '운송장')


def copy_text(value):
    if value == r'\N':
        return ''
    return re.sub(r'\\([\\tnr])', lambda m: {'\\': '\\', 't': '\t', 'n': '\n', 'r': '\r'}[m[1]], value)


def sample_table(folder, manifest, table, fields, size, seed):
    columns = manifest['tables'][table]['columns']
    positions = [columns.index(field) for field in fields]
    rng = random.Random(seed)
    reservoirs, totals = {}, Counter()
    with gzip.open(folder / f'{table}.tsv.gz', 'rt', encoding='utf-8') as stream:
        for line in stream:
            values = line.rstrip('\n').split('\t')
            row = [copy_text(values[index]) for index in positions]
            key = row[0] if table == 'ticket_comments' else 'all'
            totals[key] += 1
            reservoir = reservoirs.setdefault(key, [])
            if len(reservoir) < size:
                reservoir.append(row)
            else:
                index = rng.randrange(totals[key])
                if index < size:
                    reservoir[index] = row
    return reservoirs, dict(totals)


def text_candidates(texts):
    words, phrases = Counter(), set()
    for text in texts:
        tokens = list(re.finditer(r'[가-힣A-Za-z0-9]+', text))
        words.update(set(token[0] for token in tokens if 2 <= len(token[0]) <= 16))
        for first, second in zip(tokens, tokens[1:]):
            phrase = text[first.start():second.end()]
            if 3 <= len(phrase) <= 60 and not re.search(r'[\x00-\x1f\x7f]', phrase):
                phrases.add(phrase)
    return words, sorted(phrases)


def build(folder, seed, per_group, sample_size):
    manifest_bytes = (folder / 'manifest.json').read_bytes()
    manifest = json.loads(manifest_bytes)
    if manifest.get('synthetic') is not True:
        raise ValueError('Only explicitly synthetic fixture manifests are supported')
    specs = [('tickets', ['ticket_number', 'subject']),
             ('ticket_comments', ['visibility', 'body']),
             ('customers', ['name', 'email_normalized']),
             ('support_groups', ['name']), ('staff_accounts', ['display_name'])]
    sampled, scanned = {}, {}
    for index, (table, fields) in enumerate(specs):
        sampled[table], scanned[table] = sample_table(folder, manifest, table, fields, sample_size, seed + index)
        print(f'Sampled {table}: {sum(scanned[table].values())} source rows', flush=True)
    tickets = sampled['tickets']['all']
    public_texts = [row[1] for row in sampled['ticket_comments'].get('PUBLIC', [])]
    internal_texts = [row[1] for row in sampled['ticket_comments'].get('INTERNAL', [])]
    titles = [row[1] for row in tickets]
    texts = titles + public_texts + internal_texts
    words, _ = text_candidates(texts)
    _, public_phrases = text_candidates(titles + public_texts)
    _, internal_phrases = text_candidates(internal_texts)
    labels = [row[0] for table in ('support_groups', 'staff_accounts') for row in sampled[table].get('all', [])]
    topics = [topic for topic in TOPICS if any(topic in text for text in texts)]
    pools = {
        'ticket-number': [row[0] for row in tickets],
        'requester': [value for row in sampled['customers']['all'] for value in row],
        'phrase': public_phrases + labels,
        'topic': topics,
        'common': [word for word, count in words.most_common() if count >= 2 and len(word) >= 3],
        'short': [word for word in sorted(words) if len(word) == 2],
        'internal': internal_phrases,
        'absent': [f'zzdeskseednomatch{seed:x}{index:04x}zz' for index in range(per_group)],
    }
    used = set()
    selected = {}
    # Reserve actual topic terms before drawing short/common phrases.
    for index, name in enumerate(['topic', 'ticket-number', 'requester', 'phrase', 'common', 'short', 'internal', 'absent']):
        candidates = sorted(set(pools[name]))
        random.Random(seed + 100 + index).shuffle(candidates)
        selected[name] = []
        for query in candidates:
            normalized = query.strip().lower()
            if not normalized or normalized in used or len(query) > 500 or re.search(r'[\x00-\x1f\x7f]', query):
                continue
            used.add(normalized)
            selected[name].append(query)
            if len(selected[name]) == per_group:
                break
        if len(selected[name]) < per_group:
            raise ValueError(f'Insufficient distinct candidates for {name}; increase sample size or use a larger fixture')
    manifest_hash = hashlib.sha256(manifest_bytes).hexdigest()
    return {
        'version': 1, 'datasetId': f'deskseed-{manifest_hash[:16]}', 'seed': seed,
        'sourceManifestSha256': manifest_hash, 'sourceCommit': manifest.get('source_commit'),
        'sampleSizePerTableOrVisibility': sample_size, 'scannedRows': scanned,
        'note': 'Inputs derived from synthetic source text. Absent terms are candidates, not a database-verified oracle. No expected results or rank.',
        'groups': [{'queryClass': name, 'weight': weight, 'queries': selected[name]} for name, weight in GROUPS],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--seed', type=int, default=20260919)
    parser.add_argument('--per-group', type=int, default=20)
    parser.add_argument('--sample-size', type=int, default=4000)
    args = parser.parse_args()
    if not 10 <= args.per_group <= 100 or not 100 <= args.sample_size <= 20000 or not 0 <= args.seed <= 0xffffffff:
        parser.error('per-group: 10..100; sample-size: 100..20000; seed: uint32')
    if args.output.exists():
        parser.error('Output already exists; choose a new file to preserve the previous corpus')
    corpus = build(args.data_dir, args.seed, args.per_group, args.sample_size)
    args.output.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    descriptor = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w', encoding='utf-8') as stream:
        json.dump(corpus, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
    print(f'Created {sum(len(group["queries"]) for group in corpus["groups"])} inputs in {len(corpus["groups"])} groups; no database calls')


if __name__ == '__main__':
    main()
