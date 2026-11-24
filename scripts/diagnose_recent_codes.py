"""Read-only recent-code report; never print mail content or codes."""
import imaplib
import os
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root))
for line in (root / ".env").read_text().splitlines():
    if "=" not in line or line.lstrip().startswith("#"):
        continue
    key, value = line.split("=", 1)
    os.environ.setdefault(key.strip(), value.strip().strip("\"'"))

from app.main import body_preview, db, dec, is_verification_mail, verification_summary

with db() as con:
    boxes = con.execute("SELECT id,address,secret FROM mailboxes WHERE provider='qq'").fetchall()
for box in boxes:
    with db() as con:
        rows = con.execute("SELECT id,uid,subject,summary FROM reminders WHERE mailbox_id=? ORDER BY id DESC LIMIT 15", (box["id"],)).fetchall()
    client = imaplib.IMAP4_SSL("imap.qq.com", 993, timeout=20)
    try:
        client.login(box["address"], dec(box["secret"]))
        client.select("INBOX", readonly=True)
        for row in rows:
            status, data = client.uid("fetch", row["uid"], "(BODY.PEEK[]<0.131072>)")
            raw = data[0][1] if status == "OK" and data and isinstance(data[0], tuple) else b""
            preview = body_preview(raw) if raw else ""
            extracted = verification_summary({"subject": row["subject"], "body_preview": preview})
            print({"box": box["id"], "uid": row["uid"], "subject_detected": is_verification_mail({"subject": row["subject"]}),
                   "preview_chars": len(preview), "could_extract_code": extracted.startswith("验证码："),
                   "summary_has_code": (row["summary"] or "").startswith("验证码："),
                   "summary_is_fallback": (row["summary"] or "").startswith("验证邮件未找到")})
    finally:
        client.logout()
