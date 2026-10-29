import base64
import hashlib
import hmac
import json
import os
import secrets
import sqlite3
import time
from functools import wraps
from pathlib import Path

from authlib.integrations.flask_client import OAuth
from flask import Flask, jsonify, redirect, request, session

APP_DIR = Path(__file__).resolve().parent
DB_PATH = Path(os.getenv('ALLOWLIST_DB', '/var/lib/door-sesame/allowlist.sqlite3'))
APP = Flask(__name__)
APP.config.update(
    SECRET_KEY=os.environ['SESSION_SECRET'],
    SESSION_COOKIE_SECURE=True,
    SESSION_COOKIE_HTTPONLY=True,
    SESSION_COOKIE_SAMESITE='Lax',
    MAX_CONTENT_LENGTH=32 * 1024,
)
APP_TOKEN_SECRET = os.environ['APP_TOKEN_SECRET']
if len(APP_TOKEN_SECRET) < 32:
    raise RuntimeError('APP_TOKEN_SECRET 至少需要 32 個字元')
ADMIN_EMAIL = os.getenv('APP_ADMIN_EMAIL', '').strip().lower()
GOOGLE_REDIRECT_URI = os.environ['GOOGLE_REDIRECT_URI']


def connect_db():
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(DB_PATH, timeout=10)
    db.row_factory = sqlite3.Row
    return db


def init_db():
    configured = [x.strip().lower() for x in os.getenv('ALLOWED_EMAILS', '').split(',') if x.strip()]
    if ADMIN_EMAIL:
        configured.append(ADMIN_EMAIL)
    with connect_db() as db:
        db.execute('CREATE TABLE IF NOT EXISTS app_allowed_emails (email TEXT PRIMARY KEY, created_at INTEGER NOT NULL, created_by TEXT NOT NULL DEFAULT "env")')
        db.execute('CREATE TABLE IF NOT EXISTS app_login_codes (code_hash TEXT PRIMARY KEY, email TEXT NOT NULL, expires_at INTEGER NOT NULL, used INTEGER NOT NULL DEFAULT 0)')
        for email in set(configured):
            db.execute('INSERT OR IGNORE INTO app_allowed_emails(email, created_at, created_by) VALUES(?,?,?)', (email, int(time.time()), 'env'))
        db.execute('DELETE FROM app_login_codes WHERE expires_at < ?', (int(time.time()) - 3600,))


init_db()
oauth = OAuth(APP)
oauth.register(
    name='google',
    client_id=os.environ['GOOGLE_CLIENT_ID'],
    client_secret=os.environ['GOOGLE_CLIENT_SECRET'],
    server_metadata_url='https://accounts.google.com/.well-known/openid-configuration',
    client_kwargs={'scope': 'openid email profile'},
)


def allowed(email):
    email = (email or '').strip().lower()
    if not email:
        return False
    with connect_db() as db:
        return db.execute('SELECT 1 FROM app_allowed_emails WHERE email=?', (email,)).fetchone() is not None


def make_jwt(email, lifetime=7 * 24 * 3600):
    now = int(time.time())
    head = {'alg': 'HS256', 'typ': 'JWT'}
    body = {'iss': 'door-sesame-auth', 'sub': email, 'email': email, 'iat': now, 'exp': now + lifetime}
    enc = lambda obj: base64.urlsafe_b64encode(json.dumps(obj, separators=(',', ':')).encode()).rstrip(b'=').decode()
    first = enc(head) + '.' + enc(body)
    sig = hmac.new(APP_TOKEN_SECRET.encode(), first.encode(), hashlib.sha256).digest()
    return first + '.' + base64.urlsafe_b64encode(sig).rstrip(b'=').decode(), now + lifetime


def bearer_user():
    raw = request.headers.get('Authorization', '')
    if not raw.lower().startswith('bearer '):
        return None
    parts = raw[7:].strip().split('.')
    if len(parts) != 3:
        return None
    first = parts[0] + '.' + parts[1]
    expected = base64.urlsafe_b64encode(hmac.new(APP_TOKEN_SECRET.encode(), first.encode(), hashlib.sha256).digest()).rstrip(b'=').decode()
    if not hmac.compare_digest(expected, parts[2]):
        return None
    try:
        payload = json.loads(base64.urlsafe_b64decode(parts[1] + '=' * (-len(parts[1]) % 4)))
        if payload.get('iss') != 'door-sesame-auth' or int(payload.get('exp', 0)) <= int(time.time()):
            return None
        email = str(payload.get('email', '')).lower()
        return email if allowed(email) else None
    except Exception:
        return None


def admin_required(fn):
    @wraps(fn)
    def wrapper(*args, **kwargs):
        email = bearer_user() or str(session.get('user_email', '')).strip().lower()
        if not email or email != ADMIN_EMAIL:
            return jsonify(error='admin_required'), 403
        return fn(*args, **kwargs)
    return wrapper


