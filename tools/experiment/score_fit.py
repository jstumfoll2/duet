"""Refit the candidate score's weights on replayed top-50 lists (issue #68).

    ./gradlew -p tools/replay replay --args="deepfeatures TRACES..." | grep '^{' > deep.jsonl
    python3 tools/experiment/score_fit.py deep.jsonl

Each line's deep list carries every candidate's ScoreTerms (natural logs, in Term order), its
fit d, source and the centre-to-centre length of its letters. A conditional logit (softmax over
the list, the label as the target) refits one weight per term, regularised toward today's
weights (all 1), on one half of the batches and scores top-1 on the other. Today's order is the
list order. Variants: a non-saturating robust swipe cost in place of the saturating geometric
term, and an unexplained-arc feature in place of the short-reading keep. Needs numpy only.
"""
import json
import math
import sys

import numpy as np

A_DATES = ('2026-10-02', '2026-10-06', '2026-10-08', '2026-10-12', '2026-10-13')
TERMS = ['freq', 'geo', 'bigram', 'trigram', 'personal', 'pair', 'path', 'interleave', 'held',
         'apostrophe', 'regroup', 'penalty']
GEO, INTERLEAVE = 1, 7


def usable(c):
    return c[1] > 0 and all(math.isfinite(v) for v in c[5])


def build(line, fx):
    cands = [c for c in line['c'] if usable(c)]
    x = np.array([fx(line, c) for c in cands], dtype=float)
    y = next((i for i, c in enumerate(cands) if c[0].lower() == line['label']), -1)
    return x, y


def fit(lines, fx, w0, l2):
    data = [(x, y) for x, y in (build(l, fx) for l in lines if not l['asis']) if y >= 0 and len(x) > 1]
    w0 = np.array(w0, dtype=float)
    w = w0.copy()
    for _ in range(60):
        g = l2 * (w - w0)
        h = l2 * np.eye(len(w))
        for x, y in data:
            s = x @ w
            p = np.exp(s - s.max())
            p /= p.sum()
            mu = p @ x
            g += mu - x[y]
            xc = x - mu
            h += (xc.T * p) @ xc
        step = np.linalg.solve(h, g)
        w -= step
        if np.abs(step).max() < 1e-6:
            break
    return w


def top1(lines, fx=None, w=None):
    hit = 0
    for line in lines:
        if line['asis']:
            hit += 1
            continue
        if fx is None:
            hit += bool(line['c']) and line['c'][0][0].lower() == line['label']
            continue
        x, y = build(line, fx)
        hit += y >= 0 and int(np.argmax(x @ w)) == y
    return 100.0 * hit / max(len(lines), 1)


def run(name, lines, fx, w0, l2=1.0):
    halves = {'A': [l for l in lines if l['f'].startswith(A_DATES)],
              'B': [l for l in lines if not l['f'].startswith(A_DATES)]}
    parts = []
    for test, train in (('A', 'B'), ('B', 'A')):
        w = fit(halves[train], fx, w0, l2)
        parts.append(f"{test}: {top1(halves[test]):.2f} -> {top1(halves[test], fx, w):.2f}")
    print(f"{name:44s} {'  '.join(parts)}")


def ledger(line, c):
    return c[5][:len(TERMS)]


def robust(sigma, eps):
    def fx(line, c):
        t = list(c[5][:len(TERMS)])
        d = c[2]
        if t[INTERLEAVE] == 0:
            t[GEO] = math.log((1 - eps) * math.exp(-d * d / (2 * sigma * sigma)) + eps)
        return t
    return fx


def with_arc(line, c):
    u = 0.0
    if line['taps'] == 0 and line['arcs'] and c[4] >= 0:
        u = math.log(min(1.0, max(c[4], 1e-3) / sum(line['arcs'])))
    return list(c[5][:len(TERMS)]) + [u]


def main():
    lines = [json.loads(l) for l in open(sys.argv[1])]
    ones = [1.0] * len(TERMS)
    run('ledger refit', lines, ledger, ones)
    for l2 in (10.0, 1e3, 1e4):
        run(f'ledger refit, l2 {l2:g}', lines, ledger, ones, l2)
    for sigma in (0.2, 0.3):
        for eps in (0.02, 0.1):
            run(f'robust swipe cost s={sigma} e={eps}', lines, robust(sigma, eps), ones)
    run('ledger + unexplained arc', lines, with_arc, ones + [0.0])


if __name__ == '__main__':
    main()
