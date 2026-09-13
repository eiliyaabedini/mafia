#!/usr/bin/env python3
"""Build and package the Wasm website for `vercel deploy --prebuilt`.

Use --skip-build after an explicit :webApp:wasmJsBrowserDistribution build.
Only compiled, validated static assets enter .vercel/output. This script does
not provision credentials, link a project, invoke Vercel, or deploy anything.
Build Output API: https://vercel.com/docs/build-output-api/configuration
"""
import argparse
from functools import lru_cache
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

REPO = Path(__file__).resolve().parent.parent
DIST = REPO / 'webApp/build/dist/wasmJs/productionExecutable'
VERCEL = REPO / '.vercel'
OUTPUT = VERCEL / 'output'
ROOT_FILES = {
    'index.html', 'offline.html', 'boot.js', 'ai-bridge.js', 'styles.css',
    'sw.js', 'manifest.webmanifest', 'aipass-config.json', 'webApp.js',
    'webApp.js.LICENSE.txt',
}
MUSIC_ASSETS = frozenset(f'audio/{name}.mp3' for name in (
    'mafia-nocturne', 'mafia-dons-gambit-main', 'mafia-dons-gambit-late',
    'mafia-dons-gambit-short', 'mafia-dons-gambit-vocal-alt',
    'mafia-dons-gambit-vocal-rare',
))
REQUIRED = (ROOT_FILES - {'webApp.js.LICENSE.txt'}) | MUSIC_ASSETS
EFFECT_ASSETS = frozenset(f'audio/effects/{cue}.mp3' for cue in (
    'game_start', 'daybreak', 'nightfall', 'vote_open', 'vote_tied',
    'eliminated', 'night_killed', 'night_saved', 'town_win', 'mafia_win',
))
REQUIRED |= EFFECT_ASSETS | {'audio/effects/CREDITS.txt'}
RESOURCE_SUFFIXES = {'.png', '.webp', '.jpg', '.jpeg', '.svg', '.xml', '.ttf', '.otf', '.woff', '.woff2', '.cvr', '.bin'}
SECRET_NAMES = re.compile(r'(?:^|[-_.])(?:secrets?|credentials?|tokens?|private[-_]?key|project[-_]?grant)(?:[-_.]|$)', re.I)
SECRET_CONTENT = [
    re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----'),
    re.compile(rb'\bsk-(?:proj-)?[A-Za-z0-9_-]{20,}\b'),
    re.compile(rb'\b(?:ghp|github_pat|vcp)_[A-Za-z0-9_]{20,}\b'),
    re.compile(rb'\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b'),
    re.compile(rb'''(?:client_secret|access_token|refresh_token|api_key|apiKey|secret_key|device_code|project_token)["']?\s*[:=]\s*["'][A-Za-z0-9_./+=-]{12,}["']''', re.I),
]


@lru_cache(maxsize=1)
def tutorial_assets():
    """The authored manifest is local build input, never an uploaded document."""
    manifest = REPO / 'docs/tutorial-narration.json'
    if manifest.is_symlink():
        raise ValueError('Tutorial narration manifest must not be a symbolic link.')
    try:
        authored = json.loads(manifest.read_text(encoding='utf-8'))
        clips = authored['clips']
        ids = [clip['id'] for clip in clips]
        declared_count = authored['clipCount']
    except (OSError, ValueError, KeyError, TypeError):
        raise ValueError('Missing or invalid tutorial narration manifest.') from None
    expected = {f'basic_{name}' for name in ('welcome', 'citizen', 'roles', 'cycle', 'night', 'outcomes')}
    for track, names in {
        'practice': ('listen', 'question', 'vote', 'night', 'finish'),
        'strategy': ('evidence', 'detective', 'teammate'),
    }.items():
        expected.update(f'{track}_{name}_{part}' for name in names
                        for part in ('intro', 'feedback_0', 'feedback_1', 'feedback_2'))
    expected.update(f'complete_{track}' for track in ('basics', 'practice', 'strategy'))
    if declared_count != len(ids) or len(ids) != len(expected) \
            or any(not isinstance(clip_id, str) for clip_id in ids) or set(ids) != expected:
        raise ValueError('Tutorial manifest count and unique supported clip IDs must match.')
    return frozenset(f'audio/tutorial/{clip_id}.mp3' for clip_id in ids)


