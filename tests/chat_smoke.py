"""Run with the project's virtualenv: python tests/chat_smoke.py."""
import os
import sys
import tempfile
import time
from pathlib import Path
from unittest.mock import patch

from cryptography.fernet import Fernet

sys.path.insert(0, str(Path.cwd()))

with tempfile.TemporaryDirectory() as temporary:
    os.environ["DB_PATH"] = str(Path(temporary) / "test.db")
    os.environ["DATA_ENCRYPTION_KEY"] = Fernet.generate_key().decode()
    os.environ["SESSION_SECRET"] = "chat-smoke-secret"

    from fastapi.testclient import TestClient
    from app.main import app, chat_agent, db, digest, enc, init_db, send_qq_mail
    from langchain_core.messages import AIMessage

    init_db()
    now = int(time.time())
    with db() as con:
        con.execute("INSERT INTO users(id,email,password_hash,created_at) VALUES (1,'a@example.com','test',?)", (now,))
        con.execute("INSERT INTO users(id,email,password_hash,created_at) VALUES (2,'b@example.com','test',?)", (now,))
        con.execute("INSERT INTO sessions(token_hash,user_id,expires_at) VALUES (?,?,?)", (digest("a-token"), 1, now + 3600))
        con.execute("INSERT INTO sessions(token_hash,user_id,expires_at) VALUES (?,?,?)", (digest("b-token"), 2, now + 3600))
        con.execute(
            "INSERT INTO chat_messages(user_id,role,content,model,effort,input_tokens,output_tokens,total_tokens,created_at) "
            "VALUES (1,'assistant','private','deepseek-flash','low',10,4,14,?)", (now,)
        )
        con.execute("UPDATE users SET api_key=? WHERE id=1", (enc("test-api-key"),))
        con.execute("INSERT INTO mailboxes(id,user_id,provider,address,secret,interval_minutes,next_check) "
                    "VALUES (1,1,'qq','sender@qq.com',?,30,?)", (enc("test-mail-code"), now + 3600))

    client = TestClient(app)
    a = {"Authorization": "Bearer a-token"}
    b = {"Authorization": "Bearer b-token"}
    assert client.get("/api/chat").status_code == 401
    assert [row["content"] for row in client.get("/api/chat", headers=a).json()] == ["private"]
    assert client.get("/api/chat", headers=b).json() == []
    assert client.get("/api/usage", headers=a).json()["total_tokens"] == 14
    assert client.get("/api/usage", headers=b).json()["total_tokens"] == 0
    assert client.post("/api/chat", headers=b, json={"message": "hello"}).status_code == 422
    assert client.post("/api/chat", headers=a, json={"message": "hello", "model": "bad"}).status_code == 422
    class StubAgent:
        def invoke(self, state, config):
            assert state["messages"][-1]["content"] == "hello"
            return {"messages": [AIMessage(content="ok", usage_metadata={"input_tokens": 5, "output_tokens": 2, "total_tokens": 7})]}

    with patch("langchain.agents.create_agent", return_value=StubAgent()):
        reply, used = chat_agent(1, "test-key", [], "hello", "deepseek-flash", "none")
    assert reply == "ok" and used["total_tokens"] == 7
    draft_request = {"mailbox_id": 1, "recipient": "person@example.com", "prompt": "礼貌地问候", "model": "deepseek-flash", "effort": "none"}
    assert client.post("/api/mail/draft", headers=b, json=draft_request).status_code == 422
    with patch("app.main.draft_agent", return_value=({"subject": "问候", "body": "您好。"}, {"input_tokens": 6, "output_tokens": 3, "total_tokens": 9})):
        draft = client.post("/api/mail/draft", headers=a, json=draft_request)
    assert draft.status_code == 200 and draft.json()["subject"] == "问候"
    send_request = {"mailbox_id": 1, "recipient": "person@example.com", "subject": "问候", "body": "您好。", "request_id": "test-send-request-1234567890"}
    assert client.post("/api/mail/send", headers=b, json=send_request).status_code == 404
    with patch("app.main.send_qq_mail") as smtp:
        sent = client.post("/api/mail/send", headers=a, json=send_request)
        assert sent.status_code == 200
        smtp.assert_called_once_with("sender@qq.com", "test-mail-code", "person@example.com", "问候", "您好。")
        assert client.post("/api/mail/send", headers=a, json=send_request).status_code == 409
    with db() as con:
        assert con.execute("SELECT status FROM outbound_mail WHERE user_id=1").fetchone()[0] == "sent"
        assert con.execute("SELECT COUNT(*) FROM draft_usage WHERE user_id=1").fetchone()[0] == 1
    assert client.get("/api/usage", headers=a).json()["total_tokens"] == 23
    with patch("app.main.smtplib.SMTP_SSL") as smtp_factory:
        smtp = smtp_factory.return_value.__enter__.return_value
        send_qq_mail("sender@qq.com", "test-mail-code", "person@example.com", "问候", "您好。")
        smtp.login.assert_called_once_with("sender@qq.com", "test-mail-code")
        sent_message = smtp.send_message.call_args.args[0]
        assert sent_message["From"] == "sender@qq.com"
        assert sent_message["To"] == "person@example.com"
        assert sent_message["Subject"] == "问候"
        assert "您好。" in sent_message.get_content()
    print("chat and draft isolation, usage, SMTP handoff and duplicate protection passed")
