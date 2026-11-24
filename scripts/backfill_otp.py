"""Backfill confidently extracted codes in recent historical reminders; print counts only."""
import imaplib
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
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
        value = value[1:-1]
    os.environ.setdefault(key.strip(), value)

from app.main import DB_PATH, body_preview, db, dec, is_verification_mail, verification_summary
import sqlite3

backup_path = Path(DB_PATH).with_name(Path(DB_PATH).name + ".pre-otp-backfill-084")
with db() as source, sqlite3.connect(backup_path) as backup:
    source.backup(backup)

updated = 0
with db() as con:
    boxes = con.execute("SELECT id,address,secret FROM mailboxes WHERE provider='qq'").fetchall()
for box in boxes:
    with db() as con:
        rows = con.execute("SELECT id,uid,subject,summary FROM reminders "
                           "WHERE mailbox_id=? AND created_at>=? ORDER BY id DESC",
                           (box["id"], int(time.time()) - 48 * 3600)).fetchall()
    targets = [row for row in rows if not (row["summary"] or "").startswith("验证码：")]
    if not targets:
        continue
    client = imaplib.IMAP4_SSL("imap.qq.com", 993, timeout=20)
    try:
        client.login(box["address"], dec(box["secret"]))
        client.select("INBOX", readonly=True)
        for row in targets:
            status, data = client.uid("fetch", row["uid"], "(BODY.PEEK[]<0.131072>)")
            raw = data[0][1] if status == "OK" and data and isinstance(data[0], tuple) else b""
            if not raw:
                continue
            item = {"subject": row["subject"], "body_preview": body_preview(raw)}
            if not is_verification_mail(item):
                continue
            summary = verification_summary(item)
            if not summary.startswith("验证码："):
                continue
            with db() as con:
                con.execute("UPDATE reminders SET summary=? WHERE id=?",
                            (summary + "（历史邮件，可能已过期）", row["id"]))
            updated += 1
    finally:
        client.logout()
print({"updated_historical_reminders": updated})
