"""The two-thumb swipe encoder (decoder rework step 11, issue #134).

swipe_ctc.py's encoder reads one stroke at a time, resampled along its own length, so two
thumbs drawing at once reach it as two unrelated shapes and the decoder has to search for how
they interleave. This one reads the whole buffer: both thumbs sampled at the same FRAMES
instants, evenly in time, each as key proximities, its step and whether it is down. The same
graph as swipe_ctc.Encoder (two convolutions, one bidirectional GRU, CTC over blank + a..z +
apostrophe) on IN_DIM = 58 inputs. DuoFeatures.kt builds the same frames; DuoEncoder.kt (to
come) runs the exported file.

Training data, any mix of:
  - a one-finger swipe redrawn as two thumbs would type it (two_thumb): each key visit goes to
    the thumb on its side, visit times are drawn from two-thumb rhythm, a thumb with one visit
    between long waits taps it. The one-finger swipes are FUTO's MIT dataset (--futo, where
    Hugging Face is reachable: the CI job) or swipe_ctc's synthetic paths (--synth);
  - the same swipe left as one stroke, and the word as taps only;
  - real buffers (--traces FILE, the `replay duodata` rows). Those are personal traces: a
    model trained with them is for measuring on this machine and is never committed.

Usage:
  python duo_ctc.py train --out duo.kctc --synth --minutes 20
  python duo_ctc.py train --out duo.kctc --futo --minutes 270
  python duo_ctc.py eval --model duo.kctc --traces B.jsonl
"""
import argparse
import json
import math
import os
import random
import struct
import sys
import time

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import swipe_ctc as sc  # noqa: E402

FRAMES = 64               # DuoFeatures.FRAMES
PER_THUMB = 26 + 3        # proximities, step x, step y, down
IN_DIM = 2 * PER_THUMB
SIGMA_KW = 0.5
CLASSES = sc.CLASSES

LEFT = set("qwertasdfgzxcv")
MIDDLE = set("tgvbyh")
P_CROSS = 0.15


# ------------------------------------------------------------------ features

def features(strokes, keys):
    """strokes: [(thumb, (k, 3) array of x, y, t)] in start order; keys: (26, 2), nan where
    the layout has no such key. Returns (FRAMES, IN_DIM) float32. Mirrors DuoFeatures.of."""
    out = np.zeros((FRAMES, IN_DIM), dtype=np.float32)
    if not strokes:
        return out
    t0 = min(s[1][0, 2] for s in strokes)
    t1 = max(s[1][-1, 2] for s in strokes)
    step = max(t1 - t0, 1.0) / (FRAMES - 1)
    tf = t0 + np.arange(FRAMES) * step
    pos = np.zeros((2, FRAMES, 2), dtype=np.float64)
    down = np.zeros((2, FRAMES), dtype=bool)
    for thumb, p in strokes:
        a, b = p[0, 2], p[-1, 2]
        m = (tf >= a - step / 2) & (tf < b + step / 2)
        if not m.any():
            continue
        at = np.clip(tf[m], a, b)
        if b > a:
            pos[thumb, m, 0] = np.interp(at, p[:, 2], p[:, 0])
            pos[thumb, m, 1] = np.interp(at, p[:, 2], p[:, 1])
        else:
            pos[thumb, m] = p[0, :2]
        down[thumb, m] = True
    has = ~np.isnan(keys[:, 0])
    kc = np.where(has[:, None], keys, 0.0)
    for thumb in (0, 1):
        d = down[thumb]
        base = thumb * PER_THUMB
        d2 = ((pos[thumb][:, None, :] - kc[None, :, :]) ** 2).sum(-1)
        prox = np.exp(-d2 / (2 * SIGMA_KW * SIGMA_KW)) * has[None, :]
        out[:, base:base + 26] = prox * d[:, None]
        both = d[1:] & d[:-1]
        out[1:, base + 26] = (pos[thumb, 1:, 0] - pos[thumb, :-1, 0]) * both
        out[1:, base + 27] = (pos[thumb, 1:, 1] - pos[thumb, :-1, 1]) * both
        out[:, base + 28] = d
    return out


def key_array(cent):
    return np.array([cent.get(chr(ord("a") + k), (np.nan, np.nan)) for k in range(26)], dtype=np.float64)


# ------------------------------------------------------------------ two thumbs from one finger

