#!/usr/bin/env python3
"""Word-triple counts for the decoder's trigram context, from Tatoeba.

Writes <lang>_trigrams.txt (w1 TAB w2 TAB w3 TAB count), the same shape as
<lang>_bigrams.txt with one more word, from the Tatoeba per-language sentence
export (CC BY 2.0 FR, attribution "tatoeba.org"), the corpus the bigram table
already comes from. Only triples whose three words are all in the shipped
wordlist are kept, triples seen once are dropped as tokenization noise, and the
table is cut to the most frequent MAX_TRIGRAMS.

The development environment cannot reach Tatoeba, so this runs in
.github/workflows/trigrams.yml; pass --sentences-file to run it on a local copy.
"""

from __future__ import annotations

import argparse
import bz2
import collections
import logging
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from generate_assets import (  # noqa: E402
    TATOEBA_LANG_CODE,
    TATOEBA_SENTENCES_URL_TMPL,
    TOKEN_STRIP_RE,
    WORD_RE,
    fetch,
    normalise,
)

LOG = logging.getLogger("trigrams")

MAX_TRIGRAMS = 120_000


def count(compressed: bytes, vocab: set[str], lang: str) -> list[tuple[str, str, str, int]]:
    word_re = WORD_RE[lang]
    counts: collections.Counter[tuple[str, str, str]] = collections.Counter()
    text = bz2.decompress(compressed).decode("utf-8", errors="replace")
    sentences = 0
    for line in text.splitlines():
        parts = line.split("\t", 2)
        if len(parts) != 3:
            continue
        sentences += 1
        # A word outside the vocabulary breaks the run, so a kept triple was three
        # adjacent words in the sentence, as the bigram counter treats pairs.
        run: list[str] = []
        for tok in parts[2].split():
            t = TOKEN_STRIP_RE.sub("", normalise(lang, tok.lower()))
            if not t or not word_re.match(t) or t not in vocab:
                run = []
                continue
            run.append(t)
            if len(run) >= 3:
                counts[(run[-3], run[-2], run[-1])] += 1
    LOG.info("tatoeba: %d sentences, %d distinct trigrams", sentences, len(counts))
    rows = [(a, b, c, n) for (a, b, c), n in counts.items() if n >= 2]
    rows.sort(key=lambda r: -r[3])
    return rows[:MAX_TRIGRAMS]


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--lang", default="en")
    p.add_argument("--wordlist", type=Path, required=True, help="the shipped <lang>_wordlist.txt")
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--sentences-file", type=Path, help="a local <code>_sentences.tsv.bz2")
    args = p.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    vocab = {line.split("\t", 1)[0] for line in args.wordlist.read_text(encoding="utf-8").splitlines() if line}
    code = TATOEBA_LANG_CODE[args.lang]
    blob = (
        args.sentences_file.read_bytes()
        if args.sentences_file
        else fetch(TATOEBA_SENTENCES_URL_TMPL.format(code=code), binary=True)
    )
    rows = count(blob, vocab, args.lang)
    args.out.write_text("".join(f"{a}\t{b}\t{c}\t{n}\n" for a, b, c, n in rows), encoding="utf-8")
    LOG.info("wrote %s: %d trigrams", args.out, len(rows))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