@APP.get('/')
def index():
    return jsonify(service='door-sesame-auth', status='ok', endpoints=['/health', '/app/login', '/api/app-token', '/api/me'])


@APP.get('/health')
def health():
    return jsonify(service='door-sesame-auth', status='ok')


@APP.get('/app/login')
def app_login():
    state = request.args.get('state', '').strip()
    if len(state) < 16 or len(state) > 128:
        return jsonify(error='invalid_state'), 400
    session['sesame_app_state'] = state
    session['sesame_app_login'] = True
    return oauth.google.authorize_redirect(GOOGLE_REDIRECT_URI)


@APP.get('/auth/login')
def browser_login():
    session['sesame_app_login'] = False
    return oauth.google.authorize_redirect(GOOGLE_REDIRECT_URI)


@APP.get('/auth/callback')
def auth_callback():
    app_flow = bool(session.pop('sesame_app_login', False))
    app_state = session.pop('sesame_app_state', '') if app_flow else ''
    try:
        token = oauth.google.authorize_access_token()
        user = token.get('userinfo')
        if not user:
            user = oauth.google.userinfo(token=token)
        email = str(user.get('email', '')).strip().lower()
        verified = user.get('email_verified') is True or str(user.get('email_verified', '')).lower() == 'true'
    except Exception:
        if app_flow:
            return redirect('sesame://auth?error=google_login_failed&state=' + app_state)
        return jsonify(error='google_login_failed'), 401
    if not verified or not allowed(email):
        if app_flow:
            return redirect('sesame://auth?error=not_allowlisted&state=' + app_state)
        return jsonify(error='not_allowlisted', email=email), 403
    session['user_email'] = email
    if app_flow:
        code = secrets.token_urlsafe(32)
        digest = hashlib.sha256(code.encode()).hexdigest()
        with connect_db() as db:
            db.execute('INSERT INTO app_login_codes(code_hash,email,expires_at,used) VALUES(?,?,?,0)', (digest, email, int(time.time()) + 180))
        return redirect('sesame://auth?code=' + code + '&state=' + app_state)
    return jsonify(login_success=True, email=email, message='Google 登入成功；此瀏覽器工作階段已通過白名單。')


@APP.post('/api/app-token')
def exchange_code():
    body = request.get_json(silent=True) or {}
    code = str(body.get('code', '')).strip()
    if not code or len(code) > 200:
        return jsonify(error='invalid_code'), 400
    digest = hashlib.sha256(code.encode()).hexdigest()
    now = int(time.time())
    with connect_db() as db:
        row = db.execute('SELECT email,expires_at,used FROM app_login_codes WHERE code_hash=?', (digest,)).fetchone()
        if not row or row['used'] or row['expires_at'] < now:
            return jsonify(error='code_invalid_or_expired'), 401
        db.execute('UPDATE app_login_codes SET used=1 WHERE code_hash=? AND used=0', (digest,))
    email = row['email'].lower()
    if not allowed(email):
        return jsonify(error='not_allowlisted'), 403
    jwt, exp = make_jwt(email)
    return jsonify(access_token=jwt, token_type='Bearer', expires_at=exp, email=email)


@APP.get('/api/me')
def me():
    email = bearer_user()
    if not email:
        return jsonify(authenticated=False), 401
    return jsonify(authenticated=True, email=email, is_admin=email == ADMIN_EMAIL)


@APP.get('/api/admin/allowlist')
@admin_required
def list_allowlist():
    with connect_db() as db:
        rows = db.execute('SELECT email,created_at,created_by FROM app_allowed_emails ORDER BY email').fetchall()
    return jsonify(items=[dict(r) for r in rows])


@APP.post('/api/admin/allowlist')
@admin_required
def add_allowlist():
    body = request.get_json(silent=True) or {}
    email = str(body.get('email', '')).strip().lower()
    if len(email) > 254 or '@' not in email or '.' not in email.rsplit('@', 1)[-1]:
        return jsonify(error='invalid_email'), 400
    with connect_db() as db:
        db.execute('INSERT OR IGNORE INTO app_allowed_emails(email,created_at,created_by) VALUES(?,?,?)', (email, int(time.time()), 'admin'))
    return jsonify(ok=True, email=email)


@APP.delete('/api/admin/allowlist/<path:email>')
@admin_required
def delete_allowlist(email):
    email = email.strip().lower()
    if email == ADMIN_EMAIL:
        return jsonify(error='cannot_remove_admin'), 400
    with connect_db() as db:
        db.execute('DELETE FROM app_allowed_emails WHERE email=?', (email,))
    return jsonify(ok=True, email=email)


if __name__ == '__main__':
    APP.run(host='127.0.0.1', port=8765)
