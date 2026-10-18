#!/usr/bin/env python3
"""Word, word-pair and word-triple counts from English web text (C4), for the dictionary.

The bundled English counts come from film subtitles (FrequencyWords) and the word
pairs and triples from Tatoeba, whose short teaching sentences cover only about a
tenth of the contexts people type ("tom and mary" is its top triple). This counts
the same three tables over a slice of C4, the cleaned English Common Crawl corpus
published by AllenAI (ODC-BY 1.0, https://huggingface.co/datasets/allenai/c4), so
tools/build_en_lexicon.py can blend them in.

Counting is restricted to a candidate vocabulary (--vocab, one word per line): a
token outside it, or a sentence end, breaks the run, so a counted pair or triple
was adjacent inside one sentence, as the Tatoeba counters treat them. Unigrams are
counted for every token of the English word shape, so words outside the vocabulary
can still be judged by how often the web uses them.

Outputs in --out-dir, gzipped, tab-separated, most frequent first:
  unigrams.tsv.gz  word count   (count >= --min-unigram)
  bigrams.tsv.gz   w1 w2 count  (top --max-bigrams, count >= 3)
  trigrams.tsv.gz  w1 w2 w3 count (top --max-trigrams, count >= 3)

The development environment cannot reach Hugging Face, so this runs in
.github/workflows/web_ngrams.yml. Shards are processed in parallel; each worker
packs triples as one int64 (3 x 20-bit vocabulary ids) and reduces them with numpy.
"""

from __future__ import annotations

import argparse
import array
import collections
import gzip
import json
import logging
import multiprocessing as mp
import re
import sys
import urllib.request
from pathlib import Path

import numpy as np

LOG = logging.getLogger("web_ngrams")

C4_URL_TMPL = "https://huggingface.co/datasets/allenai/c4/resolve/main/en/c4-train.{i:05d}-of-01024.json.gz"

WORD_RE = re.compile(r"^[a-z]+(?:'[a-z]+)*$")
# Sentence ends and clause punctuation break a run; other punctuation is stripped.
SPLIT_RE = re.compile(r"[.!?;:()\[\]{}\"“”|/\\<>]+|\s[-–—]+\s")
TOKEN_STRIP_RE = re.compile(r"^[^a-z']+|[^a-z']+$")
APOSTROPHES = str.maketrans({"’": "'", "‘": "'", "`": "'"})

BITS = 20
MASK = (1 << BITS) - 1
FLUSH_EVERY = 10_000_000


def tokens(sentence: str):
    for raw in sentence.split():
        t = TOKEN_STRIP_RE.sub("", raw).strip("'")
        yield t


