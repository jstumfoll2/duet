# Site media

The page shows a labelled placeholder for any file missing here. Screenshots come from the
"Website media" workflow (.github/workflows/site-media.yml), which runs the DEV build on an
Android emulator (SiteMediaTour) and pushes the results to branch `assets/site-media`.
Videos are recorded on a real phone, because scripted two-thumb swipes look stiff on the emulator.

| File | What it shows | Source |
|---|---|---|
| `duet-demo.mp4` + `demo-poster.webp` | Typing a few sentences with both thumbs | phone |
| `duet-spacing.mp4` + `spacing-poster.webp` | Peck a word, swipe the next; one backspace splits a joined word | phone |
| `keyboard.webp` | The keyboard in its default theme, shortcut row open | emulator |
| `grammar.webp` | Harper grammar row with a mistake underlined | emulator |
| `clipboard.webp` | A copied address offered as a chip on the bar | emulator |
| `themes-{ocean,sunset,paper,lavender,forest}.webp` | Theme presets | emulator |

Screenshots are 540 px wide WebP. Keep each image under ~150 KB and each video under ~4 MB
(H.264 MP4, muted).
