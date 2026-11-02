#!/usr/bin/env python3
"""Выпуск офлайн-лицензий RoadScan AI.

Закрытый ключ НИКОГДА не попадает в репозиторий и в APK. По умолчанию
используется ~/.config/opencode/secrets/roadscan-license-key.pem (chmod 600).

Примеры:
    ./tools/license.py keygen
    ./tools/license.py issue --org "МБУ Дорремстрой" --tier PRO --days 365 \
        --features pdf,xlsx,geojson,gis --out license.txt
    ./tools/license.py verify license.txt
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import pathlib
import sys
import time

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

DEFAULT_KEY = pathlib.Path.home() / ".config/opencode/secrets/roadscan-license-key.pem"
PRODUCT = "roadscan-ai"
TIERS = {
    "PILOT": ["pdf", "xlsx", "geojson"],
    "PRO": ["pdf", "xlsx", "geojson", "gis"],
    "ENTERPRISE": ["pdf", "xlsx", "geojson", "gis", "api", "white_label"],
}


def b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


def unb64(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def load_key(path: pathlib.Path):
    if not path.exists():
        sys.exit(f"Ключ не найден: {path}. Сначала выполните: {sys.argv[0]} keygen")
    return serialization.load_pem_private_key(path.read_bytes(), password=None)


def cmd_keygen(args) -> None:
    path = pathlib.Path(args.key)
    if path.exists() and not args.force:
        sys.exit(f"Ключ уже существует: {path} (используйте --force, чтобы перезаписать)")
    path.parent.mkdir(parents=True, exist_ok=True)
    key = ec.generate_private_key(ec.SECP256R1())
    path.write_bytes(
        key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
    )
    path.chmod(0o600)
    pub = key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )
    print(f"Закрытый ключ: {path}")
    print("Публичный ключ для core/.../license/License.kt:")
    print(base64.b64encode(pub).decode())


def cmd_issue(args) -> None:
    key = load_key(pathlib.Path(args.key))
    now_ms = int(time.time() * 1000)
    expires_ms = 0 if args.days == 0 else now_ms + args.days * 86_400_000
    features = [f.strip() for f in args.features.split(",") if f.strip()] if args.features else TIERS[args.tier]
    payload = {
        "product": PRODUCT,
        "licenseId": args.id or f"{args.tier}-{int(time.time())}",
        "organization": args.org,
        "tier": args.tier,
        "issuedAtMs": now_ms,
        "expiresAtMs": expires_ms,
        "maxSessions": args.max_sessions,
        "seats": args.seats,
        "features": features,
    }
    raw = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    signature = key.sign(raw, ec.ECDSA(hashes.SHA256()))
    code = f"RSL1.{b64(raw)}.{b64(signature)}"

    if args.out:
        pathlib.Path(args.out).write_text(code + "\n")
        print(f"Лицензия записана: {args.out}")
    else:
        print(code)
    print(
        f"\nОрганизация: {args.org}\nРедакция: {args.tier}\n"
        f"Действует до: {'бессрочно' if expires_ms == 0 else time.strftime('%d.%m.%Y', time.localtime(expires_ms / 1000))}\n"
        f"Лимит смен: {'без ограничений' if args.max_sessions == 0 else args.max_sessions}\n"
        f"Функции: {', '.join(features)}",
        file=sys.stderr,
    )


def cmd_verify(args) -> None:
    key = load_key(pathlib.Path(args.key))
    pub = key.public_key()
    code = pathlib.Path(args.file).read_text().strip()
    parts = code.split(".")
    if len(parts) != 3 or parts[0] != "RSL1":
        sys.exit("Неверный формат")
    raw, signature = unb64(parts[1]), unb64(parts[2])
    try:
        pub.verify(signature, raw, ec.ECDSA(hashes.SHA256()))
    except Exception:
        sys.exit("Подпись не совпадает")
    payload = json.loads(raw)
    expired = payload.get("expiresAtMs", 0) and payload["expiresAtMs"] < time.time() * 1000
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    print("СТАТУС:", "ИСТЕКЛА" if expired else "ДЕЙСТВИТЕЛЬНА")
    if expired:
        sys.exit(1)


def main() -> None:
    parser = argparse.ArgumentParser(description="Лицензии RoadScan AI")
    parser.add_argument(
        "--key",
        default=os.environ.get("ROADSCAN_LICENSE_KEY", str(DEFAULT_KEY)),
        help="путь к закрытому ключу",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("keygen", help="создать пару ключей")
    p.add_argument("--force", action="store_true")
    p.set_defaults(func=cmd_keygen)

    p = sub.add_parser("issue", help="выпустить лицензию")
    p.add_argument("--org", required=True, help="организация-лицензиат")
    p.add_argument("--tier", choices=sorted(TIERS), default="PRO")
    p.add_argument("--days", type=int, default=365, help="0 — бессрочно")
    p.add_argument("--max-sessions", type=int, default=0, help="0 — без ограничений")
    p.add_argument("--seats", type=int, default=1)
    p.add_argument("--features", default=None, help="через запятую; по умолчанию — набор редакции")
    p.add_argument("--id", default=None)
    p.add_argument("--out", default=None)
    p.set_defaults(func=cmd_issue)

    p = sub.add_parser("verify", help="проверить лицензию")
    p.add_argument("file")
    p.set_defaults(func=cmd_verify)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