def reduce(keys: np.ndarray, counts: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    order = np.argsort(keys, kind="stable")
    k, c = keys[order], counts[order]
    if k.size == 0:
        return k, c
    starts = np.flatnonzero(np.r_[True, k[1:] != k[:-1]])
    return k[starts], np.add.reduceat(c, starts)


class Acc:
    """Packed n-gram keys, flushed to (unique keys, counts) every FLUSH_EVERY entries."""

    def __init__(self) -> None:
        self.buf = array.array("q")
        self.keys = np.zeros(0, dtype=np.int64)
        self.counts = np.zeros(0, dtype=np.int64)

    def add(self, key: int) -> None:
        self.buf.append(key)
        if len(self.buf) >= FLUSH_EVERY:
            self.flush()

    def flush(self) -> None:
        if not self.buf:
            return
        k, c = np.unique(np.frombuffer(self.buf, dtype=np.int64), return_counts=True)
        self.buf = array.array("q")
        self.keys, self.counts = reduce(np.concatenate([self.keys, k]), np.concatenate([self.counts, c.astype(np.int64)]))


def count_shard(args: tuple[int, dict[str, int], str]) -> tuple:
    shard, vocab, cache = args
    path = Path(cache) / f"c4-{shard:05d}.json.gz"
    if not path.exists():
        url = C4_URL_TMPL.format(i=shard)
        LOG.info("shard %d: downloading %s", shard, url)
        with urllib.request.urlopen(url, timeout=600) as r, open(path, "wb") as f:
            while True:
                b = r.read(1 << 20)
                if not b:
                    break
                f.write(b)
    uni: collections.Counter[str] = collections.Counter()
    bi, tri = Acc(), Acc()
    docs = words = 0
    with gzip.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            text = json.loads(line)["text"].lower().translate(APOSTROPHES)
            docs += 1
            for sentence in SPLIT_RE.split(text):
                a = b = -1
                for t in tokens(sentence):
                    if not t or not WORD_RE.match(t) or len(t) > 20:
                        a = b = -1
                        continue
                    words += 1
                    uni[t] += 1
                    c = vocab.get(t, -1)
                    if c < 0:
                        a = b = -1
                        continue
                    if b >= 0:
                        bi.add((b << BITS) | c)
                        if a >= 0:
                            tri.add((a << (2 * BITS)) | (b << BITS) | c)
                    a, b = b, c
    bi.flush()
    tri.flush()
    LOG.info("shard %d: %d docs, %d words, %d pairs, %d triples", shard, docs, words, bi.keys.size, tri.keys.size)
    # Drop the rarest before shipping back to the parent: a per-shard singleton is
    # mostly noise, and the parent keeps count >= 3 overall anyway.
    keep_b = bi.counts >= 2
    keep_t = tri.counts >= 2
    uni = collections.Counter({w: n for w, n in uni.items() if n >= 2})
    return docs, words, uni, bi.keys[keep_b], bi.counts[keep_b], tri.keys[keep_t], tri.counts[keep_t]


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--vocab", type=Path, required=True)
    p.add_argument("--shards", type=int, default=4)
    p.add_argument("--first-shard", type=int, default=0)
    p.add_argument("--workers", type=int, default=4)
    p.add_argument("--cache", type=Path, default=Path("c4"))
    p.add_argument("--out-dir", type=Path, required=True)
    p.add_argument("--min-unigram", type=int, default=20)
    p.add_argument("--max-bigrams", type=int, default=3_000_000)
    p.add_argument("--max-trigrams", type=int, default=4_000_000)
    args = p.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(processName)s %(message)s")

    words = sorted({w.strip() for w in args.vocab.read_text(encoding="utf-8").splitlines() if WORD_RE.match(w.strip())})
    if len(words) > MASK:
        raise SystemExit(f"vocabulary of {len(words)} does not fit {BITS} bits")
    vocab = {w: i for i, w in enumerate(words)}
    LOG.info("vocabulary: %d words", len(vocab))
    args.cache.mkdir(parents=True, exist_ok=True)
    args.out_dir.mkdir(parents=True, exist_ok=True)

    jobs = [(i, vocab, str(args.cache)) for i in range(args.first_shard, args.first_shard + args.shards)]
    uni: collections.Counter[str] = collections.Counter()
    bk, bc, tk, tc = [], [], [], []
    docs = total = 0
    with mp.get_context("fork").Pool(args.workers) as pool:
        for d, w, u, k2, c2, k3, c3 in pool.imap_unordered(count_shard, jobs):
            docs += d
            total += w
            uni.update(u)
            bk.append(k2), bc.append(c2), tk.append(k3), tc.append(c3)
    LOG.info("total: %d docs, %d words", docs, total)

    with gzip.open(args.out_dir / "unigrams.tsv.gz", "wt", encoding="utf-8") as f:
        for w, n in uni.most_common():
            if n < args.min_unigram:
                break
            f.write(f"{w}\t{n}\n")

    def write(name: str, keys: list, counts: list, order: int, cap: int) -> None:
        k, c = reduce(np.concatenate(keys), np.concatenate(counts))
        keep = c >= 3
        k, c = k[keep], c[keep]
        top = np.argsort(-c, kind="stable")[:cap]
        with gzip.open(args.out_dir / name, "wt", encoding="utf-8") as f:
            for i in top:
                key = int(k[i])
                ids = [(key >> (BITS * (order - 1 - j))) & MASK for j in range(order)]
                f.write("\t".join(words[x] for x in ids) + f"\t{int(c[i])}\n")
        LOG.info("%s: %d kept of %d with count >= 3", name, min(cap, k.size), k.size)

    write("bigrams.tsv.gz", bk, bc, 2, args.max_bigrams)
    write("trigrams.tsv.gz", tk, tc, 3, args.max_trigrams)
    (args.out_dir / "stats.json").write_text(json.dumps({"docs": docs, "words": total, "vocab": len(vocab),
                                                          "shards": [j[0] for j in jobs]}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