def align(pts, keys, cent):
    """Monotone nearest-approach index per key (as futo_train.align)."""
    P, L = len(pts), len(keys)
    if L > P:
        return None
    kc = np.array([cent[k] for k in keys], dtype=np.float32)
    d = np.sqrt(((pts[None, :, :] - kc[:, None, :]) ** 2).sum(-1))
    cost = np.full((L, P), np.inf)
    back = np.zeros((L, P), dtype=np.int64)
    cost[0] = d[0]
    for i in range(1, L):
        prev = cost[i - 1]
        arg = np.zeros(P, dtype=np.int64)
        m = 0
        for j in range(P):
            if prev[j] <= prev[m]:
                m = j
            arg[j] = m
        cost[i] = prev[arg] + d[i]
        back[i] = arg
    idx = [int(np.argmin(cost[-1]))]
    for i in range(L - 1, 0, -1):
        idx.append(int(back[i][idx[-1]]))
    return idx[::-1]


def _visits(word):
    letters = [c for c in word if c != "'"]
    visits, count = [], []
    for c in letters:
        if visits and c == visits[-1]:
            count[-1] += 1
        else:
            visits.append(c)
            count.append(1)
    return visits, count


def _leg(p0, p1, shape, t0, t1, rnd):
    """Samples from p0 at t0 to p1 at t1, about every 8 ms, eased; along `shape` when given."""
    n = max(2, int((t1 - t0) / 8))
    f = np.linspace(0, 1, n + 1)[1:]
    e = f * f * (3 - 2 * f)
    if shape is not None and len(shape) >= 3:
        seg = np.sqrt(((shape[1:] - shape[:-1]) ** 2).sum(1))
        acc = np.concatenate([[0], np.cumsum(seg)])
        if acc[-1] > 1e-6:
            u = acc / acc[-1]
            x = np.interp(e, u, shape[:, 0])
            y = np.interp(e, u, shape[:, 1])
            # Slide the real shape so it starts and ends where this thumb is and goes.
            off0 = p0 - shape[0]
            off1 = p1 - shape[-1]
            xy = np.stack([x, y], 1) + off0[None] * (1 - e[:, None]) + off1[None] * e[:, None]
            return np.concatenate([xy, (t0 + f * (t1 - t0))[:, None]], 1)
    bow = rnd.gauss(0, 0.08) * np.hypot(*(p1 - p0))
    nrm = np.array([-(p1 - p0)[1], (p1 - p0)[0]]) / max(np.hypot(*(p1 - p0)), 1e-6)
    xy = p0[None] + e[:, None] * (p1 - p0)[None] + (np.sin(np.pi * e) * bow)[:, None] * nrm[None]
    return np.concatenate([xy, (t0 + f * (t1 - t0))[:, None]], 1)


