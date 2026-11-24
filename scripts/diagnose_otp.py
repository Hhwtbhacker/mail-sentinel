"""Read-only OTP extraction diagnostic. Run from the project root on the server."""
import email
import imaplib
import os
import re
import sys
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

from app.main import TextOnly, body_preview, db, dec, is_verification_mail, verification_summary
from email import policy

with db() as con:
    box = con.execute("SELECT id,address,secret FROM mailboxes WHERE provider='qq' LIMIT 1").fetchone()
    rows = con.execute("SELECT uid,subject,summary FROM reminders WHERE mailbox_id=? ORDER BY id DESC LIMIT 15",
                       (box["id"],)).fetchall()

imap = imaplib.IMAP4_SSL("imap.qq.com", 993, timeout=20)
try:
    imap.login(box["address"], dec(box["secret"]))
    imap.select("INBOX", readonly=True)
    for row in rows:
        subject = row["subject"].lower()
        likely = any(term in subject for term in ("验证", "代码", "code", "authentication", "confirm", "登录"))
        if not likely:
            continue
        status, data = imap.uid("fetch", row["uid"], "(BODY.PEEK[]<0.131072>)")
        raw = data[0][1] if status == "OK" and data and isinstance(data[0], tuple) else b""
        preview = body_preview(raw) if raw else ""
        parts = []
        for part in email.message_from_bytes(raw, policy=policy.default).walk():
            if part.get_content_type() not in ("text/plain", "text/html") or part.get_content_disposition() == "attachment":
                continue
            try:
                content = part.get_content()
                if part.get_content_type() == "text/html":
                    parser = TextOnly(); parser.feed(content); content = " ".join(parser.parts)
                parts.append((part.get_content_type(), " ".join(content.split())))
            except Exception:
                pass
        combined = " ".join(value for _, value in parts)
        detected = is_verification_mail({"subject": row["subject"]})
        extracted = verification_summary({"body_preview": preview}).startswith("验证码：")
        print({"uid": row["uid"], "subject_detected": detected,
               "raw_bytes": len(raw), "preview_chars": len(preview),
               "code_extracted": extracted, "existing_code_summary": (row["summary"] or "").startswith("验证码："),
               "number_candidates": len(re.findall(r"(?<!\d)\d{4,8}(?!\d)", preview))})
        print("  parts:", [(kind, len(value)) for kind, value in parts],
              "combined_code", verification_summary({"body_preview": combined}).startswith("验证码："))
        if row["uid"] in ("563", "550", "547"):
            for candidate in re.finditer(r"(?<!\d)\d{4,8}(?!\d)", preview):
                context = preview[max(0, candidate.start() - 25):candidate.end() + 25]
                context = re.sub(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+", "<email>", context)
                context = re.sub(r"https?://\S+", "<url>", context)
                context = re.sub(r"\d", "#", context)
                print("  candidate_context:", context)
finally:
    imap.logout()
