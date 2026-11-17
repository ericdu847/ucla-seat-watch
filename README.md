# UCLA Seat Watch

Free, 24/7 alerts when a seat opens in a UCLA class. It works with your laptop and phone off.

- **Cloud checker** (`watch.py`, GitHub Actions): every ~10 min it reads UCLA's **public** Schedule
  of Classes (no MyUCLA login, no Duo) and detects newly enrollable sections. For classes with labs
  or discussions, that means a lecture **and** one of its sections both have space.
- **Android app** (`android/`): pick which classes to watch, set your alert email, and get the
  notifications. It sends nothing to anyone but you.
- **Cost: $0.** GitHub Actions is free for public repos, ntfy.sh push is free, and Gmail SMTP is free.
  Your email address and passwords are stored as encrypted GitHub secrets, never in this public repo.

## Install the app
1. On your Android phone, download **[SeatWatch.apk](https://github.com/ericdu847/ucla-seat-watch/raw/main/apk/SeatWatch.apk)**
   and open it. Allow "install unknown apps" for your browser when asked.
2. Open **Seat Watch**, tap **Create GitHub token**. Under *Repository access* choose
   *Only select repositories → ucla-seat-watch*. The permissions are already filled in.
   Tap **Generate token**, copy it, paste it into the app, and tap **Connect**.
3. Allow notifications. Tap **Don't let Android pause it** so alerts aren't delayed.
4. Optional email: enter where to send alerts, plus a Gmail sender and its free
   [app password](https://myaccount.google.com/apppasswords). UCLA Gmail may block app passwords;
   a personal Gmail works.
5. Tap **Send test alert**. Within ~1–2 min you should get a push notification (and an email if set up).

## How alerts reach you
`watch.py` → ntfy.sh topic (random, secret) → the app. Two delivery paths:
- **Instant alerts** (default): the app keeps a quiet connection to ntfy. Android shows a small
  "Watching for open seats" notification while this is on.
- **Fallback**: every ~15 min the app fetches anything it missed. If your phone was off, alerts
  from the last 12 h arrive when it turns back on.

## Limits
- UCLA's public seat counts update **about once an hour**. Treat an alert as "go to MyUCLA now",
  not as a guaranteed seat. Classes with no waitlist go first-come.
- GitHub may run the 10-min schedule a few minutes late. GitHub also pauses scheduled workflows in
  repos with no commits for 60 days. Editing classes in the app, or any alert, counts as activity.
- If UCLA changes its page or blocks GitHub, the checker sends a **"watcher is failing"** alert
  after 3 failed runs in a row.

## Files
| Path | What |
|---|---|
| `watch.py` | cloud checker (Python stdlib only) |
| `config.json` | watched classes. The app edits this; editing by hand also works |
| `state.json` | which openings were already alerted (written by the workflow) |
| `.github/workflows/watch.yml` | runs the checker every 10 min, plus the app's "test" button |
| `android/` | app source (Kotlin / Jetpack Compose) |

Manual checks: `python3 watch.py --dry-run` prints every section's status.
