#!/usr/bin/env python3
"""
UCLA seat watcher. Polls the PUBLIC Schedule of Classes (no MyUCLA / Duo),
detects newly enrollable sections, and notifies via ntfy push, email, and an
optional SMS gateway. Stdlib only.

Usage:
  python watch.py               # normal run (notify on new openings, save state)
  python watch.py --dry-run     # print current status; no notify, no state write
  python watch.py --test-notify # send a test message to every configured channel

Config: config.json. Secrets come from environment variables:
  NTFY_TOPIC   ntfy.sh topic name (push to phone). Treat it like a password.
  NTFY_SERVER  optional, default https://ntfy.sh
  SMTP_USER    sending Gmail address (personal Gmail recommended)
  SMTP_PASS    Gmail app password (16 chars), NOT your normal password
  SMTP_HOST    optional, default smtp.gmail.com
  SMTP_PORT    optional, default 465 (SSL)
  EMAIL_TO     comma-separated recipients
  SMS_TO       optional carrier email-to-SMS address(es), comma-separated
"""
import html
import json
import os
import re
import smtplib
import ssl
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from email.message import EmailMessage
from pathlib import Path

BASE = "https://sa.ucla.edu/ro/public/soc"
UA = ("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/130.0 Safari/537.36")
HERE = Path(__file__).resolve().parent
CONFIG_PATH = HERE / "config.json"
STATE_PATH = HERE / "state.json"
FAIL_ALERT_AFTER = 3          # consecutive failed runs before a "watcher broken" alert
FILTER_FLAGS = {"enrollment_status": "O,W,C,X,T,S", "advanced": "n"}


# ---------------------------------------------------------------- fetching
def http_get(url, ajax=False, tries=3):
    headers = {"User-Agent": UA, "Accept": "text/html,*/*;q=0.8"}
    if ajax:
        headers["X-Requested-With"] = "XMLHttpRequest"
    last = None
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.read().decode("utf-8", "replace")
        except Exception as e:  # network blips, 5xx
            last = e
            time.sleep(4 * (i + 1))
    raise RuntimeError(f"GET failed after {tries} tries: {url} ({last})")


def norm_catlg(c):
    """'7B' -> '0007B', '5HA' -> '0005HA', 'M51A' -> '0051A M', 'C122' -> '0122  C'.
    Already-padded values ('0007B') pass through."""
    c = c.strip().upper()
    m = re.fullmatch(r"([A-Z]*)\s*0*(\d+)\s*([A-Z]*)", c.replace(" ", "")) if not re.fullmatch(r"\d{4}.*", c) else None
    if not m:
        return c
    pre, num, suf = m.groups()
    out = f"{int(num):04d}{suf}"
    return f"{out:<6}{pre}" if pre else out


def results_url(term, subj, catlg):
    q = {"t": term, "sBy": "subject", "subj": subj, "catlg": norm_catlg(catlg),
         "cls_no": "%", "btnIsInIndex": "btn_inIndex"}
    return f"{BASE}/Results?" + urllib.parse.urlencode(q)


def children_url(model):
    q = {"model": json.dumps(model, separators=(",", ":")),
         "FilterFlags": json.dumps(FILTER_FLAGS, separators=(",", ":"))}
    return f"{BASE}/Results/GetCourseSummary?" + urllib.parse.urlencode(q)


# ---------------------------------------------------------------- parsing
def clean(fragment):
    t = re.sub(r"<br\s*/?>", " ", fragment)
    t = re.sub(r"<[^>]+>", " ", t)
    return re.sub(r"\s+", " ", html.unescape(t)).strip()


def parse_rows(page):
    """Return [{id, label, status, waitlist}] for every section row in an HTML blob."""
    rows = []
    for m in re.finditer(r'for="([^"]+)-checkbox" class="screenReaderOnly">Select ([^<]*)</label>', page):
        rid, full = m.group(1), html.unescape(m.group(2)).strip()
        lab = re.search(r"\b([A-Z][a-z]{1,3}) (\w+)$", full)  # "Lec 1", "Lab 2D", "Dis 3A", "Sem 1"
        label = f"{lab.group(1)} {lab.group(2)}" if lab else full
        st = re.search(rf'id="{re.escape(rid)}-status_data">(.*?)</div>', page, re.S)
        wl = re.search(rf'id="{re.escape(rid)}-waitlist_data">(.*?)</div>', page, re.S)
        rows.append({"id": rid, "label": label,
                     "status": clean(st.group(1)) if st else "?",
                     "waitlist": clean(wl.group(1)) if wl else "?"})
    return rows