def check_path(relative):
    """Reject hidden/config/source files even when a build copied them by accident."""
    for part in relative.parts:
        if part.startswith('.') or SECRET_NAMES.search(part) or part in {
            'local.properties', 'gradle.properties', 'package.json', 'AGENTS.md',
            'node_modules', 'src', 'build', 'gradle',
        }:
            raise ValueError(f'Private or unexpected path in distribution: {relative}')
    if relative.suffix == '.map':
        return False
    path = relative.as_posix()
    if len(relative.parts) == 1:
        allowed = path in ROOT_FILES or bool(re.fullmatch(r'[a-f0-9]{16,64}\.wasm', path))
    elif relative.parts[0] == 'icons':
        allowed = len(relative.parts) == 2 and relative.suffix == '.png'
    elif relative.parts[0] == 'fonts':
        allowed = len(relative.parts) == 2 and (relative.suffix in {'.ttf', '.woff2'} or relative.name == 'OFL.txt')
    elif relative.parts[0] == 'audio':
        allowed = path in MUSIC_ASSETS | {'audio/effects/CREDITS.txt'} or path in EFFECT_ASSETS or path in tutorial_assets()
    elif relative.parts[0] == 'composeResources':
        allowed = relative.suffix in RESOURCE_SUFFIXES or relative.name in {'OFL.txt', 'LICENSE.txt'}
    else:
        allowed = False
    if not allowed:
        raise ValueError(f'Unrecognized static asset; review before allowing it: {relative}')
    return True


def tutorial_audio_ready():
    """Keep the unfinished recording feature hidden; never publish a partial set."""
    expected = tutorial_assets()
    present = {name for name in expected if (DIST / name).is_file()}
    if present and present != expected:
        raise ValueError('Tutorial narration is incomplete; generate every manifest clip before publishing it.')
    return bool(present)


def validate_audio(path, relative):
    """Enable only decodable recordings that satisfy the browser's limits."""
    if relative.as_posix() not in MUSIC_ASSETS | EFFECT_ASSETS | tutorial_assets():
        return
    limit = 300 if relative.as_posix() in MUSIC_ASSETS else 5 if relative.as_posix() in EFFECT_ASSETS else 90
    result = subprocess.run(['ffprobe', '-v', 'error', '-show_entries',
        'format=duration:stream=codec_name,sample_rate,channels', '-of', 'json', str(path)],
        capture_output=True, text=True, timeout=15)
    try:
        meta = json.loads(result.stdout)
        seconds = float(meta['format']['duration'])
        streams = meta['streams']
        valid = result.returncode == 0 and math.isfinite(seconds) and 0 < seconds <= limit
        valid = valid and len(streams) == 1 and streams[0]['codec_name'] == 'mp3'
        valid = valid and streams[0]['sample_rate'] == '44100' and streams[0]['channels'] in (1, 2)
    except (ValueError, TypeError, KeyError):
        valid = False
    if not valid:
        raise ValueError(f'Audio does not meet playback requirements: {relative}')


def read_public_asset(path, relative):
    data = path.read_bytes()
    if relative.as_posix() in MUSIC_ASSETS and not 0 < len(data) <= 8 * 1024 * 1024:
        raise ValueError(f'Music asset is empty or exceeds the playback limit: {relative}')
    if relative.as_posix() in tutorial_assets() and not 0 < len(data) <= 5 * 1024 * 1024:
        raise ValueError(f'Tutorial audio asset is empty or exceeds the playback limit: {relative}')
    if relative.as_posix() in EFFECT_ASSETS and not 0 < len(data) <= 1024 * 1024:
        raise ValueError(f'Effect is empty or exceeds the playback limit: {relative}')
    validate_audio(path, relative)
    if relative.as_posix() == 'ai-bridge.js':
        marker = b'const TUTORIAL_AUDIO_READY = false;'
        if data.count(marker) != 1:
            raise ValueError('Expected one disabled tutorial audio build flag in ai-bridge.js.')
        if tutorial_audio_ready():
            data = data.replace(marker, b'const TUTORIAL_AUDIO_READY = true;', 1)
    if any(pattern.search(data) for pattern in SECRET_CONTENT):
        # Do not echo the matching content or credential into terminal logs.
        raise ValueError(f'Possible embedded credential in static asset: {relative}')
    if relative.as_posix() == 'aipass-config.json':
        try:
            config = json.loads(data)
        except (ValueError, UnicodeDecodeError):
            raise ValueError('Public AI Pass configuration is not valid JSON.') from None
        if not isinstance(config, dict) or set(config) != {'clientId'}:
            raise ValueError('Public AI Pass configuration must contain only clientId.')
        client_id = config['clientId']
        if not isinstance(client_id, str) or not re.fullmatch(r'[A-Za-z0-9_-]{6,160}', client_id):
            raise ValueError('Public AI Pass clientId is missing or invalid.')
    return data


