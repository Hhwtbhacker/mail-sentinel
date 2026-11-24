"""Requeue recently arrived messages that the former Agent filtered out."""
import os
import imaplib
import sqlite3
import sys
import time
from pathlib import Path

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root))
for line in (root / ".env").read_text().splitlines():
    if "=" not in line or line.lstrip().startswith("#"):
        continue
    key, value = line.split("=", 1)
    os.environ.setdefault(key.strip(), value.strip().strip("\"'"))

from app.main import DB_PATH, db, dec

backup_path = Path(DB_PATH).with_name(Path(DB_PATH).name + ".pre-all-mail-scan")
with db() as source, sqlite3.connect(backup_path) as backup:
    source.backup(backup)

now = int(time.time())
removed = 0
with db() as con:
    boxes = con.execute("SELECT id,provider,address,secret FROM mailboxes").fetchall()
    for box in boxes:
        if box["provider"] != "qq":
            continue
        rows = con.execute("SELECT uid FROM processed_messages WHERE mailbox_id=? AND NOT EXISTS "
                           "(SELECT 1 FROM reminders WHERE reminders.mailbox_id=processed_messages.mailbox_id "
                           "AND reminders.uid=processed_messages.uid) ORDER BY CAST(uid AS INTEGER) DESC LIMIT 10",
                           (box["id"],)).fetchall()
        if not rows:
            continue
        client = imaplib.IMAP4_SSL("imap.qq.com", 993, timeout=20)
        try:
            client.login(box["address"], dec(box["secret"]))
            client.select("INBOX", readonly=True)
            for row in rows:
                status, data = client.uid("fetch", row["uid"], "(INTERNALDATE)")
                if status != "OK" or not data:
                    continue
                stamp = imaplib.Internaldate2tuple(data[0][0] if isinstance(data[0], tuple) else data[0])
                arrival = int(time.mktime(stamp)) if stamp else 0
                if not 0 <= now - arrival <= 30 * 60:
                    continue
                con.execute("DELETE FROM processed_messages WHERE mailbox_id=? AND uid=?", (box["id"], row["uid"]))
                removed += 1
        finally:
            client.logout()
print({"requeued_recent_missed_messages": removed})
