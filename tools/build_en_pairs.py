#!/usr/bin/env python3
"""Build the binary English word-pair table, en_pairs.bin (engine/PairFile.kt reads it).

en_bigrams.txt holds 100,000 pairs counted over Tatoeba sentences. That covers about four
in ten of the word pairs actually typed, and the decoder's context boost can only help where
the pair is in the table. This blends in the C4 web counts .github/workflows/web_ngrams.yml
publishes on the `assets/en-web-ngrams` branch (bigrams.tsv.gz, about 2.8 million pairs over
the shipped vocabulary) and keeps the strongest --pairs of them.

Blend. As tools/build_en_lexicon.py does for triples: Tatoeba counts are scaled to
TATOEBA_SHARE of the web total and added, so conversational pairs keep their weight. No
per-context limit: a limit of 12 followers was tried and dropped `i agree` (the web ranks it
past the 60th follower of `i`), which cost more lines than the breadth gained.

Replay over 17 trace batches (lines the user corrected, retyped, re-swiped or picked
mid-sentence, n=989; lines with a true label, n=3,774):

    pairs      corrected lines   true labels
    100,000        29.4%            65.7%      (en_bigrams.txt)
    800,000        34.1%            66.5%
    1,000,000      35.8%            67.0%      (shipped)
    1,500,000      36.2%            67.1%
    2,830,000      36.4%            67.1%      (all of them)

Fragments. Web text cut at apostrophes leaves pairs such as `it s`, `don t`, `you ll` (#105).
They make a lone `s` look like a word after `it`, so the short-slip rule, which reads this
table, stopped turning `for s` into `for a`. Pairs with a contraction piece (`ll`, `re`, `ve`)
or a one-letter word other than `a` and `i` are dropped from the web counts before the cut.
The decoder replay is level with and without them.

Log-probabilities (decoder rework step 3, #126). The default format, DPR2, also stores for
every pair `-ln P(next | prev)` in 1/16-nat steps and for every context `ln C(prev)`, the
blended count of everything that follows `prev` (all counted pairs over the vocabulary, before
the cut), so the log-linear score reads a real conditional probability and can turn a triple's
count into `P(w | u, v) = c(u, v, w) / c(u, v)` with `c(u, v) = C(u) * P(v | u)`. The boost
bytes are unchanged, so the legacy score reads the same numbers. `--format 1` writes the old
DPR1 file (byte-identical to the one shipped before step 3).

Format: see PairFile.kt. Words named by spelling. Offline and deterministic: the same inputs
give a byte-identical file.
"""

from __future__ import annotations

import argparse
import collections
import gzip
import logging
import math
import struct
import sys
from pathlib import Path

LOG = logging.getLogger("build_en_pairs")

TATOEBA_SHARE = 0.15
MAX_WORDS = 65_536

# Pieces web tokenizers leave when they cut a contraction at its apostrophe (`it s`, `don t`,
# `you ll`, `we re`, `i ve`), as words of their own.
CONTRACTION_PIECES = {"ll", "re", "ve"}
SINGLE_LETTER_WORDS = {"a", "i"}


def fragment(word: str) -> bool:
    """A contraction cut at its apostrophe, or a lone letter that is no word (`s`, `t`, `m`)."""
    return word in CONTRACTION_PIECES or (len(word) == 1 and word not in SINGLE_LETTER_WORDS)


LOGP_STEP = 1.0 / 16.0


def logp_byte(log_p: float) -> int:
    """`-ln P` in LOGP_STEP steps, 0..255 (BigramTable.quantizeLogP)."""
    return min(255, max(0, round(-log_p / LOGP_STEP)))


