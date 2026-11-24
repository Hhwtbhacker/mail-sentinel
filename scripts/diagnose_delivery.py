"""Read-only mailbox latency report without subjects, addresses, or credentials."""
import imaplib
import email
import os
import sys
import time
from pathlib import Path

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root))
for line in (root / ".env").read_text().splitlines():
    if "=" not in line or line.lstrip().startswith("#"):
        continue
    key, value = line.split("=", 1)
    value = value.strip().strip("\"'")
    os.environ.setdefault(key.strip(), value)

from app.main import db, dec

now = int(time.time())
with db() as con:
    boxes = con.execute("SELECT id,address,secret,provider,last_check,next_check,last_error FROM mailboxes").fetchall()
    for box in boxes:
        latest = con.execute("SELECT uid,created_at FROM reminders WHERE mailbox_id=? ORDER BY id DESC LIMIT 1", (box["id"],)).fetchone()
        processed = con.execute("SELECT COUNT(*) AS n, MAX(CAST(uid AS INTEGER)) AS max_uid FROM processed_messages WHERE mailbox_id=?", (box["id"],)).fetchone()
        print({"box_id": box["id"], "provider": box["provider"],
               "last_check_age_s": now - box["last_check"] if box["last_check"] else None,
               "next_check_in_s": box["next_check"] - now if box["next_check"] else None,
               "error": box["last_error"], "latest_reminder_age_s": now - latest["created_at"] if latest else None,
               "latest_reminder_uid": latest["uid"] if latest else None,
               "processed_count": processed["n"], "processed_max_uid": processed["max_uid"]})
        if box["provider"] != "qq":
            continue
        client = imaplib.IMAP4_SSL("imap.qq.com", 993, timeout=20)
        try:
            client.login(box["address"], dec(box["secret"]))
            client.select("INBOX", readonly=True)
            for search_key in ("UNSEEN", "ALL"):
                status, data = client.uid("search", None, search_key)
                uids = data[0].split() if status == "OK" and data else []
                print({"box_id": box["id"], "search": search_key, "count": len(uids),
                       "last_uid": uids[-1].decode() if uids else None})
                if search_key == "ALL":
                    for uid in uids[-5:]:
                        status, raw = client.uid("fetch", uid, "(INTERNALDATE FLAGS BODY.PEEK[HEADER.FIELDS (DATE)])")
                        if status != "OK" or not raw or not isinstance(raw[0], tuple):
                            continue
                        stamp = imaplib.Internaldate2tuple(raw[0][0])
                        arrival = int(time.mktime(stamp)) if stamp else None
                        flags = imaplib.ParseFlags(raw[0][0])
                        with db() as con:
                            reminded = con.execute("SELECT COUNT(*) FROM reminders WHERE mailbox_id=? AND uid=?", (box["id"], uid.decode())).fetchone()[0]
                            done = con.execute("SELECT COUNT(*) FROM processed_messages WHERE mailbox_id=? AND uid=?", (box["id"], uid.decode())).fetchone()[0]
                        print({"uid": uid.decode(), "arrival_age_s": now - arrival if arrival else None,
                               "seen": b"\\Seen" in flags, "processed": bool(done), "reminded": bool(reminded)})
        finally:
            client.logout()
