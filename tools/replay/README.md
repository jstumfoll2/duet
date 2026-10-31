# Trace replay harness

Replays recorded swipe traces through today's decoder and reports how often the
committed word comes out on top. Plain JVM: it needs a JDK, not the Android SDK.

```bash
# Tapped-word-then-swipe buffers, real ones (join) and ones built by leaving out the space
# between a tapped word and the swiped word after it (split), one TSV row each
./gradlew -p tools/replay replay --args="splitdata TRACE.jsonl..."

# A swiped word and the swiped word after it with the space left out, read back as the backspace
# split would (PeckSplit.readSwipe): how often both words come back
./gradlew -p tools/replay replay --args="swipesplit TRACE.jsonl..."

# Which score term each miss lost on: for every line whose label is listed but not
# first, the ScoreTerms factor (freq, geo, bigram, path, ...) with the biggest deficit
# against the winner, tallied. --lines prints each line too.
./gradlew -p tools/replay replay --args="terms [--lines] TRACE.jsonl..."

# Record a synthetic session (useful for trying the tools; not an accuracy figure)
./gradlew -p tools/replay replay --args="synth --words 300 /tmp/synth.jsonl"

# Replay traces: exactness check plus metrics, overall and per bucket
./gradlew -p tools/replay replay --args="replay /tmp/synth.jsonl"

# The engine's JVM suite plus the trace gate, without the Android SDK
./gradlew -p tools/replay test
```

## Decode latency

```bash
# Per input shape: mean, p50 to p99 and max of each line's fastest of three warm runs
./gradlew -p tools/replay bench --args="TRACE.jsonl..."
# The same with the phone's NN switch on (two-thumb rerank at beta 0.1)
./gradlew -p tools/replay bench --args="--ctc app/src/debug/assets/ctc/futo_v1.kctc --beta 0.1 TRACE.jsonl..."
# Add --stages for where the time goes (il, passes, stream, fuzzy, rescue, beam, rerank),
# --alloc for bytes allocated per line instead, or --interleave for the two-thumb search
# alone with a hash of its hits (a speed-up that changes nothing keeps the hash)
```

To compare two builds, run both on the same files back to back; JVM times are relative, not
phone timings. A DEV build's decode log has the phone's own: each `decode time` line gives the
total, then the stages that took 0.01 ms or more.

Options: `--keepdups` scores a word line again when an earlier file already had it (by default
repeats are skipped and counted: a later export can contain an earlier one); `--k N` sets the deep list for recall (default 50); `--edge G` sets the
near-edge tap gap in kw (0 turns that pass off); `--nosplit` keeps a tapped word followed by a
swipe one word, as with automatic spaces off (a split loses the line's word); `--assets DIR`
points at `app/src/main/assets` when run from elsewhere. `--touch 1` gives the replay a touch model that learns
line by line from the labels, as the keyboard's does from commits; with it, `--hintz Z` and
`--hintshift KW` set when a tap hints a key it may have meant (see `TouchModel.hint`). `replay` exits 1 if any
comparable line fails to reproduce.

## Output

```
lines=229 scored=220 abandoned=9 unreadable=0
exact replay: 229/229 comparable lines (identical list, scores bit for bit)

bucket                  n    top1    top3     MRR    in@10 recall@50
all                   220   98.2%  100.0%   0.991   100.0%     99.1%
...
```

- **exact replay**: the replayed shipping list equals the list the bar showed,
  word for word and score bit for bit. Lines are comparable unless they were
  recorded with personal dictionary state, a replaced wordlist, or committed
  before their last decode landed.
- **top1, top3, MRR, in@10**: from the shipping decode (TOP_K = 10), the list
  the keyboard itself shows. MRR counts 0 when the word is absent.
- **recall@50**: from a second decode at K = 50. That is a different search, not
  a longer copy of the first: a bigger heap loosens early-abandon budgets and
  spends the fixed `MAX_EMIT_ATTEMPTS` budget sooner, so it can lose a word the
  shipping list has. The report counts those lines separately.
- **Retyped words**: a free-typed commit the user undid (a backspace, or a buffer
  abandoned after it) takes the next committed word as its label when the two are
  close ("go" then "to", "gels" then "tells"); if the next word only extends it
  ("ca" then "can") the commit was a stray mid-word space and goes unscored. Plain
  labels count a deleted typo as right, so a fix for it read as a loss. `--plain`
  keeps the committed labels. The scorecard shows these lines as `retyped`.
- **Buckets**: input shape (exactly one of `single-swipe`, `multi-swipe`,
  `two-thumb-overlap`, `tap+swipe`, `taps`) plus two label attributes that
  overlap them, `double-letter` and `short` (3 letters or fewer).

## Trace format v1

One JSON object per line, one line per word buffer. The code and full field
notes are in `app/src/debug/java/com/kinetica/keyboard/engine/trace/SwipeTrace.kt`.

```json
{"v":1,"type":"word",
 "cfg":{"lang":"en","alt":null,"britishSpelling":false,"personal":false,"dictOverride":false},
 "geom":{"kwPx":108.0,"midPx":540.0,"tapPx":37.8,"keys":{"a":[0.5,1.5,1.5,3.0], "...":[]}},
 "ctx":["of","the"],
 "tokens":[{"src":"ev","s":"L","ev":[[607.69,95.79,10000],[612.1,97.0,10008],[640.2,99.3,10016]]},
           {"src":"tap","s":"L","k":"h","x":6.0,"y":2.25,"lp":false,"t0":10200,"t1":10201}],
 "shown":{"n":2,"c":[["hello",0.83,"en"],["hells",0.12,"en"]]},
 "word":"hello","how":null}
```

- `geom.keys`: letter rects in key widths (left, top, right, bottom). Multiply by
  `kwPx` for pixels.
- `tokens[].src == "ev"`: a gesture as raw samples `[x px, y px, t ms]`, down
  first and lift last, with `s` the thumb stream (L or R) the engine assigned.
  Replay feeds these to a fresh `GestureEngine`, so tap/swipe classification,
  contacts and dwells are replayed too, not trusted from the recording.
- `tap` / `swipe`: tokens that never came from the engine (a word reloaded from
  the editor, an accent-popup letter), stored field for field.
- `shown`: the last list the bar received and how many tokens its decode saw.
- `word`: the committed word, or null for an abandoned buffer. `how` is reserved
  for how it was committed (picked, autocorrected, typed) once the recorder is
  wired into the keyboard.

Floats are written as the shortest decimal that identifies the float. Parse
them straight to float32 to get the recorded bits back.

## Privacy

Every line contains what was typed, so a trace file is personal. `*.jsonl` and
`traces/` are ignored by git; keep it that way. The personal dictionary itself
(learned counts, pairs, user and blocked words) is never recorded; `cfg.personal`
only says whether it influenced the live decode, and the harness always replays
against the bundled dictionaries.

The recorder (`SwipeTraceRecorder`) is in the debug source set only, like the
existing `TraceRecorder`: a release APK contains no trace code.