def availability(row):
    """('open', n) | ('waitlist', n) | ('none', 0)."""
    s, w = row["status"], row["waitlist"]
    if s.startswith("Open"):
        m = re.search(r"(\d+) Spots? Left", s)
        return ("open", int(m.group(1)) if m else 1)
    m = re.search(r"(\d+) of (\d+) Taken", w)
    if m and int(m.group(1)) < int(m.group(2)) and not s.startswith(("Cancelled", "Closed by Dept")):
        return ("waitlist", int(m.group(2)) - int(m.group(1)))
    return ("none", 0)


def scan_course(term, w):
    """Return (status_time, lectures) where lectures = [{row, children:[row]}]."""
    page = http_get(results_url(term, w["subj"], w["catlg"]))
    as_of = re.search(r"Status as of ([0-9:]+\s*[AP]M)", page)
    models = {m.group(1): json.loads(m.group(2)) for m in
              re.finditer(r'AddToCourseData\("([^"]+)",(\{.*?\})\)', page)}
    rows = {r["id"]: r for r in parse_rows(page)}
    if not rows:
        raise RuntimeError(f"{w['name']}: no sections parsed (page layout changed, or wrong subj/catlg?)")
    lectures = []
    for rid, row in rows.items():
        only = w.get("only_primary")
        if only and row["label"] not in only:
            continue
        kids = []
        if rid in models:
            frag = http_get(children_url(models[rid]), ajax=True)
            kids = [r for r in parse_rows(frag) if r["id"] != rid]
            time.sleep(0.5)  # be polite
        lectures.append({"row": row, "children": kids})
    return (as_of.group(1) if as_of else "?"), lectures


def opportunities(w, lectures):
    """Keys of enrollable options + human-readable lines."""
    accept = {"open"} if w.get("accept", "open") == "open" else {"open", "waitlist"}
    excl = set(w.get("exclude_secondary", []))
    found, partial = {}, {}
    for lec in lectures:
        lrow = lec["row"]
        la, ln = availability(lrow)
        kids = [k for k in lec["children"] if k["label"] not in excl]
        if lec["children"]:  # need lecture + a secondary section
            open_kids = []
            for k in kids:
                ka, kn = availability(k)
                if ka in accept:
                    open_kids.append(k["label"])
                if la in accept and ka in accept:
                    key = f"{w['name']}|{lrow['label']}|{k['label']}|{la}/{ka}"
                    found[key] = (f"{w['name']} {lrow['label']} ({la} {ln}) + {k['label']} ({ka} {kn})"
                                  f" — {k['status']} / {k['waitlist']}")
            if la in accept and not open_kids:
                partial[f"{w['name']}|{lrow['label']}|lec-only"] = (
                    f"{w['name']} {lrow['label']} has space ({la} {ln}) but all its sections are full")
            elif la not in accept and open_kids:
                partial[f"{w['name']}|{lrow['label']}|sec-only|{','.join(open_kids)}"] = (
                    f"{w['name']} {', '.join(open_kids)} has space but {lrow['label']} is full")
        elif la in accept:
            key = f"{w['name']}|{lrow['label']}|{la}"
            found[key] = f"{w['name']} {lrow['label']}: {lrow['status']} / {lrow['waitlist']}"
    return found, partial


# ---------------------------------------------------------------- notify
def notify(title, body, sms_body, priority="high", click=None):
    sent = []
    topic = os.getenv("NTFY_TOPIC")
    if topic:
        server = os.getenv("NTFY_SERVER", "https://ntfy.sh").rstrip("/")
        hdr = {"Title": title.encode("ascii", "ignore").decode(), "Priority": priority,
               "Tags": "rotating_light" if priority in ("high", "urgent") else "information_source"}
        if click:
            hdr["Click"] = click
        req = urllib.request.Request(f"{server}/{topic}", data=body.encode(), headers=hdr, method="POST")
        try:
            urllib.request.urlopen(req, timeout=20).read()
            sent.append("ntfy")
        except Exception as e:
            print(f"[warn] ntfy failed: {e}", file=sys.stderr)

    user, pw = os.getenv("SMTP_USER"), os.getenv("SMTP_PASS")
    to = [a.strip() for a in os.getenv("EMAIL_TO", "").split(",") if a.strip()]
    sms = [a.strip() for a in os.getenv("SMS_TO", "").split(",") if a.strip()]
    if user and pw and (to or sms):
        host, port = os.getenv("SMTP_HOST", "smtp.gmail.com"), int(os.getenv("SMTP_PORT", "465"))
        try:
            with smtplib.SMTP_SSL(host, port, context=ssl.create_default_context(), timeout=30) as s:
                s.login(user, pw)
                if to:
                    m = EmailMessage()
                    m["Subject"], m["From"], m["To"] = title, user, ", ".join(to)
                    m.set_content(body + (f"\n\n{click}" if click else ""))
                    s.send_message(m)
                    sent.append("email")
                for addr in sms:  # gateways truncate ~160 chars; keep it short, no subject
                    m = EmailMessage()
                    m["From"], m["To"] = user, addr
                    m.set_content(sms_body[:155])
                    s.send_message(m)
                    sent.append(f"sms:{addr.split('@')[-1]}")
        except Exception as e:
            print(f"[warn] email/sms failed: {e}", file=sys.stderr)
    print(f"[notify] {title} -> {', '.join(sent) or 'NO CHANNEL CONFIGURED'}")
    return sent


