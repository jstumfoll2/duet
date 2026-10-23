# Duet

[![License: GPL-3.0-or-later](https://img.shields.io/badge/license-GPL--3.0--or--later-blue)](LICENSE)
[![CI](https://github.com/jstumfoll2/kinetica/actions/workflows/ci.yml/badge.svg)](https://github.com/jstumfoll2/kinetica/actions/workflows/ci.yml)

Duet is an Android keyboard for **two thumbs at once**. Each thumb swipes or taps on its own, at
the same time as the other, and both streams become one word. The goal is the accuracy and feel of
Keymonk and Nintype.

Duet is a fork of [Kinetica](https://github.com/EZ-eta/kinetica) by Elia Zanella (EZ-eta), and
keeps its GPL-3.0-or-later licence. Kinetica wrote the keyboard: the gesture engine, the decoder,
the settings and nearly every feature listed below. Duet adds a two-thumb decoder of its own, a
way to measure accuracy from real typing, and a set of English and everyday features on top. It
has its own name, app id and icon, as Kinetica asks of forks. Report Duet bugs
[here](https://github.com/jstumfoll2/kinetica/issues), not upstream.

Typing SOMETHING:

- the left thumb taps `S`, then `E`;
- the right thumb swipes `O -> M`, then `T -> H -> I -> N -> G`, overlapping in time;
- Duet merges `S + OM + E + THING` into "something".

**No network permission.** The manifest declares no `INTERNET` permission, so nothing you type can
leave the phone. Decoding, prediction and learning run on the device; password and private fields
turn off suggestions, trails and learning. The one other sensitive permission, `READ_SMS`, is
asked for only if you turn on *Codes from text messages*.

## Install

Every change merged to `main` that passes CI becomes a
[GitHub release](https://github.com/jstumfoll2/kinetica/releases/latest). Each release carries:

- `duet-dev-<version>.apk`: the DEV build, which installs as **Duet DEV**
  (`io.github.jstumfoll2.duet.dev`). It is signed with the fixed DEV key in
  `app/duet-dev.keystore`, so each one installs as an update over the last.
- `duet-<version>.apk`: the release build (`io.github.jstumfoll2.duet`), attached only once the
  repository has its release signing secrets.

```bash
adb install -r duet-dev-*.apk
```

Or open the APK on the phone. Then open the Duet icon and follow the three steps to enable it.

Every CI run, pull requests included, also uploads the DEV APK as the `duet-dev-apk` artifact
(Actions > CI > the run > Artifacts), kept for 30 days. It builds the release app too and uploads
it as `duet-release-apk-unsigned`. That APK is unsigned, so it can't be installed until it's
signed; it shows that the release build still compiles.

Duet does not upgrade Kinetica or an older Kinetica DEV build: it is a different app, with its own
data. Back up the old one first (Settings > Backup) and restore the file in Duet. Duet is not on
F-Droid.

## What Duet adds to Kinetica

### Decoding

- **Interleaved two-thumb reading.** When swipes from the two thumbs overlap in time, Duet also
  reads them as one path on one timeline (`Interleave`, `InterleavedSearch`) instead of only
  cutting them into pieces and merging the pieces. Its readings join the same ranked list as
  Kinetica's. On by default for Latin-script boards.
- **Touch repair.** When the thumbs come within about a key of each other, the touchscreen can
  merge them into one contact for a few milliseconds. `ContactRepair` undoes that, and the two
  streams carry on after a joint lift instead of swapping.
- **Long swipes keep their length.** A long one-finger swipe across the board is no longer beaten
  by a short, common word that fits only part of the path.
- **Held doubles.** Pausing on a key mid-swipe reads as a doubled letter (`HeldDoubles`); a missing
  pause is never counted against one.
- **Per-thumb touch offsets.** Duet learns where each thumb lands relative to each key it meant
  (`TouchModel`). It moves taps back toward the key meant only once it has proved more accurate
  than the raw touches on your own recent taps, so for a precise typist it stays off.
- **Short slips.** A short tapped word with one letter hit beside the one meant is fixed when the
  previous word settles it (`going ti` becomes `going to`), and left alone when nothing does.
- **A pecked word, then a swipe.** With automatic spaces on, tap `to` and swipe `help` straight
  after with no space: Duet types "to help", not one word made of both (`PeckSplit`). It splits
  when the two words read better than one, and needs a much clearer case when the swipe follows
  the taps at once, as it does inside a word: `m`, `e` tapped and `rge` swiped still makes "merge",
  and `a` tapped with `llow` swiped makes "allow". When the two do end up as one word, one
  backspace right after it puts the two words back (`toddle` becomes "to feel"), and a backspace
  slide stops at the join first and leaves "to " for the second word again.
- **Whole-word backspace (optional).** With "Backspace deletes a swiped word" on (Settings >
  Shortcuts and gestures > Swipes and slides), one backspace tap right after a swiped word deletes
  the whole word and its space, and the bar offers the swipe's other readings. Holding backspace,
  tapped words and anything typed after the word still delete letter by letter. Off by default.
- **A left-out space.** Tapped letters that are no word but a common pair with the space left out
  (`tofeel`) become the two words; backspace right after puts back what you typed.
- **Words started from the spacebar.** A thumb that lands on the spacebar and rises onto the
  letters draws its word instead of typing a space and losing those letters.
- **Apostrophes, Nintype style.** Swipe out to the apostrophe key and back mid-word
  (`w-e-'-r-e`) for "we're"; the trip is cut out of the path before it is decoded. A stroke can
  also end on the key: `h-o` then down to `'` for "who's", or `i` straight to `'` (with `v-e` on
  the other thumb) for "I've".
- **A tap while the first key is held.** Hold the first letter, tap the next one with the other
  thumb, then slide on: `d`, tap `i`, slide to `r` gives "dir" even when the tap comes right after
  the hold starts.
- **Swipes from the edge.** A swipe or tap that starts in the empty margin beside `a` or `l`, or
  in a gap between keys, starts on the letter beside it instead of being dropped.

### English

- A larger English list, filtered with SCOWL, with everyday and technical words added. A
  contraction whose letters spell another word (`we're`, `were`) is offered only when you asked for
  the apostrophe, so it never competes with the plain word.
- Word triples (`en_trigrams.txt`) for context, on top of Kinetica's word pairs.
- A small grammar pass on the word before the one just written: `a`/`an`, `could of`, `he don't`,
  and confusion pairs such as `then`/`than` when the next word settles them (`GrammarCheck`).
- Grammar auto-fixes as a word lands (on by default: Settings > Typing > Fix grammar slips
  automatically): a function word typed twice (`to to`), a contraction split by a space
  (`isn t`), `I are`, and `to` run into a word (`tobe`, `triedto`). One backspace right after
  puts the text back (`GrammarAutoFix`).
- Grammar underlines from [Harper](https://github.com/Automattic/harper) (opt-in: Settings >
  Typing > Grammar check). Harper (Apache-2.0, rule-based, a few milliseconds per sentence) runs
  on the phone. A slim row above the suggestion bar mirrors the sentence being written with its
  mistakes underlined in red; tap one for the fix. This works in every app. With Duet chosen as the
  system spell checker (Languages and input > Spell checker, Android 12+), apps whose text fields
  support it also underline the mistakes in the text. Spelling stays with Duet's own dictionaries.
- Language-aware learning: a new word needs three commits before it is learned, and a word is
  learned in the language it belongs to, so English typed in Spanish mode does not fill the
  Spanish list.

### Everyday features

- **Spell checker.** Duet's dictionaries as an Android spell checker (pick it in the system's
  spell checker settings). A word is right if any enabled language spells it that way, so Spanish
  on an English phone is not underlined.
- **Clipboard history.** The last clips copied, offered back on the bar. Kept in memory only, and
  a clip the copying app marks sensitive is never kept.
- **Image paste.** A copied image joins the clipboard history and pastes as an image into any field
  that takes one (chat apps, email), through Android's rich content API (`commitContent`). Fields
  that take only text are not offered it. The image is copied into the keyboard's private cache and
  deleted when it leaves the history; no new permission.
- **Menu button on the bar** (on by default). A ☰ at the bar's left end brings up the shortcut
  row (paste, undo, clipboard and the rest) in the middle of a sentence, over the last word's
  alternatives. A shortcut, a second tap or the next word puts the suggestions back. Turn it off in
  Settings > Suggestion bar.
- **Voice typing key.** An action that hands the field to the phone's voice input; bind it to a
  chord, an edge swipe or a bar shortcut.
- **Autofill chips.** On Android 11 and later, the password manager's saved logins, addresses and
  codes appear as chips in the bar. The keyboard never sees the secret behind a chip.
- **Codes from text messages** (off by default): the one-time code from a recent text, offered in a
  code field. Asks for `READ_SMS`.
- **Incognito** (off by default): learns nothing in any field, no new words, word pairs, recent
  emoji or clipboard history, while suggestions keep working. The spacebar shows 🕶 while it is on.
- **Sound on key press** (off by default).
- **Emoji search and skin tones.** The 🔍 tab in the emoji panel searches names and keywords
  ("pi" finds 🍕 and 🥧, "red heart" finds ❤️) from a small letter board inside the panel. The hand
  in the panel's footer sets a default skin tone, and a long press on a hand or person picks a tone
  for that emoji alone, which is remembered. The bar's emoji suggestions use the same tones.
- **Theme presets.** Pure black (AMOLED), Ocean, Forest, Sunset, Paper, Lavender and High
  contrast, in Keyboard theme next to Duet dark, Material You and Custom colour. Duet dark stays
  the default.
- **Gap above the space bar** (25% of a letter row by default). A strip between the bottom
  letters and the space bar row (space, comma, period, enter) types the letter above, so a thumb
  aiming low for `b` or `n` no longer types a space. Settings > Size and layout, 0 to 50%. The
  gap under the numbers row works the same way. Both gaps are added beside their rows, and the
  board grows to fit, so no key is shortened.
- **Numbers row and space bar row height.** Each is a percent of a letter key's height, 50 to
  150% (Settings > Size and layout, 100% by default, matching the letters). The board grows or
  shrinks to fit; the letter keys and swipe decoding are unchanged.
- **Spacing between keys.** How far apart keys are drawn, 0 to 300% of the stock spacing
  (Settings > Size and layout). Drawing only: touch targets and swipe decoding are unchanged.
- **Autospace mid-paragraph.** A word written before text that already follows (`cats|.` or
  `word| next`) holds its space so nothing doubles, and the next word you start there gets it
  instead.
- **Colour swatches.** The accent and trail hue sliders show the picked colour as a dot, a tinted
  thumb and a rainbow track, and the trail colour row shows its colour too. Each slider is grayed
  out unless it does something: the accent hue needs the *Custom colour* theme, the trail hue needs
  the *Custom hue* trail, and brightness is off for the fixed presets.
- **Settings in three groups.** *Typing* (typing and correction, suggestion bar, languages, words
  and learning), *Keyboard* (look, sound and vibration; size and layout; keys; shortcuts and
  gestures) and *Help and backup* (Learn Duet, backup, about). Inside each screen, rows sit under
  short headings, and settings search reaches every row.
- **Word menu on the bar.** Hold a suggestion still for about a second: *Never*, *Less*, *More*,
  *Forget* and *Why?* replace the words. *Less* halves the word's score each time, so even a
  common word can drop below a rarer one (sliding only ever lowered what you had taught it);
  *More* undoes a *Less* first; *Forget* clears both; *Why?* shows its dictionary frequency, how
  often you picked it and any *Less*. Holding and sliding still adjusts the weight as before.

## For testers: the DEV build

The DEV build has everything above, plus a **Traces and practice** screen. It is one app with one
launcher icon: open the screen from *Settings > Learn Duet > Traces and practice* or from the button on Duet DEV's
start screen. (Builds before this change had a second *Duet DEV trace* icon; updating removes it,
and recorded traces and settings carry over because the app id is unchanged.)

- **Trace recording** (word traces on by default in the DEV build, with switches on the traces screen): each
  word you type is saved on the phone with its touch samples and the list the bar showed.
  Password and private fields and incognito are never recorded, and traces are kept out of cloud
  backup.
- **Practice mode** (*Duet DEV practice*, from the traces screen): prompts a word, asks for three
  attempts, and records each as a labelled trace. *Discard last swipe* drops a bad attempt, and
  the word length can be picked.
- **Prose practice** (*Duet DEV prose practice*, from the traces screen): a sentence or two to
  copy as you would type a message. The word to type is underlined and finished words turn green
  or red as they come out; each word is recorded with its passage word as the label, so running
  text (context, automatic spaces, punctuation) gets measured too. *Next passage* skips ahead.
- **Export**: *Export* and *Export word traces* write the traces to a file you choose through
  Android's file picker. The app itself has no network access, so sending them anywhere is up to
  you.
- **Neural rerank** (off by default): a small CTC model, trained from scratch on FUTO's MIT swipe
  dataset (never FUTO's weights), can reorder the bar's list. The traces screen turns it on and sets
  its weight. Off builds exactly the release decoder.

Traces are personal typing data: keep them out of this repository.

## Measuring accuracy

`tools/replay` replays recorded traces through today's decoder on the plain JVM (no Android SDK)
and reports top-1, top-3, MRR and recall, overall and per input shape (one swipe, several swipes,
two-thumb overlap, taps and swipes, taps only). It first checks that each line reproduces the list the phone showed, score for score.

```bash
./gradlew -p tools/replay replay --args="replay traces.jsonl"
./gradlew -p tools/replay test     # engine JVM suite plus the exact-replay gate
```

See [tools/replay/README.md](tools/replay/README.md). The CTC model's training code is in
[tools/experiment](tools/experiment/README.md); the `train` workflow runs it in GitHub Actions.

## Features from Kinetica

Everything below comes from Kinetica and works the same in Duet.

### Two thumbs

- Two independent swipe/tap streams, one per thumb, merged by contact time. Each thumb is read with
  its own cursor, so a word can go back and forth between them in any order.
- A tap from one thumb inside the other's swipe can double a letter (`h-e-l-o` swipe + `l` tap is
  "hello"), and one tap can stand for a doubled letter (`ottima`, `tutti`).
- Swipe decoding written from scratch: banded dynamic time warping over resampled paths, an anchored
  trie search, frequency and word-pair context.
- A thumb left resting on the keys, a third finger while both thumbs draw, and a number-row digit
  brushed mid-swipe type nothing.
- Practise two thumbs (Settings > Learn Duet): five words drawn over the keyboard, thumb by
  thumb, typed in a practice field.

### Suggestions

- Up to ten candidates; a page holds as many as fit, and the bar swipes sideways to the next one.
  Flick a word up to put it in at once.
- Words sit side by side with a small gap (Suggestion bar > *Compact suggestions*, on),
  in a text size of your choice.
- After a commit the bar becomes a correction strip: tap another word and it replaces the one just
  written, taking its learned weight with it.
- Next-word suggestions while nothing is being typed (Typing and correction, on).
- Recent words: the last two words stay on the bar, each with the words it beat; tap one to swap it
  in (off by default). They have their own text size and a swipe direction to choose.
- A tapped word with one stray or one missing letter is offered on the bar, never changed for you.
- Tap completions mid-word (`t-h` offers "the", "they"); the letters you typed are always the last
  zone, so a word the dictionary lacks goes in with one tap.
- Tap autocorrect with three levels. It restores missing accents (`perche` -> `perché`,
  `pozno` -> `późno`), and backspace right after it puts back what you typed.
- A retype button (off by default) takes the word back, and the bar then offers what else the
  gesture could have been.

### Editing

- Tap inside a word you wrote and the bar offers what it had when you wrote it, then the word's
  spelling neighbours; a pick replaces the whole word.
- Backspace to the end of a word to keep working on it with taps or swipes.
- Slide along the spacebar to move the cursor, by letter or by word.
- Slide left from backspace to mark words for deletion; slide back to keep them, lift to delete.
- Swipe shift up for `ABC`, right for `Abc`, down for `abc`, or hold it for the menu: on the word at
  the cursor or on selected text. The swipes are edge swipes like any other, so they can be changed.
- Enter searches, sends or goes where the app asks for it, and its label says which; hold it for a
  new line (Keys > Enter and fields, on). In a chat box, *Hold enter to send* swaps the two (off).
- Keys that fit the field: `@` on the comma in an email field, `/` in a web address, no capital at
  their start (Keys > Enter and fields, on). Number and phone fields can open on the number pad (off).
- A new field opens on the letters, wherever the last one was left (Keys > Enter and fields, on).
- Tidy spaces: never two spaces in a row and none before a closing quote (Typing and correction > Spacing, off).
- The marks that take back an automatic space are a list you can edit (Typing and correction > Spacing > *No space
  before*), and a band on the bar shows the wait before the space lands.

### Shortcuts

- Chords: hold `?123` or the spacebar and tap a key. Each chord picks its trigger, so `?123`+`l` and
  space+`l` are two chords. Defaults: `?123`+`l` switches language, `?123`+`p` toggles peck-type;
  both can be removed. Hold the trigger still and each key shows its chord (Shortcuts and gestures,
  on).
- Edge swipes: swipe off any key in any direction. Defaults: backspace up `!`, enter up `?`, and
  shift up, right and down for the word's case.
- Text expansions: a short trigger becomes longer text, fired by the Expand action. A trigger can
  have several targets, picked on the bar, and targets can chain. A capital in the trigger can be
  matched exactly, ignored, or carried into the text (`Andor` gives `And/or`), set on the Text
  expansions screen.
- A chord, an edge swipe or an expansion can type text, run an action (paste, copy, undo, Tab, the
  arrows, Home, End, Esc, forward delete, the date) or press Ctrl and a key (Ctrl+A, Ctrl+Del).
- Shortcuts on the bar when there is nothing to suggest, and in the `?123` hold menu; both sets are
  chosen and ordered in Suggestion bar. While the chords show, the menu sits on the
  spacebar so it covers none of them.
- Settings from the keyboard: hold `?123` and slide onto the gear.

### Keys and layout

- Long-press menus on every letter and punctuation key: a row or a 3x3, 4x3 or 5x3 grid. Each
  letter's list can be edited, filled from every enabled language with Default, or given a shortcut
  (`:paste`). Swipe up on the top row, or down on the bottom row, for the key's corner character.
- Numbers row above the letters (Keys, off). The keyboard grows so the letters keep their size.
- Middle row width (Size and layout): spreads `a s d f g h j k l` toward the edges, for hands used to
  a board without the half-key indent.
- Layout modes: full width, right, left, split, one-handed on either side. Split's gap is a slider
  (Size and layout, 0-40% of the width, 16 by default; 0 closes it). Landscape has its own
  height and can be split to the edges, centred or full width. An unfolded phone or a tablet held
  upright uses the landscape arrangement (Size and layout, on).
- Height by dragging the handle above the bar, side and bottom margins, a live size preview.
- An optional apostrophe key right of `L`, for `don't` or `nell'immagine` without the symbols page.
- Two symbol pages and a numpad; a configurable comma and period key; an emoji picker on the
  comma's long-press, with a frequently used tab (search and skin tones are Duet's).
- Words per minute on the spacebar while you type (Keys, off).
- Themes: dark, light, Material You, or a palette from one colour of your choice. Zen mode turns
  animations off.

### Languages

- English, Italian, Spanish, Polish, Czech, Dutch, German, French and Norwegian; Russian, Ukrainian,
  Hebrew and Arabic are experimental, each with its own board. 46k to 50k words per language with
  real corpus frequencies, and up to 100k word pairs.
- Switch with a chord without leaving the field; the active language shows on the spacebar.
- Accented words are drawn on their base keys and come out with their accents. A spelling with the
  accents left off is offered only where written text uses it: `pojsc` corrects to `pójść`, while
  `ze` and `że` both stay.
- Mix enabled languages: the active language and the next enabled one in one list (Languages > More
  language options, off).
- Follow the language I type: up to three languages as equals, the one you are typing weighs most
  (same screen, off, experimental).
- British spelling for English.
- A language is data plus registration, so new ones can be contributed:
  [ADDING_A_LANGUAGE.md](ADDING_A_LANGUAGE.md).

### Learning and privacy

- Every committed word earns weight for its language; a word you keep choosing outranks a more
  common one. Hold a suggestion and slide up or down to change its weight, one badge tier per step;
  slide past the lowest tier to block it.
- A word you keep capitalizing yourself, a name or `GitHub`, comes out with its capital. Writing it
  in lowercase over the capital talks it back out.
- Phrase learning (optional): which word you tend to type after which.
- Dictionary: learned and blocked words per language, with search; import of an AOSP word list,
  merged on the phone.
- Backup: every setting, learned word and shortcut in one text file, restored with progress, and
  the last restore can be undone.
- Settings in English and Italian, by the phone's language or Android 13's per-app language;
  [CONTRIBUTING.md](CONTRIBUTING.md) says how to add a translation.
- Learn Duet in Settings: the two-thumb practice, then short tips from the basics up.

### Worth a look in Settings

- **Get rid of a word for good.** Hold it on the bar and slide past the lowest tier, or add it in
  Settings > Words and learning > Dictionary > *Blocked words*. A blocked word is never offered again and never learned
  back. With Words and learning > *Blocking a word deletes it*, the word you just wrote also leaves
  the text.
- **Peck-type mode** turns off swiping, suggestions and autocorrect, so every tap types exactly its
  letter. For slang, codes and anything the dictionary keeps fighting.
- **Automatic spaces after tapped words too.** Typing and correction > Spacing > *Autospace tapped words too*. A
  word the dictionary holds gets its space after a pause, and the next letter takes it back if the
  word was not over. Once the next word is finished, Duet weighs it as one word or two and puts
  the space back when two read better: `to` then `a` stays `to a`, `the` then `y` becomes `they`. Three sliders set the delays. *Smart timing for tapped words* (on) reads the
  letters: `if` and `my` space sooner, `th` waits longer, and a word you spell slowly waits for your
  own pace. *Autospace delay after one letter* sets the least `I` or `a` waits.
- **Autospace that keeps up.** *Match the delay to your speed* (on) shortens the autospace wait
  while you type fast and lengthens it while you slow down, from half to twice the sliders'
  values: swiped words follow your words per minute, tapped words your pauses between letters.
- **A shorter keyboard.** *Keyboard height* goes down to 10% of the screen, *Suggestion bar height*
  shrinks the bar and its text, and *Resize handle height* at 0 removes the grip.
- **Long-press without the accents.** *Hide accented letters on long-press* leaves digits, symbols
  and the letters of your enabled languages, so Polish keeps `ą` on the English keys.
- **A much bigger dictionary.** Settings > Words and learning > Dictionary > *Import improved dictionary* takes an AOSP
  `wordlist.combined` and merges it with the bundled list, three to five times the words. Get the
  `main_<code>.combined` file for your language from
  [aosp-dictionaries](https://codeberg.org/Helium314/aosp-dictionaries) under `wordlists/`.
- **Search the settings** with the icon at the top; it knows several words for each setting.

## Build and test

### Requirements

- JDK 17
- Android SDK: platform 34, build-tools 34.0.0 (the command-line tools are enough)
- Gradle 8.7 through the bundled wrapper
- minSdk 26 (Android 8.0), targetSdk 34

#### Toolchain from zero (macOS, Homebrew)

```bash
brew install openjdk@17
brew install --cask android-commandlinetools
export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="$(brew --prefix)/share/android-commandlinetools"
yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses
sdkmanager --sdk_root="$ANDROID_HOME" "platform-tools" "platforms;android-34" "build-tools;34.0.0"
echo "sdk.dir=$ANDROID_HOME" > local.properties
```

On Linux or Windows, any JDK 17 and the Android command-line tools work the same way: install
platform 34 and build-tools 34.0.0, then point `sdk.dir` in `local.properties` (or `ANDROID_HOME`)
at the SDK.

### Build

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The grammar suggestions need Harper's native library, built from Rust (`native/harper-jni`)
before Gradle runs. CI does this; locally, with the Android NDK and
`cargo install cargo-ndk --locked --version 4.1.2`:

```bash
ANDROID_NDK_HOME=/path/to/ndk native/harper-jni/build-android.sh
```

Skip it and the app still builds; the grammar setting then has no library and does nothing.

The debug build is Duet DEV (`io.github.jstumfoll2.duet.dev`), beside the release app with its
own data. `assembleRelease` builds the release app, signed only when a `keystore.properties`
points at a keystore (see `app/build.gradle`); without one the APK is unsigned.

### Test

The gate every change must pass, the same one CI runs:

```bash
./gradlew assembleDebug test lint
```

The engine is pure Kotlin and tested on the JVM, including golden decodes against the real
dictionaries. [CONTRIBUTING.md](CONTRIBUTING.md) has the hard rules: no Android types under
`engine/`, no network, third-party libraries and models only when they run on the device and
are listed in THIRD_PARTY_NOTICES (the swipe decoder itself stays in-house), tunables in
`engine/KineticaConstants.kt`.

### Releases

Merging to `main` releases automatically once CI is green (`.github/workflows/auto-release.yml`):
a patch bump by default, or label the pull request `release:minor`, `release:major` or
`release:skip`.

## Architecture

The code keeps Kinetica's package, `com.kinetica.keyboard`.

```
MotionEvent
   -> KeyboardView          touch routing: letters to the engine, space and backspace to controllers
   -> GestureEngine         up to two pointer streams, tap or swipe decided at lift
   -> InputToken            TapToken (anchor) | SwipeToken (resampled path + key contacts)
   -> WordComposer          the word's tokens, decoded on a dedicated thread
   -> WordPredictor         merge orders + interleaved reading -> trie search -> DTW -> ranked list
   -> KineticaIME           commit-only text model through InputConnection, the bar, autocorrect
```

| Package | Contents |
|---|---|
| `engine/` | Pure Kotlin decoder: `GestureEngine`, `WordPredictor`, `ThumbBeam`, `DtwMatcher`, `Trie`, `Interleave`, `ContactRepair`, `TouchModel`, `GrammarCheck`, `CtcScorer`, dictionaries and tables |
| `ime/` | `KineticaIME` (the InputMethodService), editor state, clipboard history, autofill, SMS codes |
| `spell/` | `KineticaSpellChecker`, the system spell checker service |
| `ui/` | `KeyboardView`, `SuggestionBarView`, emoji picker, theming, trails |
| `layout/` | JSON layouts, mutations, layout-mode transforms |
| `keys/` | Shift, spacebar and backspace controllers, edge swipes, editor actions, chords |
| `settings/` | The settings screens, `Prefs`, `KeyboardConfig`, backup |
| `data/` | Room: learned words, word pairs, blocked words, chords, expansions |
| `onboarding/` | The enable flow and the two-thumb tutor |
| `src/debug/` | DEV only: trace recorder, practice mode, export, neural rerank |

### Algorithm notes

- **Coordinates** are in key widths, so density, height and layout mode drop out.
- **Swipe matching**: the observed path and each word's ideal path (through key centres) are
  resampled to 32 points by arc length and compared with Sakoe-Chiba banded DTW (radius 4, endpoints
  anchored and weighted, abandoned early against the current floor).
- **Candidates**: a search over a flat trie (two ints per node, children sorted, a 6-bit letter code
  so Cyrillic and Arabic fit). Taps are exact anchors; a swipe is a segment pruned by its start and
  end keys, the path's order, and an ideal-length band. A letter records every pass of the path near
  it, so a revisited letter (the second `e` of "however") still matches. DTW runs on complete words
  only.
- **Two thumbs**: tokens are ordered by contact time, and a near-tie (120 ms) also tries the swapped
  order. A further pass gives each thumb its own cursor and a short hand-back, and a best-first beam
  reads both thumbs in one search; their readings join the same ranked list.
- **Scoring**: `frequency_weight * geometric_term(d) * bigram_multiplier * personal_boost`, with
  `geometric_term(d) = 1 / (1 + min(d, 0.50))^3.75`. Inside half a key width the shape of the gesture
  is scored steeply; past it, a d=0.6 and a d=1.5 match both mean "not this shape", and frequency
  decides. The context and personal multipliers fade with the candidate's fit, so a common or
  heavily learned word cannot beat a clearly better match.
- **Accents**: the trie stores accent-folded keys per script; spellings that differ from their key
  ("senti", "sentì") carry their own frequencies and come out as separate candidates.
- Decoding runs in single-digit milliseconds on the JVM against a 100 ms budget (see
  `RealDictionaryTest`).
- **The backspace slide** shows the marked text in a chip above the key, not as a composing region in
  the editor: the text model is commit-only because composing spans behave differently from app to
  app. In password fields the chip shows bullets.

## Data sources

Regenerate a language's dictionary with
`python3 tools/generate_assets.py --lang <code>` (`--dry-run` to preview); the codes are
en, it, es, pl, cs, nl, de, fr, no, ru, uk, he and ar.

- Word frequencies: [hermitdave/FrequencyWords](https://github.com/hermitdave/FrequencyWords)
  (OpenSubtitles 2018), MIT. The 50k list per language; Ukrainian from the full list.
- Word pairs: counted from the [Tatoeba](https://tatoeba.org) sentence corpus of each language,
  [CC BY 2.0 FR](https://creativecommons.org/licenses/by/2.0/fr/), attribution tatoeba.org. The same
  conversational register as the word lists. Tatoeba also decides which accent-less spellings stay
  and which German nouns are capitalized.
- English additions: SCOWL decides which entries the English list keeps; C4 web text counts add
  words and the English word triples. Both are listed in [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES).
- Emoji: a hand-curated `assets/emoji_data.json` (478 emoji with names and keywords, up to
  Unicode 15). No ZWJ sequences, because a phone that cannot render one draws its
  parts; an emoji the phone's font cannot draw is left out. Skin tones are added when shown, not stored in the
  asset, and a toned form the font cannot draw falls back to the plain emoji.

### Open keyboard dictionaries (from Kinetica)

Considered as replacements for the OpenSubtitles word lists (July 2026):

| Project | Code licence | Dictionary data | Verdict |
|---|---|---|---|
| [HeliBoard](https://github.com/Helium314/HeliBoard) | Apache-2.0 | [Helium314/aosp-dictionaries](https://codeberg.org/Helium314/aosp-dictionaries): the `main_*` lists are AOSP LatinIME dictionaries (Apache-2.0) | The import path: `wordlist.combined` with per-word frequency and flags |
| [FUTO Keyboard](https://github.com/futo-org/android-keyboard) | FUTO Source First 1.1, not OSI-open | Same terms | Rejected: incompatible with open redistribution |
| [FlorisBoard](https://github.com/florisboard/florisboard) | Apache-2.0 | No frequency word lists | Nothing to import |

`tools/generate_assets.py --merge-aosp <wordlist.combined>` merges an AOSP list into a primary word
list; the same merge runs on the phone from Settings > Words and learning > Dictionary, written to app storage and loaded
in place of the bundled list. Abbreviations, offensive entries and spellings that drop a bundled
word's accents are left out. The bundled lists stay OpenSubtitles and Tatoeba: AOSP's Italian list,
merged in, decoded worse in the project's tests. An AOSP merge is Apache-2.0; this section is its
notice.

### Personal weighting

`personal_boost = 1 + 0.15 * ln(1 + personal_count)`, weighted by the candidate's fit like every
other multiplier.

- Every commit adds to the word's count for its language; counts never mix across languages. A
  correction on the bar moves the count to the replacement.
- 20 commits (`1 + 0.15 * ln(21) = 1.46`) overturn the frequency gap between a common word and a
  middling one; the logarithm keeps any one word from taking over.
- Badges show up to 7 dots; each tier doubles the count (1, 2, 4 ... 64), which matches the
  logarithm. A slide on the bar moves one tier per step.
- Learned words merge into the trie at load, so a word the dictionary lacks becomes a full
  candidate.

## License and credit

Duet is free software under the GNU General Public License, version 3 or (at your option) any
later version. The full text is in [LICENSE](LICENSE); the SPDX identifier is `GPL-3.0-or-later`.

Kinetica is Copyright (C) 2026 Elia Zanella. Duet's changes are listed in this repository's commit
history. The bundled dictionary data carries its own licences (MIT for FrequencyWords, CC BY 2.0
FR for Tatoeba, and the SCOWL and C4 terms); the attributions are in
[THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES) and in the app under Settings > About Duet > Open-source licenses.
Shipping that file meets them.

If you redistribute Duet, the GPL asks you to keep the licence notice, state your changes and make
the source available to whoever receives your build. Kinetica asks forks to use their own name and
icon; please do the same for a fork of Duet.

If Kinetica is useful to you, its author accepts tips at [ko-fi.com/ez_eta](https://ko-fi.com/ez_eta).

## Contributing

[CONTRIBUTING.md](CONTRIBUTING.md) has the build, test and lint gate, the commit format and the
test rules; [ADDING_A_LANGUAGE.md](ADDING_A_LANGUAGE.md) is the recipe for a new language. A change
that would help Kinetica too is worth offering upstream.
