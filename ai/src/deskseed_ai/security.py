from __future__ import annotations

import base64
import hashlib
import hmac

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from .config import Settings


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def authenticate_machine(key_id: str, secret: str, configured_key_id: str, configured_digest: str) -> bool:
    return bool(
        key_id
        and secret
        and hmac.compare_digest(key_id.encode(), configured_key_id.encode())
        and hmac.compare_digest(sha256_text(secret).encode(), configured_digest.lower().encode())
    )


class EnvelopeCipher:
    def __init__(self, settings: Settings):
        encoded = settings.result_encryption_key.get_secret_value()
        if encoded:
            key = base64.b64decode(encoded, validate=True)
            if len(key) != 32:
                raise ValueError("result encryption key must contain exactly 32 bytes")
        elif settings.environment == "production":
            raise ValueError("production requires a result encryption key")
        else:
            key = hashlib.sha256(b"deskseed-ai-local-result-key-not-for-production").digest()
        self._cipher = AESGCM(key)

    def encrypt(self, plaintext: bytes, associated_data: bytes) -> tuple[bytes, bytes]:
        nonce = __import__("os").urandom(12)
        return self._cipher.encrypt(nonce, plaintext, associated_data), nonce

    def decrypt(self, ciphertext: bytes, nonce: bytes, associated_data: bytes) -> bytes:
        return self._cipher.decrypt(nonce, ciphertext, associated_data)
