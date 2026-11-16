"""Fit BoundaryModel (engine/BoundaryModel.kt) from replay rows.

    ./gradlew -p tools/replay replay --args="splitdata TRACES..." > split.tsv
    ./gradlew -p tools/replay replay --args="rejoindata TRACES..." > rejoin.tsv
    python3 tools/experiment/boundary_fit.py split.tsv rejoin.tsv

Prints today's ratio rules against the model, each half held out (fitted on the other), then
the weights fitted on everything, which go into BoundaryModel. Each is given without and with
the language-model feature (the rows' last column, WordPredictor.splitLm; decoder rework step
9). The scores in the rows depend on the score in use, so fit the legacy and the log-linear
rows (`--score loglinear --taps --speed` on both commands) separately. Needs numpy only.
"""
import math
import sys

import numpy as np

# Halves by file date, as in the decoder rework: A = files before 2026-10-22, B = the rest.
NAMES = ['BIAS', 'EVIDENCE', 'PECK', 'PECK_GAP', 'REST_SWIPE', 'LM']
# The language-model feature is held within this many nats, so one unseen pair cannot decide.
LM_CAP = 8.0


def half(f):
    return 'A' if f[:10] < '2026-10-22' else 'B'


def load(split_path, rejoin_path):
    rows = []
    for line in open(split_path):
        c = line.rstrip('\n').split('\t')
        if len(c) < 24:
            continue
        rows.append(dict(src='peck', half=half(c[0]), want=c[2] == 'split', now=c[22] == 'true',
                         elig=c[23] == 'true', tap=float(c[14]), rest=float(c[18]), one=float(c[10]),
                         gap=float(c[7]), restsw=True, lm=float(c[24]) if len(c) > 24 else 0.0))
    for line in open(rejoin_path):
        c = line.rstrip('\n').split('\t')
        if len(c) < 17 or c[8] == '-':
            continue
        rows.append(dict(src='rejoin', half=half(c[0]), want=c[2] == 'split', now=c[14] == 'true',
                         elig=c[16] == 'true', tap=float(c[9]), rest=float(c[11]), one=float(c[13]),
                         gap=None, restsw=c[6] == 'false', lm=float(c[17]) if len(c) > 17 else 0.0))
    return rows


def feats(r, lm=False):
    tap = min(r['tap'], 1.0)  # KineticaConstants.PECK_SPLIT_TAP_CAP
    evidence = math.log(max(tap, 1e-6)) + math.log(max(r['rest'], 1e-6)) - math.log(max(r['one'], 1e-6))
    peck = r['gap'] is not None
    f = [1.0, evidence, 1.0 if peck else 0.0, math.log1p(max(r['gap'], 0)) if peck else 0.0,
         1.0 if r['restsw'] else 0.0]
    if lm:
        f.append(max(-LM_CAP, min(LM_CAP, r['lm'])))
    return f


def fit(rows, lm=False, l2=1e-2):
    x = np.array([feats(r, lm) for r in rows])
    y = np.array([1.0 if r['want'] else 0.0 for r in rows])
    w = np.zeros(x.shape[1])
    for _ in range(100):
        p = 1 / (1 + np.exp(-x @ w))
        grad = x.T @ (p - y) + l2 * w
        hess = (x.T * (p * (1 - p))) @ x + l2 * np.eye(len(w))
        w -= np.linalg.solve(hess, grad)
    return w


def decide(r, w):
    if not r['elig'] or r['tap'] <= 0 or r['rest'] <= 0:
        return False
    if r['one'] <= 0:
        return True
    return float(np.dot(feats(r, len(w) > 5), w)) > 0


def report(name, rows, f):
    parts, wrong = [], 0
    for src in ('peck', 'rejoin'):
        for want in (True, False):
            sub = [r for r in rows if r['src'] == src and r['want'] == want]
            bad = sum(1 for r in sub if f(r) != want)
            wrong += bad
            parts.append(f"{src}-{'split' if want else 'join'} wrong {bad}/{len(sub)}")
    print(f"{name:14s} {'  '.join(parts)}  total wrong {wrong}")


def main():
    rows = load(sys.argv[1], sys.argv[2])
    usable = lambda rs: [r for r in rs if r['elig'] and r['one'] > 0]
    for test in ('A', 'B'):
        train = usable([r for r in rows if r['half'] != test])
        w = fit(train)
        wl = fit(train, lm=True)
        held = [r for r in rows if r['half'] == test]
        print(f"half {test} held out")
        report('  ratios', held, lambda r: r['now'])
        report('  model', held, lambda r: decide(r, w))
        report('  model + lm', held, lambda r: decide(r, wl))
    for lm in (False, True):
        w = fit(usable(rows), lm)
        print(('with lm, ' if lm else 'without lm, ') + 'fitted on all rows: ' +
              ', '.join(f"{n} = {x:.3f}f" for n, x in zip(NAMES, w)))


if __name__ == '__main__':
    main()
