#!/usr/bin/env python3
"""Provision this project's public AI Pass client via owner-approved device flow.
Never persists/prints setup grants or runtime credentials. Run request, then provision.
"""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.error
import urllib.request
import urllib.parse
import uuid

ROOT = Path(__file__).resolve().parent.parent
DIRECTORY = ROOT / '.aipass'
CONFIG = DIRECTORY / 'config.json'
RECOVERY = DIRECTORY / 'project-grant.json'
BASE = 'https://aipass.one'
SCOPES = ['setup:read', 'oauth-clients:read', 'oauth-clients:create', 'space:read',
          'space-apps:write', 'space-apps:publish', 'space-apps:delete', 'nova:query']


def api(path, body=None, grant=None, method=None):
    headers = {'Content-Type': 'application/json'}
    if grant:
        headers['Authorization'] = 'Bearer ' + grant
    request = urllib.request.Request(BASE + path, data=json.dumps(body).encode() if body is not None else None,
                                     headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        data = json.load(error)
        code = data.get('error', 'http_' + str(error.code))
        # Never emit a token-bearing response body.
        return {'error': code if isinstance(code, str) else 'http_' + str(error.code)}


def secure_paths():
    if DIRECTORY.is_symlink() or RECOVERY.is_symlink() or CONFIG.is_symlink():
        raise SystemExit('Refusing symlink in project authorization paths')
    DIRECTORY.mkdir(mode=0o700, exist_ok=True)
    DIRECTORY.chmod(0o700)
    ignore = ROOT / '.gitignore'
    existing = ignore.read_text() if ignore.exists() else ''
    if '.aipass/project-grant.json' not in existing.splitlines():
        ignore.write_text(existing.rstrip() + '\n.aipass/project-grant.json\n')


def write_json(path, data, secret=False):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600 if secret else 0o644)
    with os.fdopen(descriptor, 'w') as out:
        json.dump(data, out, ensure_ascii=False, indent=2)
        out.write('\n')
    if secret:
        path.chmod(0o600)


def exchange(record):
    result = api('/api/v1/agent-auth/token', {'deviceCode': record['deviceCode']})
    data = result.get('data', {})
    if data.get('status') != 'approved':
        print(json.dumps({'status': result.get('error', 'authorization_pending')}))
        return None
    record['grantExpiresAt'] = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(seconds=data['expiresIn'])).isoformat()
    write_json(RECOVERY, record, True)
    return data['accessToken']


def normalized_origins(origins):
    result = []
    for origin in origins:
        parsed = urllib.parse.urlsplit(origin)
        if (parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password
                or parsed.path not in ('', '/') or parsed.query or parsed.fragment):
            raise SystemExit('Use actual HTTPS deployment origins, without paths, queries or fragments.')
        value = origin.rstrip('/')
        if value not in result:
            result.append(value)
    return result


def request(origins, migrate=False):
    secure_paths()
    origins = normalized_origins(origins)
    config = json.loads(CONFIG.read_text()) if CONFIG.exists() else {'schemaVersion': 1, 'projectFingerprint': str(uuid.uuid4())}
    write_json(CONFIG, config)
    bindings = {'baseUrl': BASE, 'projectFingerprint': config['projectFingerprint'],
                'proposedRedirectUris': ['http://localhost:8080/', *[origin + '/' for origin in origins]],
                'proposedSpaceAppSlug': 'ai-mafia', 'requestedScopes': SCOPES}
    if RECOVERY.exists():
        old = json.loads(RECOVERY.read_text())
        if all(old.get(k) == v for k, v in bindings.items()):
            print(json.dumps({'status': 'existing_project_authorization', 'action': 'Run provision to recover the existing request'}))
            return
        if not migrate:
            raise SystemExit('Existing setup request has different callbacks. Use migrate only for an intentional hosting change.')
        # The old grant cannot authorize a new origin. Keep the old public client
        # intact, but discard its incompatible local recovery record.
        RECOVERY.unlink()
    key = config.get('oauthClientIdempotencyKey', 'oauth-client:v1')
    if config.get('clientId') and config.get('redirectUris') != bindings['proposedRedirectUris']:
        if not migrate:
            raise SystemExit('A configured client has different callbacks. Use migrate for a fresh owner approval.')
        match = re.fullmatch(r'oauth-client:v(\d+)', key)
        if not match:
            raise SystemExit('Cannot advance an unfamiliar OAuth idempotency key.')
        key = 'oauth-client:v' + str(int(match.group(1)) + 1)
    result = api('/api/v1/agent-auth/device', {'agentName': 'Codex', 'projectName': 'AI Mafia',
        'setupVersion': 5, **{k: v for k, v in bindings.items() if k != 'baseUrl'}})
    data = result.get('data')
    if not data or 'deviceCode' not in data:
        raise SystemExit('Setup request failed: ' + str(result.get('error', 'unknown')))
    record = {'schemaVersion': 1, **bindings, 'deviceCode': data['deviceCode'],
        'oauthClientIdempotencyKey': key,
        'deviceExpiresAt': (dt.datetime.now(dt.timezone.utc) + dt.timedelta(seconds=data['expiresIn'])).isoformat(),
        'interval': data.get('interval', 5)}
    write_json(RECOVERY, record, True)
    uri = data['verificationUriComplete']
    subprocess.run(['open', uri], check=False)
    print(json.dumps({'verificationUriComplete': uri, 'interval': record['interval'], 'redirectUris': bindings['proposedRedirectUris']}))


def provision():
    secure_paths()
    if not RECOVERY.exists():
        raise SystemExit('No project authorization request exists yet')
    record = json.loads(RECOVERY.read_text())
    grant = exchange(record)
    if not grant:
        return
    context = api('/api/v1/agent-control/context', grant=grant)
    if 'error' in context:
        raise SystemExit('Cannot read authorized setup context')
    config = json.loads(CONFIG.read_text())
    # Persist the migration key with the device request, so retries cannot create
    # further clients or accidentally reuse the old callback-bound client.
    key = record.get('oauthClientIdempotencyKey', config.get('oauthClientIdempotencyKey', 'oauth-client:v1'))
    result = api('/api/v1/agent-control/oauth-clients/ensure', {
        'name': 'AI Mafia', 'idempotencyKey': key, 'runtimeScopes': ['api:access']}, grant=grant)
    data = result.get('data', {})
    if not data.get('clientId'):
        raise SystemExit('Client provisioning failed: ' + str(result.get('error', 'unknown')))
    config.update({'schemaVersion': 1, 'path': 'sdk', 'appName': 'AI Mafia', 'clientId': data['clientId'],
                   'redirectUris': data.get('redirectUris', record['proposedRedirectUris']), 'oauthClientIdempotencyKey': key})
    write_json(CONFIG, config)
    public = {'clientId': config['clientId']}
    write_json(ROOT / 'webApp/src/webMain/resources/aipass-config.json', public)
    dist = ROOT / 'webApp/build/dist/wasmJs/productionExecutable'
    if dist.exists():
        write_json(dist / 'aipass-config.json', public)
    print(json.dumps({'clientId': config['clientId'], 'redirectUris': config['redirectUris'],
                      'grantRecoverableUntil': record['grantExpiresAt']}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['request', 'migrate', 'provision'])
    parser.add_argument('--origin', action='append', metavar='HTTPS_ORIGIN',
                        help='stable deployment origin; repeat for every public alias')
    args = parser.parse_args()
    if args.action in ('request', 'migrate'):
        if not args.origin:
            parser.error('request requires at least one --origin with an actual HTTPS viewer URL')
        request(args.origin, migrate=args.action == 'migrate')
    else:
        provision()