def distribution_files():
    if not DIST.is_dir():
        raise ValueError('No Wasm distribution. Run ./gradlew :webApp:wasmJsBrowserDistribution first.')
    for ancestor in [DIST, *DIST.parents]:
        if ancestor == REPO:
            break
        if ancestor.is_symlink():
            raise ValueError('Distribution directories must not be symbolic links.')
    files = []
    for directory, dirs, names in os.walk(DIST, followlinks=False):
        parent = Path(directory)
        for name in sorted(dirs + names):
            path = parent / name
            relative = path.relative_to(DIST)
            if path.is_symlink():
                raise ValueError(f'Symbolic links are not permitted in the distribution: {relative}')
            if path.is_dir():
                if any(part.startswith('.') or SECRET_NAMES.search(part) for part in relative.parts):
                    raise ValueError(f'Private directory in distribution: {relative}')
                if relative.parts[0] not in {'icons', 'fonts', 'audio', 'composeResources'}:
                    raise ValueError(f'Unrecognized directory in distribution: {relative}')
                continue
            if not path.is_file():
                raise ValueError(f'Non-regular file in distribution: {relative}')
            if check_path(relative):
                files.append(relative)
    names = {path.as_posix() for path in files}
    tutorial_audio_ready()
    missing = REQUIRED - names
    if missing:
        raise ValueError('Incomplete distribution; missing: ' + ', '.join(sorted(missing)))
    if not any(path.suffix == '.wasm' for path in files):
        raise ValueError('The distribution contains no Wasm executable.')
    return sorted(files)


def output_config():
    # Query strings do not participate in Vercel route matching. Both ordinary
    # entry loads and same-page OAuth callbacks therefore receive no-store.
    return {
        'version': 3,
        'routes': [
            {'src': '^/(.*)$', 'headers': {
                'X-Content-Type-Options': 'nosniff',
                'Referrer-Policy': 'strict-origin-when-cross-origin',
            }, 'continue': True},
            {'src': '^/(?:index\\.html|aipass-config\\.json|sw\\.js)?$',
             'headers': {'Cache-Control': 'no-store'}, 'continue': True},
            {'src': '^/[a-f0-9]{16,64}\\.wasm$', 'headers': {
                'Content-Type': 'application/wasm',
                'Cache-Control': 'public, max-age=31536000, immutable',
            }, 'continue': True},
            {'src': '^/audio/(?:mafia-[a-z0-9-]+|(?:tutorial|effects)/[a-z0-9_]+)\\.mp3$', 'headers': {
                'Content-Type': 'audio/mpeg',
                'Cache-Control': 'public, max-age=0, must-revalidate',
            }, 'continue': True},
            {'src': '^/(?:webApp\\.js|boot\\.js|ai-bridge\\.js|styles\\.css|manifest\\.webmanifest|offline\\.html|(?:icons|fonts|composeResources)/.*)$',
             'headers': {'Cache-Control': 'public, max-age=0, must-revalidate'}, 'continue': True},
            {'src': '^/$', 'dest': '/index.html'},
            {'handle': 'filesystem'},
        ],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--skip-build', action='store_true', help='Package the existing production distribution without invoking Gradle.')
    args = parser.parse_args()
    if not args.skip_build:
        subprocess.run([str(REPO / 'gradlew'), ':webApp:wasmJsBrowserDistribution'], cwd=REPO, check=True)
    files = distribution_files()
    if VERCEL.is_symlink() or OUTPUT.is_symlink():
        raise ValueError('.vercel and .vercel/output must not be symbolic links.')
    if OUTPUT.exists() and not OUTPUT.is_dir():
        raise ValueError('.vercel/output is not a generated output directory.')
    VERCEL.mkdir(exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix='.output-', dir=VERCEL))
    fingerprint = hashlib.sha256()
    total = 0
    try:
        static = staging / 'static'
        static.mkdir()
        for relative in files:
            source = DIST / relative
            # Recheck while copying, so a symlink introduced after enumeration
            # cannot make the packager read a different tree.
            if source.is_symlink() or DIST.resolve() not in source.resolve().parents:
                raise ValueError(f'Distribution changed during packaging: {relative}')
            data = read_public_asset(source, relative)
            destination = static / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(data)
            destination.chmod(0o644)
            fingerprint.update(relative.as_posix().encode('utf-8') + b'\0' + hashlib.sha256(data).digest())
            total += len(data)
        config = (json.dumps(output_config(), indent=2, ensure_ascii=False) + '\n').encode('utf-8')
        (staging / 'config.json').write_bytes(config)
        fingerprint.update(config)
        # Only generated output is replaced. Project links and local Vercel
        # settings alongside output are untouched.
        if OUTPUT.exists():
            shutil.rmtree(OUTPUT)
        staging.rename(OUTPUT)
    finally:
        if staging.exists():
            shutil.rmtree(staging)
    print(f'Prepared {len(files)} static files ({total / 1024 / 1024:.2f} MiB) in {OUTPUT}')
    print(f'Content fingerprint: {fingerprint.hexdigest()}')
    print('Project links preserved. Packaging complete; nothing was deployed.')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f'Packaging failed: {error}', file=sys.stderr)
        sys.exit(1)