# ---------------------------------------------------------------- main
def load_json(p, default):
    try:
        return json.loads(p.read_text())
    except Exception:
        return default


def main():
    args = set(sys.argv[1:])
    cfg = load_json(CONFIG_PATH, None)
    if cfg is None:
        sys.exit("config.json missing or invalid")
    if "--test-notify" in args:
        notify("UCLA seat watch: test", "Test notification. If you see this, this channel works.",
               "UCLA seat watch test OK", priority="default")
        return

    stop = cfg.get("stop_after")
    if stop and datetime.now(timezone.utc) > datetime.fromisoformat(stop):
        print(f"past stop_after ({stop}); nothing to do")
        return

    dry = "--dry-run" in args
    state = load_json(STATE_PATH, {"found": [], "partial": [], "fails": 0, "fail_alerted": False})
    term = cfg["term"]
    all_found, all_partial, as_of = {}, {}, "?"
    try:
        for w in cfg["watches"]:
            if not w.get("enabled", True):
                continue
            as_of, lectures = scan_course(term, w)
            f, p = opportunities(w, lectures)
            all_found.update(f)
            all_partial.update(p)
            if dry:
                print(f"\n== {w['name']} (status as of {as_of})")
                for lec in lectures:
                    r = lec["row"]
                    print(f"  {r['label']:7} {r['status']}  | {r['waitlist']}")
                    for k in lec["children"]:
                        print(f"     {k['label']:7} {k['status']}  | {k['waitlist']}")
    except Exception as e:
        state["fails"] = state.get("fails", 0) + 1
        print(f"[error] {e}", file=sys.stderr)
        if not dry and state["fails"] >= FAIL_ALERT_AFTER and not state.get("fail_alerted"):
            notify("UCLA seat watch is failing",
                   f"{state['fails']} consecutive failed checks. Last error: {e}\n"
                   "Check the GitHub Actions log. You are NOT being watched until this clears.",
                   "UCLA seat watch FAILING - check GitHub Actions", priority="default")
            state["fail_alerted"] = True
        if not dry:
            STATE_PATH.write_text(json.dumps(state, indent=1, sort_keys=True) + "\n")
        sys.exit(1)

    if state.get("fail_alerted"):
        if not dry:
            notify("UCLA seat watch recovered", "Checks are succeeding again.",
                   "UCLA seat watch recovered", priority="low")
    state["fails"], state["fail_alerted"] = 0, False

    new_found = sorted(set(all_found) - set(state.get("found", [])))
    new_partial = sorted(set(all_partial) - set(state.get("partial", [])))
    print(f"status as of {as_of}: {len(all_found)} enrollable, {len(all_partial)} partial; "
          f"new: {len(new_found)} enrollable, {len(new_partial)} partial")

    click = "https://my.ucla.edu/"
    if new_found and not dry:
        lines = [all_found[k] for k in new_found]
        notify(f"SEAT OPEN: {lines[0].split(' — ')[0][:60]}",
               f"Public SoC status as of {as_of} (can lag up to ~1h; enroll NOW in MyUCLA):\n\n"
               + "\n".join(f"• {l}" for l in lines),
               ("OPEN: " + "; ".join(l.split(" — ")[0] for l in lines))[:155],
               priority="urgent", click=click)
    if new_partial and not dry and cfg.get("notify_partial", True):
        lines = [all_partial[k] for k in new_partial]
        notify("Seat movement (not enrollable yet)",
               f"Status as of {as_of}:\n\n" + "\n".join(f"• {l}" for l in lines),
               ("Partial: " + "; ".join(lines))[:155], priority="default", click=click)

    if not dry:
        state["found"], state["partial"] = sorted(all_found), sorted(all_partial)
        STATE_PATH.write_text(json.dumps(state, indent=1, sort_keys=True) + "\n")


if __name__ == "__main__":
    main()
