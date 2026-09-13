#!/usr/bin/env python3
"""Build ten short local game cues. No hosted AI or credentials.

Requires macOS, Swift, NumPy and ffmpeg. --download-sources retrieves only the
two fixed public-domain bird recordings documented in docs/SOUND_EFFECTS.md.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import subprocess
import urllib.request
import wave

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / 'build/sound-effects'
OUT = ROOT / 'webApp/src/webMain/resources/audio/effects'
RATE = 44_100
BIRDS = {
    'rooster.ogg': 'https://upload.wikimedia.org/wikipedia/commons/0/0d/Medium_rooster_crowing.ogg',
    'owl.oga': 'https://upload.wikimedia.org/wikipedia/commons/e/ea/Barred_Owl%2C_Yellowstone_National_Park.oga',
}


def run(args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)


def decode(path, filters=None):
    args = ['ffmpeg', '-v', 'error', '-i', str(path)]
    if filters:
        args += ['-af', filters]
    args += ['-f', 'f32le', '-ar', str(RATE), '-ac', '2', '-']
    return np.frombuffer(run(args, stdout=subprocess.PIPE).stdout, dtype='<f4').reshape(-1, 2).copy()


def db(value):
    return 20 * math.log10(max(float(value), 1e-12))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--download-sources', action='store_true')
    args = parser.parse_args()
    sources = WORK / 'sources'
    sources.mkdir(parents=True, exist_ok=True)
    for name, url in BIRDS.items():
        target = sources / name
        if not target.exists():
            if not args.download_sources:
                raise RuntimeError('Missing bird recordings: use --download-sources once.')
            request = urllib.request.Request(url, headers={'User-Agent': 'MafiaGameAssetBuild/1.0'})
            with urllib.request.urlopen(request, timeout=25) as response:
                data = response.read(2_000_001)
            if not data.startswith(b'OggS') or len(data) > 2_000_000:
                raise RuntimeError('Expected a small Ogg recording')
            target.write_bytes(data)
    rendered = WORK / 'rendered'
    run(['swift', str(Path(__file__).with_suffix('.swift')), str(rendered)])
    OUT.mkdir(parents=True, exist_ok=True)
    clips = {p.stem: decode(p, 'highpass=f=40,lowpass=f=7000') for p in rendered.glob('*.caf')}
    # One full crow, with a small natural lead-in and tail. Owl is three hoots.
    clips['daybreak'] = decode(sources / 'rooster.ogg', 'highpass=f=180,lowpass=f=6500')[int(2.45 * RATE):int(5.95 * RATE)]
    clips['nightfall'] = decode(sources / 'owl.oga', 'highpass=f=100,lowpass=f=5000')[:int(4.7 * RATE)]
    report = []
    for cue, samples in sorted(clips.items()):
        attack, release = min(441, len(samples)), min(8820, len(samples))
        samples[:attack] *= np.linspace(0, 1, attack)[:, None]
        samples[-release:] *= np.linspace(1, 0, release)[:, None]
        rms = np.sqrt(np.mean(samples ** 2))
        peak = np.max(np.abs(samples))
        samples *= min(10 ** (-23 / 20) / max(rms, 1e-8), 10 ** (-7 / 20) / max(peak, 1e-8))
        wav = WORK / (cue + '.wav')
        with wave.open(str(wav), 'wb') as file:
            file.setnchannels(2); file.setsampwidth(2); file.setframerate(RATE)
            file.writeframes(np.round(samples * 32767).astype('<i2').tobytes())
        mp3 = OUT / (cue + '.mp3')
        artist = 'alys / PDSounds (public domain)' if cue == 'daybreak' else 'National Park Service (public domain)' if cue == 'nightfall' else 'Mafia Game'
        run(['ffmpeg', '-v', 'error', '-y', '-i', str(wav), '-c:a', 'libmp3lame', '-b:a', '128k',
             '-ar', str(RATE), '-ac', '2', '-metadata', 'title=' + cue, '-metadata', 'artist=' + artist, str(mp3)])
        actual = decode(mp3)
        if len(actual) / RATE > 5 or mp3.stat().st_size > 1_048_576 or np.max(np.abs(actual)) >= 1:
            raise RuntimeError('Effect exceeds browser limits or clips: ' + cue)
        report.append({'cue': cue, 'seconds': round(len(actual) / RATE, 3), 'bytes': mp3.stat().st_size,
                       'peak_dbfs': round(db(np.max(np.abs(actual))), 2),
                       'rms_dbfs': round(db(np.sqrt(np.mean(actual ** 2))), 2),
                       'sha256': hashlib.sha256(mp3.read_bytes()).hexdigest()})
    if len(report) != 10:
        raise RuntimeError('Expected exactly ten game cues')
    (WORK / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
