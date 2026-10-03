from __future__ import annotations

from pathlib import Path

import yaml

from deskseed_ai.main import app

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "api" / "ai-internal-api-v1.yaml"


def main() -> None:
    document = app.openapi()
    document["info"]["description"] = (
        "Deskseed Backend 전용 AI 수신·상태·운영 계약입니다. "
        "direction-specific machine credential이 필요하며 staff/public ingress에 노출하지 않습니다."
    )
    rendered = yaml.safe_dump(document, allow_unicode=True, sort_keys=False, width=120)
    OUTPUT.write_text(rendered, encoding="utf-8")


if __name__ == "__main__":
    main()
