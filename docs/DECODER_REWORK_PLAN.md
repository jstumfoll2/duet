# Duet decoder rework: implementation plan

2026-11-15. Follows `report/FRESH_REVIEW_2026-11-15.md`. Requested by Jason: build the review's
recommendations and features into Duet behind switches, keep the legacy decoder until each new path shows
parity or better on traces, and start step one.

## Rules every step follows

1. **Switch first.** Every behaviour change sits behind a switch whose engine default is the legacy
   behaviour. The DEV build exposes the switch on the traces screen; the replay tool has a flag for it.
   Legacy code is not deleted in the same PR.
2. **Parity gate.** A switch may become the DEV default when, on replay of all trace batches with the
   standard eval flags (`--aim 1 --ctc futo_split_v1 --beta 0.1 --ctcilonly`):
   - gold top-1 and all-lines top-1 are at least legacy's, on both halves (A = batches up to 2026-10-21,
     B = later) when weights were fitted on the other half;
   - no input-shape bucket (single-swipe, two-thumb-overlap, tap+swipe, taps) loses more than 0.5 pt;
   - JVM p95 decode time grows by no more than 20%.
   It becomes the release default only after one real DEV batch from the phone confirms it.
3. **Legacy removal** is its own later PR, once the release default has held for two batches.
4. **One PR per step**, from a fresh branch off `main`, PR title with a semver prefix (`feat:`, `fix:`, …),
   numbers in the PR body, assignee jstumfoll2. No personal traces in the repo, no network permissions.
5. **Fitted weights** are fitted on one half and checked on the other. A fit that only wins on the half it
   saw does not ship.

## Order and why

Measurement first where it is cheap, then the score form (it is the base everything else plugs into), then
evidence the new form can use, then the structural changes that depend on all of it.

| Step | Issue | What | Depends on | Switch |
|---|---|---|---|---|
| 1 | #124 | Real n-gram LM (log P with backoff) + unsaturated shape term + unfaded boosts, in one log-linear score | none | `ScoreModel.LOGLINEAR` |
| 2 | #125 | DEV: after a retype, a one-tap "did you mean X?" to grow the gold set | none | DEV only |
| 3 | #126 | Rebuild the 1M-pair table and the triples with log-probabilities instead of per-context bytes; personal pairs as counts in the same form | 1 | data file + step 1 switch |
| 4 | #127 | Gaussian tap likelihood over all keys within ~1.2 kw, from TouchModel's per-thumb spreads, always on in the new path; store lift and centroid | 1 | `ScoreModel.TAPS` |
| 5 | #128 | Every pass scores through one function; "run when sparse" gates become "run when the lead's margin is small" | 1 | `ScoreModel.UNIFIED` |
| 6 | #129 | Speed weights in the DTW cell cost; resample count grows with arc length | 1 | `ShapeModel.SPEED` |
| 7 | #130 | Keep the last two words' N-best with log scores; flip the previous word when the next one changes the LM verdict clearly; one-backspace undo; correction strip shows it | 1, 3 | pref, default off |
| 8 | #131 | Learn per-user "decoded Y, meant X" penalties from retypes | 1 | pref, default off |
| 9 | #132 | `BoundaryModel` gets the LM's two-word log-probability as a feature, refit | 1, 3 | `BoundaryModel.enabled` |
| 10 | #133 | One decoder over the event stream: Interleave's DP as the core, word boundaries as scored transitions, lazy commit of the last 1-2 words | 1, 4, 5, 9 | `SEARCH_ENGINE = 4` |
| 11 | #134 | Two-thumb time-sampled encoder, lexicon-constrained CTC beam along the trie, LM fused | 1, 10 (or alone as a pass) | `SEARCH_ENGINE = 5` |

Features, slotted where their dependencies land:

| After step | Feature | Notes |
|---|---|---|
| 1 | #135 Local accuracy panel (release) | Backspace-retype rate, bar picks, WPM over days, stored on the phone only. Needed to judge switches in normal use |
| 3 | #136 Per-app and per-field adaptation | Personal counts split by field class (chat, email, search, code) |
| 3 | #137 Phrase completion chip | From the opt-in pair and triple counts |
| 6 | #138 Personal shape templates for learned words | The user's own paths for words the dictionary lacks |
| 7 | #139 "Fix this sentence" action | Joint re-decode of a sentence from stored N-best, shown as a diff |
| 10 | #140 Live word preview while swiping | Needs mid-gesture decoding |
| 10 | #141 Spacebar as a word break inside one stroke | A boundary transition in the lattice |

## Step 1 in detail: log-linear score with a real language model

**Goal.** Replace `fw * geometricTerm(d) * bigram * personal * pair * keep` (with the fade) by

```
ln S(w) = A * lm(w | u, v) + B * shape(d) + ln personal + ln pair + ln keep (+ the post-search terms)
lm      = ln P3(w | u, v)            if the triple is in the table
        = ln P2(w | v)               else if the pair is
        = ln BACKOFF + ln P1(w)      else                     (stupid backoff)
shape   = -ln(1 + (d / SIGMA)^2)     (Cauchy: unsaturated but robust, no floor)
```

- `P1` from the trie's frequency byte, normalised by a new total count kept on the trie.
- `P2`, `P3` from the counts the bigram and trigram tables are already built from, normalised per
  context, quantised to a byte of 1/16 nat (cheap: ~600 KB for 600k entries).
- Personal and pair boosts applied at full strength (no fade by fit); the shape term pays for a poor fit.
- The trigram moves inside the search (it is part of `lm`), so its post-search pass is skipped in this mode.
- The early-abandon budget inverts the new shape term, with every boost included, so it stays admissible.
- Variant spellings (`forms`), held doubles and apostrophe re-spellings get their prior from one helper, so
  they follow the mode.
- Interleaved readings use the same `lm` and keep their own shape cost for now (step 5 unifies them).

**Not in step 1** (later steps): the wide 1M-pair table has only per-context bytes, not counts, so step 1
uses the 100k counted pairs and the 500k triples; step 3 rebuilds the wide table.

**Switch.** `ScoreModel.mode` (`LEGACY` default). Replay: `--score loglinear` with `--lmA`, `--lmB`,
`--lmSigma`, `--lmBackoff`. DEV: a switch on the traces screen next to the boundary model.

**Fit.** Grid over A, B, SIGMA, BACKOFF on half A, checked on half B, then the reverse. Report gold,
context and all-lines top-1, buckets, and p95.

**Done when** the PR is up with CI green and the numbers in its body, whether or not step 1 passes the
parity gate. If it does not pass, the PR stays a draft with the result, and the plan is revisited before
step 3.