def utf(word: str) -> bytes:
    # DataOutput.writeUTF: a u16 byte length, then modified UTF-8. The word list holds no NUL
    # and nothing outside the basic plane, where modified and standard UTF-8 agree.
    data = word.encode("utf-8")
    if "\x00" in word or any(ord(c) > 0xFFFF for c in word) or len(data) > 0xFFFF:
        raise ValueError(f"cannot store {word!r}")
    return struct.pack(">H", len(data)) + data


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--assets", type=Path, default=Path("app/src/main/assets/dictionaries"))
    p.add_argument("--web", type=Path, required=True, help="checkout of the assets/en-web-ngrams branch")
    p.add_argument("--pairs", type=int, default=1_000_000)
    p.add_argument("--out", type=Path, help="defaults to <assets>/en_pairs.bin")
    p.add_argument("--text", type=Path, help="also write the table as prev<TAB>next<TAB>count lines")
    p.add_argument("--format", type=int, choices=(1, 2), default=2, help="DPR1 (boosts) or DPR2 (boosts and log-probabilities)")
    args = p.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")

    order = [line.split("\t")[0] for line in (args.assets / "en_wordlist.txt").read_text(encoding="utf-8").splitlines()]
    rank = {w: i for i, w in enumerate(order)}

    web: dict[tuple[str, str], int] = {}
    with gzip.open(args.web / "bigrams.tsv.gz", "rt", encoding="utf-8") as f:
        for line in f:
            a, b, c = line.rstrip("\n").split("\t")
            if a in rank and b in rank and not (fragment(a) or fragment(b)):
                web[(a, b)] = int(c)
    tat: dict[tuple[str, str], int] = {}
    for line in (args.assets / "en_bigrams.txt").read_text(encoding="utf-8").splitlines():
        a, b, c = line.split("\t")
        if a in rank and b in rank:
            tat[(a, b)] = int(c)
    scale = TATOEBA_SHARE * sum(web.values()) / sum(tat.values())
    merged: dict[tuple[str, str], float] = collections.defaultdict(float)
    for k, c in web.items():
        merged[k] += c
    for k, c in tat.items():
        merged[k] += c * scale
    # Every counted follower of a word, before the cut: the denominator of P(next | prev).
    totals: dict[str, float] = collections.defaultdict(float)
    for (a, _), c in merged.items():
        totals[a] += c
    kept = sorted(((c, a, b) for (a, b), c in merged.items()), key=lambda r: (-r[0], r[1], r[2]))[: args.pairs]
    kept = [(a, b, max(1, round(c))) for c, a, b in kept]
    LOG.info("%d tatoeba + %d web -> %d kept", len(tat), len(web), len(kept))
    if args.text:
        args.text.write_text("".join(f"{a}\t{b}\t{c}\n" for a, b, c in kept), encoding="utf-8")

    # Words numbered in word-list order, so of two spellings that fold to one trie word the
    # more frequent comes first, which is the one PairFile keeps.
    words = sorted({w for a, b, _ in kept for w in (a, b)}, key=rank.__getitem__)
    if len(words) > MAX_WORDS:
        raise SystemExit(f"{len(words)} words do not fit 16 bits")
    index = {w: i for i, w in enumerate(words)}
    contexts: dict[int, list[tuple[int, int]]] = collections.defaultdict(list)
    for a, b, c in kept:
        contexts[index[a]].append((index[b], c))

    out = bytearray(b"DPR1" if args.format == 1 else b"DPR2")
    out += struct.pack(">i", len(words))
    for w in words:
        out += utf(w)
    out += struct.pack(">i", len(contexts))
    for prev in sorted(contexts):
        followers = sorted(contexts[prev])
        log_max = math.log(1.0 + max(c for _, c in followers))
        out += struct.pack(">HH", prev, len(followers) - 1)
        if args.format == 2:
            log_total = math.log(totals[words[prev]])
            out += struct.pack(">f", log_total)
        for nxt, c in followers:
            boost = min(255, max(1, int(255.0 * math.log(1.0 + c) / log_max)))
            if args.format == 1:
                out += struct.pack(">HB", nxt, boost)
            else:
                out += struct.pack(">HBB", nxt, boost, logp_byte(math.log(c) - log_total))
    target = args.out or args.assets / "en_pairs.bin"
    target.write_bytes(bytes(out))
    LOG.info("%s: %d words, %d contexts, %d pairs, %d bytes", target, len(words), len(contexts), len(kept), len(out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
