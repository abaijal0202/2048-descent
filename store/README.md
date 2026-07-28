# Play Store assets

```
play-store-listing.md     App name, short and full description, console form notes
generate_assets.py        Regenerates every PNG in assets/
validate_boards.py        Checks the mocked boards are states the engine could reach
assets/                   The generated PNGs
```

Regenerate after any palette or board-size change:

```
python store/validate_boards.py && python store/generate_assets.py
```

`generate_assets.py` mirrors the colour tokens in `ui/theme/Color.kt` and the layout in
`ui/GameScreen.kt`. `validate_boards.py` asserts the same two invariants the engine fuzz
test does — no touching equal tiles, no tile hovering over a gap — so the artwork can't
show a position the game would have resolved away. The Plan screenshot is exempt from
both, because during a Plan window the engine runs no resolver at all.

Requires Pillow (`pip install pillow`) and the Segoe UI fonts that ship with Windows.

---

## Read this before you upload the screenshots

**`screen_*.png` are renders, not captures from the running app.** I could not launch the
app to photograph it, so these were reconstructed from the same colour and layout
constants the app uses. They are accurate about what the game contains — every feature
shown is real and every board is a legal position — but they are not the real thing:

- the app renders in Compose's default typeface, these use Segoe UI
- spacing is reconstructed from the dp values in `GameScreen.kt`, not measured
- there is no status bar, and no real device chrome

Google Play requires screenshots to represent actual in-app experience, so **replace
these with real captures before you publish**. You have the APK, so it is a five-minute
job:

```
adb shell screencap -p /sdcard/shot.png && adb pull /sdcard/shot.png
```

Treat these as an art-direction reference and a placeholder for laying out the listing —
they show which five moments are worth capturing and in what order.

**The icon and feature graphic are a different matter.** Those are promotional artwork,
not depictions of a screen, so they are legitimate to ship as-is.

---

## Asset inventory

| File | Size | Play Console slot | Ship as-is? |
|---|---|---|---|
| `icon_512.png` | 512 x 512 | App icon | Yes |
| `feature_graphic_1024x500.png` | 1024 x 500 | Feature graphic | Yes |
| `screen_1_play.png` | 1080 x 2160 | Phone screenshot 1 | Replace with a real capture |
| `screen_2_plan.png` | 1080 x 2160 | Phone screenshot 2 | Replace with a real capture |
| `screen_3_delete.png` | 1080 x 2160 | Phone screenshot 3 | Replace with a real capture |
| `screen_4_trophy.png` | 1080 x 2160 | Phone screenshot 4 | Replace with a real capture |
| `screen_5_danger.png` | 1080 x 2160 | Phone screenshot 5 | Replace with a real capture |

Screenshots are 1080 x 2160 — exactly 2:1, the tallest ratio Play accepts for phone
screenshots. A real 1080 x 2400 device capture is 2.22:1 and **will be rejected**, so crop
or letterbox your captures before uploading.

Suggested order in the listing, so the first two thumbnails carry the pitch:

1. **Core play** — the landing guide lit cyan over a merge, so the mechanic reads instantly
2. **Plan** — the differentiator, with the timer bar and slide arrows visible
3. **Delete Row** — targeting mode, showing powers are player-directed
4. **Trophy** — the payoff and the ladder continuing to 4096
5. **Danger** — stakes

## Still outstanding

- **The in-app launcher icon is still the placeholder vector** in
  `res/drawable/ic_launcher.xml`. `icon_512.png` is the Play *listing* icon, which is a
  different asset. For the launcher you need an adaptive icon: a foreground and background
  layer with the artwork inside the central 66% safe zone, via Image Asset Studio.
- No tablet screenshots. Play does not require them for a phone-only release, but the
  listing is downranked on tablets without them, and the app is portrait-locked.
