#!/usr/bin/env bash
set -euo pipefail

# Run from the extracted project directory on the VM, or set PROJECT_DIR.
PROJECT_DIR="${PROJECT_DIR:-$(cd "$(dirname "$0")/.." && pwd)}"
APP_SOURCE="$PROJECT_DIR/auth-backend/app.py"
REQ_SOURCE="$PROJECT_DIR/auth-backend/requirements.txt"
TARGET_DIR="/opt/door-sesame"
ENV_FILE="$TARGET_DIR/.env"
SERVICE="door-sesame.service"

if [[ $EUID -ne 0 ]]; then echo "請用 sudo 執行：sudo bash auth-backend/deploy-auth-backend.sh" >&2; exit 1; fi
if [[ ! -f "$APP_SOURCE" || ! -f "$REQ_SOURCE" ]]; then echo "找不到專案 auth-backend/app.py 或 requirements.txt；請確認 PROJECT_DIR。" >&2; exit 1; fi
if [[ ! -f "$ENV_FILE" ]]; then echo "找不到 $ENV_FILE；停止，不建立或覆寫任何環境變數。" >&2; exit 1; fi
if ! grep -q '^APP_TOKEN_SECRET=.' "$ENV_FILE"; then echo "請先在 $ENV_FILE 加入 APP_TOKEN_SECRET（至少 32 字元）；與芝麻開門 Node 服務使用同一個值。" >&2; exit 1; fi
if ! grep -q '^GOOGLE_CLIENT_ID=.' "$ENV_FILE" || ! grep -q '^GOOGLE_CLIENT_SECRET=.' "$ENV_FILE"; then echo "請先確認 Google OAuth 的 Client ID/Secret 已在 .env 中設定。" >&2; exit 1; fi

stamp=$(date +%Y%m%d-%H%M%S)
cp -a "$TARGET_DIR/app.py" "$TARGET_DIR/app.py.backup-$stamp"
install -o root -g doorsesame -m 0640 "$APP_SOURCE" "$TARGET_DIR/app.py"
"$TARGET_DIR/venv/bin/pip" install -r "$REQ_SOURCE"
"$TARGET_DIR/venv/bin/python" - <<'PY'
import ast
from pathlib import Path
ast.parse(Path('/opt/door-sesame/app.py').read_text())
print('Python syntax OK')
PY
systemctl restart "$SERVICE"
sleep 2
systemctl --no-pager --full status "$SERVICE" | head -30
curl --fail --silent --show-error http://127.0.0.1:8765/health
printf '\n後端更新完成。備份：%s/app.py.backup-%s\n' "$TARGET_DIR" "$stamp"
