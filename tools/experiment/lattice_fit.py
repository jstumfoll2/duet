"""Fit LatticeDecoder's word-break score (engine/LatticeDecoder.kt) from replay rows.

    ./gradlew -p tools/replay replay --args="latticedata --score loglinear --taps --speed TRACES..." > rows.tsv
    python3 tools/experiment/lattice_fit.py rows.tsv

A row is one place a word may end: a real break (two typed words, the space left out) or a
lift inside one real word. Two words win when

    EVIDENCE * (ln head + ln rest - ln one) + BREAK + GAP * g
        + [tap before] (PREV_TAP + PREV_TAP_GAP * g) + [tap after] (NEXT_TAP + NEXT_TAP_GAP * g) > 0

with g = ln(1 + gap ms). Prints the errors of each half held out (A = files before 2026-10-22,
fitted on the other), then the weights fitted on everything in the order `--latW` and
`LatticeDecoder.setWeights` take them. Needs numpy only.
"""
import math
import sys

import numpy as np

NAMES = ['EVIDENCE', 'BREAK', 'GAP', 'PREV_TAP', 'PREV_TAP_GAP', 'NEXT_TAP', 'NEXT_TAP_GAP']
FLOOR = 1e-30


def half(f):
    return 'A' if f[:10] < '2026-10-22' else 'B'


def load(path):
    rows = []
    for line in open(path):
        c = line.rstrip('\n').split('\t')
        if len(c) < 16:
            continue
        rows.append(dict(half=half(c[0]), want=c[2] == 'split', gap=float(c[7]), prev=c[8] == 'true',
                         nxt=c[9] == 'true', head=float(c[11]), rest=float(c[13]), one=float(c[15])))
    return rows


def lns(x):
    return min(0.0, math.log(max(x, FLOOR)))


def feats(r):
    g = math.log1p(max(r['gap'], 0))
    ev = lns(r['head']) + lns(r['rest']) - lns(r['one'])
    p, n = float(r['prev']), float(r['nxt'])
    return [ev, 1.0, g, p, p * g, n, n * g]


def usable(r):
    # Rows the model is asked about: both words read as something, and so does the one word.
    return r['head'] > 0 and r['rest'] > 0 and r['one'] > 0


def fit(rows, l2=1e-2):
    x = np.array([feats(r) for r in rows])
    y = np.array([1.0 if r['want'] else 0.0 for r in rows])
    w = np.zeros(x.shape[1])
    for _ in range(200):
        p = 1 / (1 + np.exp(-np.clip(x @ w, -30, 30)))
        grad = x.T @ (p - y) + l2 * w
        hess = (x.T * (p * (1 - p))) @ x + l2 * np.eye(len(w))
        w -= np.linalg.solve(hess, grad)
    return w


def decide(r, w):
    if r['head'] <= 0 or r['rest'] <= 0:
        return False
    if r['one'] <= 0:
        return True
    return float(np.dot(feats(r), w)) > 0


def report(name, rows, f):
    out = []
    for want in (True, False):
        sub = [r for r in rows if r['want'] == want]
        bad = sum(1 for r in sub if f(r) != want)
        out.append(f"{'break' if want else 'no break'} wrong {bad}/{len(sub)} ({100 * bad / max(len(sub), 1):.1f}%)")
    print(f"{name:12s} " + '  '.join(out))


def main():
    rows = load(sys.argv[1])
    for test in ('A', 'B'):
        w = fit([r for r in rows if r['half'] != test and usable(r)])
        held = [r for r in rows if r['half'] == test]
        print(f"half {test} held out")
        report('  never', held, lambda r: False)
        report('  model', held, lambda r: decide(r, w))
        print('  --latW ' + ','.join(f"{x:.3f}" for x in w))
    w = fit([r for r in rows if usable(r)])
    report('all, in-sample', rows, lambda r: decide(r, w))
    print('fitted on all rows: ' + ', '.join(f"{n} = {x:.3f}f" for n, x in zip(NAMES, w)))
    print('--latW ' + ','.join(f"{x:.3f}" for x in w))


if __name__ == '__main__':
    main()
