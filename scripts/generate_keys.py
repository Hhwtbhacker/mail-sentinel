"""Print deployment secrets. Run locally and copy the output into .env."""
import base64
import secrets
from cryptography.fernet import Fernet
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

key = ec.generate_private_key(ec.SECP256R1())
private = key.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
public = key.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)
print("SESSION_SECRET=" + secrets.token_urlsafe(48))
print("DATA_ENCRYPTION_KEY=" + Fernet.generate_key().decode())
print("VAPID_PRIVATE_KEY=" + base64.urlsafe_b64encode(private).decode().rstrip("="))
print("VAPID_PUBLIC_KEY=" + base64.urlsafe_b64encode(public).decode().rstrip("="))
