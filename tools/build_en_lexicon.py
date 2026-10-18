#!/usr/bin/env python3
"""Blend the C4 web counts (tools/web_ngrams.py) into the shipped English lexicon.

Reads the committed en_wordlist.txt, en_bigrams.txt and en_trigrams.txt, plus the
counts .github/workflows/web_ngrams.yml publishes on the `assets/en-web-ngrams`
branch, SCOWL and the full FrequencyWords list, and rewrites the three assets:

Word list. A word joins when two independent sources agree it is a word people
write: SCOWL lists it (the same membership test the list already passes,
generate_assets.spellcheck_keeps) and the web uses it often enough, more often the
rarer SCOWL grades it (ADD_RULES). Modern names SCOWL 2020 lacks (tiktok,
whatsapp, kubernetes) come from MODERN_WORDS, still gated on web use. Only 4+
letters, and no apostrophe forms: a short string is one tap from dozens of words,
and an apostrophe form whose plain spelling is a word (we're, i'll) or a
possessive would outrank that word for the same letters (MarkedContractions owns
those). A new word's count is its web count scaled onto the subtitle counts by the
median ratio of the words both lists hold, then discounted (NEW_WORD_DISCOUNT), so
it ranks just under words of its own frequency. Counts of words already in the list
are not touched.

Word pairs and triples. Web counts are added to the Tatoeba ones after scaling
Tatoeba to TATOEBA_SHARE of the web total, so conversational contexts Tatoeba is
rich in survive while the web supplies the breadth (Tatoeba holds about a tenth of
the contexts typed). Every context keeps at most PER_CONTEXT followers, the table
keeps the strongest MAX_* entries, and only words of the final list take part.

Offline and deterministic: the same inputs give byte-identical assets.
"""

from __future__ import annotations

import argparse
import collections
import gzip
import logging
import statistics
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from generate_assets import WORD_RE, apostrophe_drops, scowl_sizes, spellcheck_keeps  # noqa: E402

LOG = logging.getLogger("build_en_lexicon")

# (largest SCOWL size, smallest web count) a new word must meet one of. C4 slice:
# 753M words, so 100 uses is 0.13 per million.
ADD_RULES = ((50, 100), (70, 300), (80, 1000))
MIN_LEN = 4
# A word the subtitles never use is rarer in chat than on the web: new words count this share
# of their scaled web count, so a common word wins a near tie with one (kept over keto).
NEW_WORD_DISCOUNT = 0.5

# Product and service names, and current technical words, SCOWL 2020 does not
# list. Each still needs MODERN_MIN_COUNT web uses.
MODERN_WORDS = {
    "tiktok", "whatsapp", "pinterest", "linkedin", "photoshop", "gameplay", "gmail",
    "macbook", "millennials", "minecraft", "spotify", "kickstarter", "airbnb",
    "barcode", "lifecycle", "snapchat", "iphones", "reddit", "impactful", "tumblr",
    "ipads", "crossfit", "newsfeed", "codebase", "kubernetes", "homelab", "devops",
    "frontend", "backend", "chatgpt", "smartwatch", "smartwatches", "workflow",
    "workflows", "venmo", "doordash", "instacart", "zelle", "paypal", "chromebook",
    "airpods", "playstation", "nintendo", "fortnite", "twitch", "discord", "github",
    "javascript", "typescript", "python", "linux", "ubuntu", "android", "firmware",
    "dropdown", "screenshot", "screenshots", "livestream", "livestreaming", "unfollow",
    "retweet", "hashtags", "vlogs", "blogging", "webinar", "webinars", "onboarding",
    "smartphones", "touchscreen", "geolocation", "cryptocurrency", "bitcoin",
    "ethereum", "blockchain", "selfies", "emojis", "gifs", "covid", "vaxxed",
}
MODERN_MIN_COUNT = 50

TATOEBA_SHARE = 0.15
PER_CONTEXT = 12
MAX_BIGRAMS = 400_000
MAX_TRIGRAMS = 500_000


def read_counts(path: Path) -> list[tuple[str, int]]:
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        w, c = line.split("\t")
        rows.append((w, int(c)))
    return rows


def read_gz(path: Path, n: int):
    with gzip.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) == n + 1:
                yield tuple(parts[:n]), int(parts[n])


def read_table(path: Path, n: int):
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) == n + 1:
            yield tuple(parts[:n]), int(parts[n])


