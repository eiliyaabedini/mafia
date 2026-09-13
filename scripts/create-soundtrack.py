#!/usr/bin/env python3
"""Render and master the original local Mafia loop. Requires macOS, NumPy, Swift, ffmpeg.

No network, hosted model, credentials, downloaded music, or exported sound bank.
The complete score and instrument choices live in create-soundtrack.swift.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
import subprocess
import tempfile
import wave

import numpy as np

RATE = 44_100
FRAMES = 3_528_000  # 32 bars, 3/4, 72 BPM: exactly 80 seconds.


def run(arguments: list[str], **kwargs) -> subprocess.CompletedProcess:
    return subprocess.run(arguments, check=True, **kwargs)


def decode(path: Path, filters: str | None = None) -> np.ndarray:
    command = ["ffmpeg", "-v", "error", "-i", str(path)]
    if filters:
        command += ["-af", filters]
    command += ["-f", "f32le", "-acodec", "pcm_f32le", "-ar", str(RATE), "-ac", "2", "-"]
    return np.frombuffer(run(command, stdout=subprocess.PIPE).stdout, dtype="<f4").reshape(-1, 2)


def write_wav(path: Path, samples: np.ndarray) -> None:
    pcm = np.round(np.clip(samples, -1.0, 1.0) * 32767).astype("<i2")
    with wave.open(str(path), "wb") as output:
        output.setnchannels(2)
        output.setsampwidth(2)
        output.setframerate(RATE)
        output.writeframes(pcm.tobytes())


def loudness(path: Path) -> dict[str, float]:
    result = run([
        "ffmpeg", "-hide_banner", "-i", str(path), "-af",
        "loudnorm=I=-19:TP=-4:LRA=7:print_format=json", "-f", "null", "-",
    ], stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
    data = json.loads(result.stderr[result.stderr.rfind("{"):result.stderr.rfind("}") + 1])
    return {key: float(data[key]) for key in ("input_i", "input_tp", "input_lra", "input_thresh")}


def db(value: float) -> float:
    return 20 * math.log10(max(value, 1e-12))


def characteristics(samples: np.ndarray) -> dict:
    # Production QA measurements, not a claim of listening or subjective quality.
    window = RATE // 10
    complete = samples[:len(samples) // window * window]
    rms = np.sqrt(np.mean(complete.reshape(-1, window, 2) ** 2, axis=(1, 2)))
    edge = RATE // 10
    boundary = float(np.max(np.abs(samples[0] - samples[-1])))
    nearby_steps = np.concatenate((np.diff(samples[:edge], axis=0), np.diff(samples[-edge:], axis=0)))
    return {
        "frames": len(samples),
        "seconds": len(samples) / RATE,
        "sample_peak_dbfs": db(float(np.max(np.abs(samples)))),
        "rms_dbfs": db(float(np.sqrt(np.mean(samples ** 2)))),
        "quietest_100ms_rms_dbfs": db(float(np.min(rms))),
        "100ms_windows_below_minus_60_dbfs": int(np.sum(rms < 0.001)),
        "boundary_step_dbfs": db(boundary),
        "nearby_step_99th_percentile_dbfs": db(float(np.quantile(np.abs(nearby_steps), 0.99))),
        "clipped_samples": int(np.sum(np.abs(samples) >= 1)),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-prefix", default="/tmp/mafia-waltz-v2")
    args = parser.parse_args()
    prefix = Path(args.output_prefix).resolve()
    prefix.parent.mkdir(parents=True, exist_ok=True)
    source = Path(__file__).with_suffix(".swift")
    with tempfile.TemporaryDirectory(prefix="mafia-waltz-v2-", dir="/tmp") as temporary:
        scratch = Path(temporary)
        raw = scratch / "three-cycles.caf"
        stage = scratch / "unmastered.wav"
        run(["swift", str(source), str(raw)])
        # Filter before cutting so the filter state crosses the eventual seam.
        rendered = decode(raw, "highpass=f=35,equalizer=f=2600:t=q:w=0.8:g=-2.5,lowpass=f=6200")
        if len(rendered) != FRAMES * 3:
            raise RuntimeError(f"Unexpected rendered frame count: {len(rendered)}")
        middle = rendered[FRAMES:FRAMES * 2]
        final = rendered[FRAMES * 2:FRAMES * 3]
        # Smoothly combine equal score positions in the two settled cycles.
        # Beginning = third-cycle start; ending = second-cycle end: those are
        # adjacent real source samples. No fade-to-silence or shortened bar.
        weight = (0.5 + 0.5 * np.cos(np.linspace(0, math.pi, FRAMES))).astype(np.float32)[:, None]
        loop = middle * (1 - weight) + final * weight
        write_wav(stage, loop)
        measured = loudness(stage)
        gain_db = min(-19.0 - measured["input_i"], -4.0 - measured["input_tp"])
        loop *= 10 ** (gain_db / 20)
        wav = prefix.with_suffix(".wav")
        mp3 = prefix.with_suffix(".mp3")
        write_wav(wav, loop)
        run([
            "ffmpeg", "-v", "error", "-y", "-i", str(wav),
            "-c:a", "libmp3lame", "-b:a", "128k", "-ar", str(RATE), "-ac", "2",
            "-write_xing", "1", "-metadata", "title=A Promise After Midnight — Mafia Waltz",
            "-metadata", "artist=Mafia Game", "-metadata", "genre=Soundtrack",
            "-metadata", "comment=Original local composition; macOS sampled rendering; not ElevenLabs.",
            str(mp3),
        ])
        waveform = prefix.with_suffix(".png")
        run(["ffmpeg", "-v", "error", "-y", "-i", str(mp3), "-filter_complex",
             "showwavespic=s=1600x360:colors=0xE0B77A|0x94D3BA:split_channels=1",
             "-frames:v", "1", str(waveform)])
        report = {
            "title": "A Promise After Midnight — Mafia Waltz",
            "origin": "Original local score; macOS sampled rendering; not ElevenLabs",
            "instruments": ["trumpet", "nylon guitar tremolo (mandolin-like)", "string ensemble", "accordion", "plucked acoustic bass"],
            "bpm": 72, "bars": 32, "meter": "3/4", "key_center": "G minor",
            "sample_rate": RATE, "channels": 2, "master_gain_db": gain_db,
            "wav": {"path": str(wav), "bytes": wav.stat().st_size,
                    **loudness(wav), **characteristics(decode(wav))},
            "mp3": {"path": str(mp3), "bytes": mp3.stat().st_size, "bitrate": 128_000,
                    **loudness(mp3), **characteristics(decode(mp3))},
            "review": "Measured only; no claim of audible listening review.",
        }
        report_path = prefix.with_suffix(".json")
        report_path.write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