def two_thumb(word, pts, cent, rnd, p_tap=1.0):
    """A one-finger swipe of `word` as two thumbs would type it: [(thumb, (k, 3) x y t)], or None.

    Rhythm from the developer's traces (2026-11-16): about 125 ms a letter, a tap down about
    70 ms, half as many strokes as letters. That is aggregate timing only; no trace is copied.
    """
    visits, count = _visits(word)
    if not visits:
        return None
    pts = np.asarray(pts, dtype=np.float64)
    idx = align(pts, visits, cent)
    if idx is None:
        return None
    side = []
    for v in visits:
        left = v in LEFT
        if v in MIDDLE and rnd.random() < P_CROSS:
            left = not left
        side.append(0 if left else 1)
    pos = [pts[i] + np.array([rnd.gauss(0, 0.06), rnd.gauss(0, 0.06)]) for i in idx]
    pace = rnd.lognormvariate(0, 0.25)
    tau = [0.0]
    for k in range(1, len(visits)):
        d = float(np.hypot(*(pos[k] - pos[k - 1])))
        if side[k] != side[k - 1]:
            gap = rnd.lognormvariate(math.log(105), 0.35)
        else:
            gap = rnd.lognormvariate(math.log(110 + 45 * d), 0.3)
        gap = gap * pace + 130 * (count[k - 1] - 1)
        t = tau[-1] + gap
        own = [j for j in range(k - 1) if side[j] == side[k]]
        if own:
            j = own[-1]
            t = max(t, tau[j] + 60 + 35 * float(np.hypot(*(pos[k] - pos[j]))))
        tau.append(t)
    strokes = []
    for thumb in (0, 1):
        mine = [k for k in range(len(visits)) if side[k] == thumb]
        if not mine:
            continue
        groups = [[mine[0]]]
        for a, b in zip(mine[:-1], mine[1:]):
            gap = tau[b] - tau[a]
            p_lift = 0.12 if b == a + 1 else min(0.9, 0.2 + gap / 500)
            if rnd.random() < p_lift * p_tap:
                groups.append([b])
            else:
                groups[-1].append(b)
        for gr in groups:
            if len(gr) == 1:
                k = gr[0]
                for rep in range(count[k]):
                    dur = min(160.0, max(15.0, rnd.gauss(72, 22)))
                    t = tau[k] + rep * rnd.uniform(110, 150)
                    p = pos[k] + np.array([rnd.gauss(0, 0.04), rnd.gauss(0, 0.04)])
                    strokes.append((thumb, np.array([[p[0], p[1], t - dur / 2], [p[0], p[1], t + dur / 2]])))
                continue
            path = [np.array([[pos[gr[0]][0], pos[gr[0]][1], tau[gr[0]] - rnd.uniform(15, 60)]])]
            for a, b in zip(gr[:-1], gr[1:]):
                gap = tau[b] - tau[a]
                dep = tau[a] + gap * rnd.uniform(0.0, 0.3)
                arr = tau[b] - gap * rnd.uniform(0.0, 0.2)
                shape = pts[idx[a]:idx[b] + 1] if b == a + 1 and idx[b] > idx[a] else None
                path.append(np.array([[pos[a][0], pos[a][1], dep]]))
                path.append(_leg(pos[a], pos[b], shape, dep, arr, rnd))
            last = pos[gr[-1]]
            prev = pos[gr[-2]]
            over = rnd.uniform(0.0, 0.2)
            path.append(np.array([[last[0] + over * (last - prev)[0] * 0.3, last[1] + over * (last - prev)[1] * 0.3,
                                   tau[gr[-1]] + rnd.uniform(15, 80)]]))
            p = np.concatenate(path, 0)
            p[:, 2] = np.maximum.accumulate(p[:, 2])
            strokes.append((thumb, p))
    strokes.sort(key=lambda s: s[1][0, 2])
    return strokes


def one_thumb(word, pts, rnd):
    """The swipe as it was drawn: one stroke, about 8 ms a sample scaled to the word's pace."""
    pts = np.asarray(pts, dtype=np.float64)
    n = len(pts)
    span = max(120.0, rnd.lognormvariate(math.log(135), 0.3) * max(len(word) - 1, 1))
    t = np.linspace(0, span, n)
    thumb = 0 if pts[0, 0] < 5.0 else 1
    return [(thumb, np.concatenate([pts, t[:, None]], 1))]


def synth_sample(row, rnd):
    """(features, classes, word) from a (word, one-finger path, key centres) row."""
    word, pts, cent = row
    r = rnd.random()
    strokes = None
    if r < 0.2 and len(word) >= 2:
        strokes = one_thumb(word, pts, rnd)
    elif r < 0.3:
        strokes = two_thumb(word, pts, cent, rnd, p_tap=50.0)   # every visit lifted: all taps
    if strokes is None:
        strokes = two_thumb(word, pts, cent, rnd)
    if strokes is None:
        return None
    return features(strokes, key_array(cent)), [sc.code(c) for c in word if c != "'"], word


# ------------------------------------------------------------------ real buffers (local only)

def load_traces(path):
    rows = []
    with open(path) as f:
        for line in f:
            r = json.loads(line)
            keys = np.array([k if k is not None else (np.nan, np.nan) for k in r["keys"]], dtype=np.float64)
            strokes = [(s["s"], np.array(s["p"], dtype=np.float64)) for s in r["strokes"]]
            rows.append((r["label"], strokes, keys, r))
    return rows


def trace_sample(row, rnd, augment):
    word, strokes, keys, _ = row
    if augment:
        off = np.array([rnd.gauss(0, 0.08), rnd.gauss(0, 0.08), 0.0])
        strokes = [(th, p + off + np.array([rnd.gauss(0, 0.04), rnd.gauss(0, 0.04), 0.0])) for th, p in strokes]
    label = [sc.code(c) for c in word if c != "'"]
    return features(strokes, keys), label, word


# ------------------------------------------------------------------ model

