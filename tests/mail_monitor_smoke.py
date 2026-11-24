"""Run with the project's virtualenv: python tests/mail_monitor_smoke.py."""
import asyncio
import os
import sys
import tempfile
import time
from pathlib import Path
from unittest.mock import AsyncMock, patch

from cryptography.fernet import Fernet

sys.path.insert(0, str(Path.cwd()))

with tempfile.TemporaryDirectory() as temporary:
    os.environ["DB_PATH"] = str(Path(temporary) / "test.db")
    os.environ["DATA_ENCRYPTION_KEY"] = Fernet.generate_key().decode()
    os.environ["SESSION_SECRET"] = "mail-monitor-smoke"

    from app.main import _check_box, close_imap_client, db, enc, fetch_unseen, init_db, is_verification_mail, verification_summary, CHECK_INTERVAL_SECONDS, ENRICH_TASKS

    init_db()
    now = int(time.time())
    with db() as con:
        con.execute("INSERT INTO users(id,email,password_hash,api_key,created_at) VALUES (1,'a@example.com','test',?,?)",
                    (enc("test-api-key"), now))
        con.execute("INSERT INTO mailboxes(id,user_id,provider,address,secret,interval_minutes,next_check) "
                    "VALUES (1,1,'qq','sender@qq.com',?,60,?)", (enc("test-mail-code"), now))
        box = dict(con.execute("SELECT * FROM mailboxes WHERE id=1").fetchone())

    otp = {"uid": "101", "sender": "service@example.com", "subject": "登录验证码", "date": "", "recent": True,
           "body_preview": "您的验证码为 123456，请勿透露。"}
    bare_code = {"uid": "103", "sender": "service@example.com", "subject": "hi", "date": "", "recent": True,
                 "body_preview": "1234567"}
    normal = {"uid": "102", "sender": "work@example.com", "subject": "会议安排", "date": "", "recent": True}
    assert is_verification_mail(otp)
    assert is_verification_mail(bare_code)
    assert verification_summary(bare_code) == "验证码：1234567"
    assert not is_verification_mail({"subject": "账单", "body_preview": "订单 123456"})
    assert is_verification_mail({"subject": "你的临时 ChatGPT 登录代码"})
    assert verification_summary(otp) == "验证码：123456"
    assert verification_summary({"subject": "你的临时 ChatGPT 登录代码", "body_preview": "登录代码：482019"}) == "验证码：482019"
    assert not verification_summary({"subject": "电子邮件验证", "body_preview": "https://example.com/verify?id=123456"}).startswith("验证码：")
    assert CHECK_INTERVAL_SECONDS == 5
    def slow_agent(messages, key):
        with db() as con:
            assert con.execute("SELECT COUNT(*) FROM reminders").fetchone()[0] == 3
        return {"102": ("normal", "需要查看", "邮件要点")}, {"input_tokens": 6, "output_tokens": 2, "total_tokens": 8}

    with patch("app.main.access_token", new=AsyncMock(return_value="test-mail-code")), \
         patch("app.main.fetch_unseen", return_value=[otp, normal, bare_code]) as fetch, \
         patch("app.main.triage", side_effect=slow_agent) as agent, \
         patch("app.main.push_to_user") as push:
        async def run_check():
            await _check_box(box)
            await asyncio.gather(*ENRICH_TASKS)
        asyncio.run(run_check())
        assert agent.call_count == 1 and agent.call_args.args[0] == [normal]
        assert fetch.call_count == 1
        assert push.call_count == 3
        assert all(call.args[1]["address"] == "sender@qq.com" for call in push.call_args_list)

    with db() as con:
        rows = con.execute("SELECT uid,summary FROM reminders ORDER BY uid").fetchall()
        assert [(row["uid"], row["summary"]) for row in rows] == [("101", "验证码：123456"), ("102", "邮件要点"), ("103", "验证码：1234567")]
        assert con.execute("SELECT COUNT(*) FROM token_usage").fetchone()[0] == 1
        assert con.execute("SELECT COUNT(*) FROM processed_messages").fetchone()[0] == 3
        assert con.execute("SELECT next_check FROM mailboxes WHERE id=1").fetchone()[0] <= int(time.time()) + 5
    class FakeImap:
        searches = []
        def login(self, address, credential): return "OK", []
        def select(self, folder, readonly=False): return "OK", []
        def uid(self, command, *args):
            if command == "search": self.searches.append(args[-1])
            return "OK", [b""]
        def logout(self): return "BYE", []

    with patch("app.main.imaplib.IMAP4_SSL", return_value=FakeImap()) as connect:
        assert fetch_unseen(box, "test-mail-code") == []
        assert fetch_unseen(box, "test-mail-code") == []
        assert connect.call_count == 1
        assert connect.return_value.searches == ["ALL", "ALL"]
    close_imap_client(1)
    print("five-second scheduling, OTP summary, and new-mail-only Agent call passed")
