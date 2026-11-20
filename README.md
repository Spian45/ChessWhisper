# Chess Whisper (Android)

Hears nothing, says everything: the app tells you the best move in your earbud, and you
enter your friend's moves with the **volume buttons**, phone in your pocket, screen off.

## Buttons

| Button | What it does |
|---|---|
| **Volume UP** | Next: A → B → C … H (then back to A), or 1 → 2 → … 8 |
| **Volume DOWN** | Choose this one / "yes, I played it" / "yes, that's their move" |
| **Volume DOWN ×2** (quick) | Undo |
| **Volume DOWN ×3** (quick) | Repeat your move |
| **Volume DOWN ×4** (quick) | I played a different move (misheard / blunder) → enter what you really played |
| Volume UP while it's waiting for "yes" | Say it again now |
| Earbud 1 tap (play/pause) | Same as Volume DOWN (choose / yes) |
| Earbud 2 taps (next track) | Same as Volume UP (next) |
| Earbud 3 taps (previous track) | Undo |

## One round

1. Your turn: *"Knight… F… 3"* — it repeats every 6 s. Play it, press **Vol DOWN**.
2. Friend's turn: *"Their move. A"* → press **Vol UP** until you hear the column, **Vol DOWN**.
   Then the row the same way. If two pieces could go there it asks *"Pawn? / Knight?"*.
3. It reads the move back (*"Bishop from F 8, takes your Knight on C 5"*), repeating until
   you press **Vol DOWN** (right) or **Vol DOWN ×2** (wrong → enter it again).

Castling: enter the square the **king** lands on (G1/C1 or G8/C8).

## When do my volume buttons go back to normal?

The app only takes the volume buttons while a game is running (you'll see the
"Chess Whisper is listening" notification). They go back to normal when:
- you press **Stop** (in the app or on the notification),
- the game ends (checkmate or draw) — about 6 seconds after it announces the result,
- you swipe the app away from recent apps.

Your game is saved, so **Resume** brings it back.

## Build the APK

**Android Studio (easiest):** File → Open → this folder → wait for Gradle sync →
Build → Build App Bundle(s) / APK(s) → Build APK(s). Install
`app/build/outputs/apk/debug/app-debug.apk` on your phone.

**GitHub (no Android Studio):** push this folder to a GitHub repo. The included workflow
(`.github/workflows/build.yml`) builds it; download the APK from the run's *Artifacts*.

Requirements: Android 7.0+ (API 24). First start asks for notification permission —
allow it, it's how Android keeps the app alive with the screen off.

## Hide the volume slider

With the screen **on**, Android pops up the volume slider when you press the buttons.
To stop that: in the app tap **Open Accessibility settings** → *Installed apps* →
**Chess Whisper – hide volume slider** → On. (If it's greyed out: App info → ⋮ →
*Allow restricted settings*.) It only catches the volume buttons, only during a game.
With the screen **off** nothing shows anyway.

## Phone settings that help

- Battery: set Chess Whisper to **Unrestricted / Don't optimize** (Xiaomi, Oppo, Vivo,
  Samsung kill background apps aggressively).
- Don't play music in another app at the same time (it would take the volume buttons).

## Credits / licenses

- Engine: CuckooChess by Peter Österlund — GPLv3 (see `COPYING-CuckooChess.txt`).
  Because of this, the whole app is GPLv3.
- Piece images: "cburnett" by Colin M.L. Burnett — CC BY-SA 3.0.
- Opening names: lichess-org/chess-openings — CC0.