class Encoder(nn.Module):
    """swipe_ctc.Encoder's graph on IN_DIM inputs; DuoEncoder.kt runs the same."""

    def __init__(self, width=96, hidden=96):
        super().__init__()
        self.conv1 = nn.Conv1d(IN_DIM, width, 5, padding=2)
        self.conv2 = nn.Conv1d(width, width, 5, padding=2)
        self.gru = nn.GRU(width, hidden, batch_first=True, bidirectional=True)
        self.out = nn.Linear(2 * hidden, CLASSES)

    def forward(self, x):
        h = x.transpose(1, 2)
        h = F.relu(self.conv1(h))
        h = F.relu(self.conv2(h))
        h, _ = self.gru(h.transpose(1, 2))
        return F.log_softmax(self.out(h), -1)


NAMES = [
    "conv1.weight", "conv1.bias", "conv2.weight", "conv2.bias",
    "gru.weight_ih_l0", "gru.weight_hh_l0", "gru.bias_ih_l0", "gru.bias_hh_l0",
    "gru.weight_ih_l0_reverse", "gru.weight_hh_l0_reverse",
    "gru.bias_ih_l0_reverse", "gru.bias_hh_l0_reverse",
    "out.weight", "out.bias",
]


def export(model, path):
    """b'KCTC', version 2, tensor count, sigma, frames, then each tensor as ndim, dims, float32s."""
    sd = model.state_dict()
    with open(path, "wb") as f:
        f.write(b"KCTC")
        f.write(struct.pack("<ii", 2, len(NAMES)))
        f.write(struct.pack("<f", SIGMA_KW))
        f.write(struct.pack("<i", FRAMES))
        for n in NAMES:
            t = sd[n].detach().cpu().numpy().astype("<f4")
            f.write(struct.pack("<i", t.ndim))
            f.write(struct.pack(f"<{t.ndim}i", *t.shape))
            f.write(t.tobytes())


def load(path):
    with open(path, "rb") as f:
        assert f.read(4) == b"KCTC"
        version, count = struct.unpack("<ii", f.read(8))
        assert version == 2 and count == len(NAMES)
        f.read(8)
        tensors = {}
        for n in NAMES:
            nd = struct.unpack("<i", f.read(4))[0]
            dims = struct.unpack(f"<{nd}i", f.read(4 * nd))
            tensors[n] = torch.from_numpy(np.frombuffer(f.read(4 * int(np.prod(dims))), dtype="<f4").reshape(dims).copy())
    m = Encoder(tensors["conv1.weight"].shape[0], tensors["gru.weight_hh_l0"].shape[1])
    m.load_state_dict(tensors)
    return m


def greedy(lp):
    out, prev = [], 0
    for b in lp.argmax(-1).tolist():
        if b != prev and b != 0:
            out.append(b)
        prev = b
    return out


def evaluate(model, samples):
    """Greedy exact-match share over [(features, classes, word)]."""
    model.eval()
    ok = 0
    with torch.no_grad():
        for i in range(0, len(samples), 256):
            chunk = samples[i:i + 256]
            lp = model(torch.from_numpy(np.stack([s[0] for s in chunk])))
            for row, s in zip(lp, chunk):
                ok += greedy(row) == s[1]
    return ok / max(len(samples), 1)


# ------------------------------------------------------------------ train / eval

def synth_rows(words, rnd, n):
    rows = []
    for _ in range(n):
        w = words[min(int(rnd.paretovariate(1.1)) - 1, len(words) - 1)] if rnd.random() < 0.5 else rnd.choice(words)
        cent = sc.qwerty(rnd.choice([1.3, 1.5, 1.565]))
        rows.append((w, sc.synth_path(w, cent, rnd), cent))
    return rows


