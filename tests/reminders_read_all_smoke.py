"""Verify bulk read updates every reminder for only the signed-in user."""
import os
import sys
import tempfile
import time
from pathlib import Path

from cryptography.fernet import Fernet
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

with tempfile.TemporaryDirectory() as directory:
    os.environ["DB_PATH"] = str(Path(directory) / "test.db")
    os.environ["DATA_ENCRYPTION_KEY"] = Fernet.generate_key().decode()
    os.environ["SESSION_SECRET"] = "reminder-read-smoke"
    os.environ["COOKIE_SECURE"] = "false"

    from app.main import app, db, init_db

    init_db()
    alice = TestClient(app)
    bob = TestClient(app)
    password = "safe-test-password-123"
    assert alice.post("/api/register", json={"email": "alice@example.com", "password": password}).status_code == 200
    assert bob.post("/api/register", json={"email": "bob@example.com", "password": password}).status_code == 200
    now = int(time.time())
    with db() as con:
        for user_id in (1, 2):
            con.execute("INSERT INTO mailboxes(user_id,provider,address,secret,next_check) VALUES (?,?,?,?,?)",
                        (user_id, "qq", f"{user_id}@qq.com", "test", now))
        for i in range(105):
            con.execute("INSERT INTO reminders(user_id,mailbox_id,uid,sender,subject,reason,priority,created_at) "
                        "VALUES (1,1,?,?,?,?,?,?)", (str(i), "sender", "subject", "reason", "normal", now))
        con.execute("INSERT INTO reminders(user_id,mailbox_id,uid,sender,subject,reason,priority,created_at) "
                    "VALUES (2,2,'bob','sender','subject','reason','normal',?)", (now,))

    response = alice.post("/api/reminders/read-all")
    assert response.status_code == 200 and response.json()["updated"] == 105
    assert alice.post("/api/reminders/read-all").json()["updated"] == 0
    with db() as con:
        assert con.execute("SELECT COUNT(*) FROM reminders WHERE user_id=1 AND read_at IS NULL").fetchone()[0] == 0
        assert con.execute("SELECT COUNT(*) FROM reminders WHERE user_id=2 AND read_at IS NULL").fetchone()[0] == 1
    assert bob.get("/api/reminders").json()[0]["read_at"] is None
    assert TestClient(app).post("/api/reminders/read-all").status_code == 401

print("bulk reminders read: OK")