def new_words(rows, web: dict[str, int], sizes) -> list[tuple[str, int]]:
    have = {w for w, _ in rows}
    both = [(c, web[w]) for w, c in rows if w in web and 1000 < c < 100_000]
    ratio = statistics.median(a / b for a, b in both)
    LOG.info("web -> subtitle count ratio %.4f over %d words", ratio, len(both))
    word_re = WORD_RE["en"]
    added = []
    for w, n in web.items():
        if w in have or "'" in w or len(w) < MIN_LEN or len(w) > 20 or not word_re.match(w):
            continue
        if w in MODERN_WORDS:
            ok = n >= MODERN_MIN_COUNT
        else:
            # Common words only: a SCOWL proper name (icahn) or abbreviation does not count.
            size = sizes.get(w, {}).get("word")
            if size is None or not spellcheck_keeps("en", w, sizes):
                continue
            ok = any(size <= s and n >= c for s, c in ADD_RULES)
        if ok:
            added.append((w, max(1, round(n * ratio * NEW_WORD_DISCOUNT))))
    added.sort(key=lambda r: (-r[1], r[0]))
    LOG.info("words added: %d", len(added))
    return added


def blend(tat, web, vocab: set[str], per_context: int, cap: int):
    tat = {k: c for k, c in tat if all(w in vocab for w in k)}
    web = {k: c for k, c in web if all(w in vocab for w in k)}
    tat_total = sum(tat.values()) or 1
    web_total = sum(web.values()) or 1
    scale = TATOEBA_SHARE * web_total / tat_total
    merged: dict[tuple, float] = collections.defaultdict(float)
    for k, c in web.items():
        merged[k] += c
    for k, c in tat.items():
        merged[k] += c * scale
    by_ctx: dict[tuple, list] = collections.defaultdict(list)
    for k, c in merged.items():
        by_ctx[k[:-1]].append((c, k))
    kept = []
    for followers in by_ctx.values():
        followers.sort(key=lambda r: (-r[0], r[1]))
        kept.extend(followers[:per_context])
    kept.sort(key=lambda r: (-r[0], r[1]))
    kept = kept[:cap]
    LOG.info("table: %d tatoeba + %d web -> %d kept (%d contexts)", len(tat), len(web), len(kept),
             len({k[:-1] for _, k in kept}))
    return [(k, max(1, round(c))) for c, k in kept]


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--assets", type=Path, default=Path("app/src/main/assets/dictionaries"))
    p.add_argument("--web", type=Path, required=True, help="checkout of the assets/en-web-ngrams branch")
    p.add_argument("--scowl-dir", type=Path, required=True)
    p.add_argument("--out-dir", type=Path, help="defaults to --assets")
    p.add_argument("--no-words", action="store_true", help="keep the word list as it is")
    p.add_argument("--tatoeba-dir", type=Path,
                   help="where the Tatoeba-only en_bigrams.txt and en_trigrams.txt are (default --assets); "
                        "after one run the assets hold the blend, so a rerun takes them from the "
                        "assets/en-dictionary and assets/en-trigrams branches")
    args = p.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    out = args.out_dir or args.assets
    out.mkdir(parents=True, exist_ok=True)

    rows = read_counts(args.assets / "en_wordlist.txt")
    if not args.no_words:
        web_uni = {k[0]: c for k, c in read_gz(args.web / "unigrams.tsv.gz", 1)}
        rows = sorted(rows + new_words(rows, web_uni, scowl_sizes(args.scowl_dir)), key=lambda r: (-r[1], r[0]))
        # The web writes "whats" for "what's" as often as subtitles do: the same cleanup the
        # generator applies, so an added plain spelling cannot shadow its contraction.
        drops = apostrophe_drops("en", {w for w, _ in rows})
        LOG.info("apostrophe cleanup drops %d: %s", len(drops), " ".join(sorted(drops)))
        rows = [r for r in rows if r[0] not in drops]
    vocab = {w for w, _ in rows}
    (out / "en_wordlist.txt").write_text("".join(f"{w}\t{c}\n" for w, c in rows), encoding="utf-8")

    for name, n, cap in (("bigrams", 2, MAX_BIGRAMS), ("trigrams", 3, MAX_TRIGRAMS)):
        table = blend(read_table((args.tatoeba_dir or args.assets) / f"en_{name}.txt", n), read_gz(args.web / f"{name}.tsv.gz", n),
                      vocab, PER_CONTEXT, cap)
        (out / f"en_{name}.txt").write_text(
            "".join("\t".join(k) + f"\t{c}\n" for k, c in table), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