def futo_rows(limit):
    import futo_train as ft
    from huggingface_hub import hf_hub_download
    norm = ft.futo_centres()

    def conv(rows):
        return [(w, pts, ft.centres_kw(norm, aspect)) for w, pts, aspect in rows]
    train = ft.load_rows(hf_hub_download(ft.REPO, "train.jsonl", repo_type="dataset"), limit)
    test = ft.load_rows(hf_hub_download(ft.REPO, "test.jsonl", repo_type="dataset"), 3000, rnd_skip=0.8, seed=5)
    return conv(train), conv(test)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["train", "eval"])
    ap.add_argument("--out")
    ap.add_argument("--model")
    ap.add_argument("--init", help="start from this duo .kctc (our own earlier model only)")
    ap.add_argument("--synth", action="store_true", help="one-finger paths from swipe_ctc's generator")
    ap.add_argument("--futo", action="store_true", help="one-finger paths from FUTO's dataset (needs Hugging Face)")
    ap.add_argument("--traces", help="`replay duodata` rows to train or evaluate on (local only)")
    ap.add_argument("--heldout", help="`replay duodata` rows reported during training (local only)")
    ap.add_argument("--pTrace", type=float, default=0.5, help="share of a batch drawn from --traces")
    ap.add_argument("--rows", type=int, default=600_000)
    ap.add_argument("--minutes", type=float, default=20)
    ap.add_argument("--batch", type=int, default=128)
    ap.add_argument("--width", type=int, default=96)
    ap.add_argument("--hidden", type=int, default=96)
    ap.add_argument("--lr", type=float, default=3e-3)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--threads", type=int, default=os.cpu_count() or 1)
    a = ap.parse_args()
    torch.set_num_threads(a.threads)
    torch.manual_seed(a.seed)
    rnd = random.Random(a.seed)

    if a.cmd == "eval":
        model = load(a.model)
        rows = load_traces(a.traces)
        samples = [trace_sample(r, rnd, False) for r in rows]
        groups = {"all": samples}
        for s, r in zip(samples, rows):
            strokes = r[1]
            taps = sum(1 for _, p in strokes if len(p) == 2 and (p[0, :2] == p[1, :2]).all())
            kind = "taps" if taps == len(strokes) else "one swipe" if len(strokes) == 1 else "several strokes"
            groups.setdefault(kind, []).append(s)
        for k, v in groups.items():
            print(f"{k}: n={len(v)} greedy exact {evaluate(model, v):.3f}")
        return

    one_finger, test = [], []
    if a.futo:
        one_finger, test = futo_rows(a.rows)
    elif a.synth:
        words = sc.load_words()
        one_finger = synth_rows(words, rnd, 60_000)
        test = synth_rows(words, random.Random(77), 1500)
    traces = load_traces(a.traces) if a.traces else []
    held = [trace_sample(r, rnd, False) for r in load_traces(a.heldout)] if a.heldout else []
    test_rnd = random.Random(5)
    test_samples = [s for s in (synth_sample(r, test_rnd) for r in test) if s is not None]
    print(f"one-finger rows {len(one_finger)}, trace rows {len(traces)}, held-out synthetic {len(test_samples)}, held-out traces {len(held)}", flush=True)

    model = load(a.init) if a.init else Encoder(a.width, a.hidden)
    print(f"params: {sum(p.numel() for p in model.parameters())}", flush=True)
    opt = torch.optim.AdamW(model.parameters(), lr=a.lr, weight_decay=1e-4)
    ctc = nn.CTCLoss(blank=0, zero_infinity=True)
    p_trace = a.pTrace if (traces and one_finger) else (1.0 if traces else 0.0)
    budget = a.minutes * 60
    start = time.time()
    step = 0
    while True:
        frac = (time.time() - start) / budget
        if frac >= 1:
            break
        for g in opt.param_groups:
            g["lr"] = a.lr * 0.5 * (1 + np.cos(np.pi * min(frac, 1.0))) + 1e-5
        xs, ys, lens = [], [], []
        while len(xs) < a.batch:
            if rnd.random() < p_trace:
                s = trace_sample(rnd.choice(traces), rnd, True)
            else:
                s = synth_sample(rnd.choice(one_finger), rnd)
            if s is None or not s[1] or len(s[1]) > FRAMES // 2:
                continue
            xs.append(s[0])
            ys.extend(s[1])
            lens.append(len(s[1]))
        model.train()
        x = torch.from_numpy(np.stack(xs))
        lp = model(x).transpose(0, 1)
        loss = ctc(lp, torch.tensor(ys), torch.full((x.shape[0],), FRAMES, dtype=torch.long), torch.tensor(lens))
        opt.zero_grad()
        loss.backward()
        nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        opt.step()
        step += 1
        if step % 500 == 0:
            line = f"step {step} min {(time.time() - start) / 60:.0f} loss {loss.item():.3f}"
            if test_samples:
                line += f" synthetic {evaluate(model, test_samples[:600]):.3f}"
            if held:
                line += f" held-out traces {evaluate(model, held[:1500]):.3f}"
            print(line, flush=True)
            export(model, a.out)
    export(model, a.out)
    line = f"final step {step}:"
    if test_samples:
        line += f" synthetic greedy exact {evaluate(model, test_samples):.3f} (n={len(test_samples)})"
    if held:
        line += f" held-out traces {evaluate(model, held):.3f} (n={len(held)})"
    print(line, flush=True)
    print(f"wrote {a.out}")


if __name__ == "__main__":
    main()
