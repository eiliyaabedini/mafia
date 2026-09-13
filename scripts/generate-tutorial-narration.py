#!/usr/bin/env python3
"""Inventory or explicitly generate the bundled Persian tutorial narration."""
import argparse
import fcntl
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "docs/tutorial-narration.json"
WORK = ROOT / "build/tutorial-narration-aipass"
ASSETS = ROOT / "webApp/src/webMain/resources/audio/tutorial"
JOURNAL = WORK / "journal.json"
BASE_URL = "https://aipass.one/v1"
MODEL = "gemini-3.1-flash-tts"
DEFAULT_VOICE = "Charon"
SPEED = 1.0
REQUEST_GAP_SECONDS = 35.0
SOURCE_LIMIT = 12 * 1024 * 1024
ASSET_LIMIT = 5 * 1024 * 1024


def digest(data):
    return hashlib.sha256(data).hexdigest()


def atomic(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("wb") as output:
        output.write(data)
        output.flush()
        os.fsync(output.fileno())
    os.replace(temporary, path)


def save(records):
    atomic(JOURNAL, (json.dumps(records, indent=2, ensure_ascii=False) + "\n").encode())


def api_key():
    key = os.environ.get("AIPASS_API_KEY", "").strip()
    if not key and (ROOT / ".env.local").is_file():
        for line in (ROOT / ".env.local").read_text().splitlines():
            match = re.fullmatch(r"\s*AIPASS_API_KEY\s*=\s*(['\"]?)([^\s'\"]+)\1\s*", line)
            if match:
                key = match[2]
                break
    if len(key) < 12 or any(character.isspace() for character in key):
        raise RuntimeError("Missing valid AIPASS_API_KEY; export it or use a literal .env.local assignment.")
    return key


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class ProviderError(RuntimeError):
    def __init__(self, code):
        self.code = code
        super().__init__(f"AI Pass HTTP {code}; request was not retried")


def api(path, key, payload=None, limit=2 * 1024 * 1024):
    data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode()
    request = urllib.request.Request(
        BASE_URL + path,
        data=data,
        method="GET" if data is None else "POST",
        headers={
            "Authorization": "Bearer " + key,
            "Content-Type": "application/json",
            "Accept": "application/json" if data is None else "audio/mpeg",
        },
    )
    try:
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=180) as response:
            raw = response.read(limit + 1)
            content_type = response.headers.get("Content-Type", "").split(";", 1)[0].lower()
    except urllib.error.HTTPError as error:
        raise ProviderError(error.code) from None
    except (urllib.error.URLError, TimeoutError, OSError):
        raise RuntimeError("AI Pass network/timeout failure; request was not retried") from None
    if not raw or len(raw) > limit:
        raise RuntimeError("AI Pass response was empty or exceeded the size limit")
    if payload is not None:
        if content_type not in {"audio/mpeg", "audio/mp3", "application/octet-stream"}:
            raise RuntimeError("AI Pass returned a non-audio speech response")
        return raw
    try:
        return json.loads(raw)
    except (ValueError, UnicodeError):
        raise RuntimeError("AI Pass returned invalid model metadata") from None


def probe(path, final=False):
    limit = ASSET_LIMIT if final else SOURCE_LIMIT
    if not path.is_file() or not 0 < path.stat().st_size <= limit:
        raise RuntimeError("Audio is missing, empty, or oversized")
    result = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries",
         "format=duration:stream=codec_name,sample_rate,channels", "-of", "json", str(path)],
        capture_output=True, text=True, timeout=20,
    )
    try:
        metadata = json.loads(result.stdout)
        duration = float(metadata["format"]["duration"])
        streams = metadata["streams"]
        stream = streams[0]
        valid = result.returncode == 0 and math.isfinite(duration) and 0 < duration <= 90
        valid = valid and len(streams) == 1 and stream["codec_name"] == "mp3"
        valid = valid and stream["channels"] in (1, 2)
        if final:
            valid = valid and stream["sample_rate"] == "44100"
    except (ValueError, KeyError, TypeError, IndexError):
        valid = False
    if not valid:
        raise RuntimeError("Audio failed MP3 playback validation")
    return duration


def clip_segments(clip, voice):
    return clip.get("segments") or [{"speaker": "narrator", "voice": voice, "text": clip["text"]}]


def signature(clip, voice):
    segments = clip_segments(clip, voice)
    result = {
        "provider": "aipass",
        "model": MODEL,
        "voice": voice if "segments" not in clip else "multi",
        "language": "fa",
        "response_format": "mp3",
        "speed": SPEED,
        "text_sha256": digest(clip["text"].encode()),
    }
    if "segments" in clip:
        encoded = json.dumps(segments, ensure_ascii=False, separators=(",", ":")).encode()
        result["segments_sha256"] = digest(encoded)
    return result


def paths(clip_id):
    return WORK / f"{clip_id}.source.mp3", WORK / f"{clip_id}.mp3", ASSETS / f"{clip_id}.mp3"


def source_paths(clip, voice):
    if "segments" not in clip:
        return [paths(clip["id"])[0]]
    return [WORK / f"{clip['id']}.part{index}.source.mp3"
            for index, _ in enumerate(clip_segments(clip, voice))]


def sources_match(clip, record, voice):
    sources = source_paths(clip, voice)
    if "segments" not in clip:
        expected = [record.get("source_audio_sha256")]
    else:
        parts = record.get("parts")
        if not isinstance(parts, list) or len(parts) != len(sources):
            return False
        expected = [part.get("source_audio_sha256") for part in parts]
    try:
        return all(expected_hash and source.is_file() and digest(source.read_bytes()) == expected_hash
                   and probe(source) > 0 for source, expected_hash in zip(sources, expected))
    except (OSError, RuntimeError, subprocess.SubprocessError):
        return False


def inspect(clip, records, voice):
    record = records.get(clip["id"], {})
    matches = all(record.get(key) == value for key, value in signature(clip, voice).items())
    _, cached, asset = paths(clip["id"])
    if record.get("status") in ("requesting", "uncertain"):
        return "uncertain", matches
    if matches and record.get("status") in ("generating", "rate_limited"):
        return "resume", True
    if matches and record.get("status") == "received" and sources_match(clip, record, voice):
        return "transcode", True
    candidate = asset if asset.is_file() else cached
    if matches and record.get("status") == "complete" and candidate.is_file():
        try:
            if digest(candidate.read_bytes()) == record.get("audio_sha256"):
                probe(candidate, final=True)
                return ("complete" if asset.is_file() else "restore"), True
        except (OSError, RuntimeError, subprocess.SubprocessError):
            pass
    if not record and not any(path.exists() for path in source_paths(clip, voice)) and not cached.exists() and not asset.exists():
        return "missing", False
    return "conflict", matches


def convert_and_install(clip, record, voice):
    sources = source_paths(clip, voice)
    for source in sources:
        probe(source)
    _, cached, asset = paths(clip["id"])
    temporary = WORK / f".{clip['id']}.converted.mp3"
    temporary.unlink(missing_ok=True)
    command = ["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y"]
    for source in sources:
        command += ["-i", str(source)]
    if len(sources) == 1:
        command += ["-map_metadata", "-1", "-vn", "-af", "loudnorm=I=-18:LRA=8:TP=-1.5",
                    "-ac", "1", "-ar", "44100"]
    else:
        filters = [f"[{index}:a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=mono[a{index}]"
                   for index in range(len(sources))]
        joined = "".join(f"[a{index}]" for index in range(len(sources)))
        filters.append(f"{joined}concat=n={len(sources)}:v=0:a=1,loudnorm=I=-18:LRA=8:TP=-1.5[out]")
        command += ["-filter_complex", ";".join(filters), "-map", "[out]", "-map_metadata", "-1", "-vn"]
    command += ["-ac", "1", "-ar", "44100", "-codec:a", "libmp3lame", "-b:a", "128k", str(temporary)]
    result = subprocess.run(command, capture_output=True, timeout=120)
    if result.returncode != 0:
        temporary.unlink(missing_ok=True)
        raise RuntimeError("ffmpeg could not standardize generated narration")
    duration = probe(temporary, final=True)
    raw = temporary.read_bytes()
    temporary.unlink(missing_ok=True)
    atomic(cached, raw)
    record.update(status="received", audio_sha256=digest(raw), bytes=len(raw), duration=duration)
    atomic(asset, raw)
    record.update(status="complete", updated_at=int(time.time()))
    return duration, len(raw)


def verify_model(key):
    catalog = api("/models", key)
    models = catalog.get("data") if isinstance(catalog, dict) else catalog
    if not isinstance(models, list):
        raise RuntimeError("AI Pass model catalog has an unsupported shape")
    model = next((item for item in models if isinstance(item, dict) and item.get("id") == MODEL), None)
    if not model or "audio_speech" not in model.get("methods", []):
        raise RuntimeError(f"{MODEL} is not currently available for speech")


def generate(selected, records, voice, retry_uncertain, replace_existing):
    pending = []
    for clip in selected:
        state, matches = inspect(clip, records, voice)
        if state == "uncertain" and not retry_uncertain:
            raise RuntimeError(f"{clip['id']}: prior request is uncertain; inspect usage before an explicit retry.")
        if (state == "conflict" or (state == "uncertain" and not matches)) and not replace_existing:
            raise RuntimeError(f"{clip['id']}: existing audio differs; --replace-existing is required.")
        if state == "complete":
            print(f"{clip['id']}: complete, no request")
        elif state == "restore":
            _, cached, asset = paths(clip["id"])
            atomic(asset, cached.read_bytes())
            print(f"{clip['id']}: restored, no request")
        elif state == "transcode":
            duration, size = convert_and_install(clip, records[clip["id"]], voice)
            save(records)
            print(f"{clip['id']}: installed, {duration:.2f}s, {size} bytes")
        else:
            pending.append((clip, state, matches))
    if not pending:
        return
    key = api_key()
    verify_model(key)
    print(f"Verified {MODEL}; generating {len(pending)} clip(s).")
    last_request_started = 0.0
    for clip, prior_state, matches in pending:
        clip_id = clip["id"]
        segments = clip_segments(clip, voice)
        existing = records.get(clip_id, {})
        resuming = matches and prior_state in ("resume", "uncertain")
        parts = existing.get("parts") if resuming else None
        if not isinstance(parts, list) or len(parts) != len(segments):
            parts = [{} for _ in segments]
        record = existing if resuming else {}
        record.update({
            **signature(clip, voice), "status": "generating",
            "characters": sum(len(segment["text"]) for segment in segments),
            "attempt": existing.get("attempt", 0) + 1, "updated_at": int(time.time()), "parts": parts,
        })
        records[clip_id] = record
        save(records)
        for index, segment in enumerate(segments):
            part = parts[index]
            source = source_paths(clip, voice)[index]
            try:
                if part.get("status") == "complete" and source.is_file() \
                        and digest(source.read_bytes()) == part.get("source_audio_sha256"):
                    probe(source)
                    continue
            except (OSError, RuntimeError, subprocess.SubprocessError):
                pass
            part.clear()
            part.update({
                "speaker": segment["speaker"], "voice": segment["voice"],
                "text_sha256": digest(segment["text"].encode()), "characters": len(segment["text"]),
                "status": "requesting", "updated_at": int(time.time()),
            })
            record.update(status="requesting", active_part=index, updated_at=int(time.time()))
            save(records)
            try:
                wait = REQUEST_GAP_SECONDS - (time.monotonic() - last_request_started)
                if wait > 0:
                    time.sleep(wait)
                last_request_started = time.monotonic()
                raw = api("/audio/speech", key, {
                    "model": MODEL, "input": segment["text"], "voice": segment["voice"],
                    "response_format": "mp3", "speed": SPEED,
                }, limit=SOURCE_LIMIT)
                atomic(source, raw)
                probe(source)
                part.update(status="complete", source_audio_sha256=digest(raw), source_bytes=len(raw),
                            updated_at=int(time.time()))
                record.update(status="generating", active_part=None, updated_at=int(time.time()))
                save(records)
                print(f"{clip_id}: voice part {index + 1}/{len(segments)} complete ({segment['speaker']}, {segment['voice']})")
            except ProviderError as error:
                state = "rate_limited" if error.code == 429 else "uncertain"
                part.update(status=state, updated_at=int(time.time()))
                record.update(status=state, active_part=index, updated_at=int(time.time()))
                save(records)
                raise
            except BaseException:
                part.update(status="uncertain", updated_at=int(time.time()))
                record.update(status="uncertain", active_part=index, updated_at=int(time.time()))
                save(records)
                raise
        record.update(status="received", active_part=None, updated_at=int(time.time()))
        if len(segments) == 1:
            record.update(source_audio_sha256=parts[0]["source_audio_sha256"],
                          source_bytes=parts[0]["source_bytes"])
        save(records)
        duration, size = convert_and_install(clip, record, voice)
        save(records)
        print(f"{clip_id}: complete, {duration:.2f}s, {size} bytes, {len(segments)} voice part(s)")


def import_audition(clip, source_path, records, voice, replace_existing):
    if "segments" in clip:
        raise RuntimeError("A multi-voice clip cannot be imported as a single audition")
    state, _ = inspect(clip, records, voice)
    if state != "missing" and not replace_existing:
        raise RuntimeError("Existing state differs; --replace-existing is required for an import.")
    raw = source_path.read_bytes()
    if not raw or len(raw) > SOURCE_LIMIT:
        raise RuntimeError("Audition is missing, empty, or oversized")
    source, _, _ = paths(clip["id"])
    atomic(source, raw)
    probe(source)
    part = {
        "speaker": "narrator", "voice": voice, "text_sha256": digest(clip["text"].encode()),
        "characters": len(clip["text"]), "status": "complete", "source_audio_sha256": digest(raw),
        "source_bytes": len(raw), "updated_at": int(time.time()),
    }
    record = {
        **signature(clip, voice), "status": "received", "characters": len(clip["text"]),
        "attempt": 1, "source_audio_sha256": digest(raw), "source_bytes": len(raw),
        "updated_at": int(time.time()), "origin": "explicit-audition-import", "parts": [part],
    }
    records[clip["id"]] = record
    save(records)
    duration, size = convert_and_install(clip, record, voice)
    save(records)
    print(f"{clip['id']}: audition imported, {duration:.2f}s, {size} bytes")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--generate", action="store_true")
    mode.add_argument("--import-audition", type=Path)
    parser.add_argument("--voice", default=DEFAULT_VOICE)
    parser.add_argument("--clip", help="One exact clip ID")
    parser.add_argument("--retry-uncertain", action="store_true")
    parser.add_argument("--replace-existing", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_-]{0,63}", args.voice):
        parser.error("invalid voice name")
    if args.import_audition and not args.clip:
        parser.error("--import-audition requires --clip")
    if args.retry_uncertain and (not args.generate or not args.clip):
        parser.error("--retry-uncertain requires --generate and one --clip")
    manifest = json.loads(MANIFEST.read_text())
    clips = manifest["clips"]
    ids = [clip["id"] for clip in clips]
    expected_count = manifest.get("clipCount")
    if not isinstance(expected_count, int) or expected_count != len(clips) \
            or len(set(ids)) != len(clips) \
            or any(not re.fullmatch(r"[a-z][a-z0-9_]*", item) for item in ids):
        raise RuntimeError("Manifest clipCount must match its unique safe clip IDs")
    if any(not isinstance(clip.get("text"), str) or not clip["text"].strip() for clip in clips):
        raise RuntimeError("Manifest contains an empty narration")
    for clip in clips:
        segments = clip.get("segments")
        if segments is not None and (not isinstance(segments, list) or len(segments) < 2):
            raise RuntimeError("Multi-voice narration requires at least two segments")
        for segment in segments or []:
            if not isinstance(segment, dict) or set(segment) != {"speaker", "voice", "text"} \
                    or not all(isinstance(segment[field], str) and segment[field].strip()
                               for field in ("speaker", "voice", "text")) \
                    or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_-]{0,63}", segment["voice"]):
                raise RuntimeError("Manifest contains an invalid voice segment")
    count = sum(sum(len(segment["text"]) for segment in clip.get("segments", []))
                if clip.get("segments") else len(clip["text"]) for clip in clips)
    if manifest.get("spokenCharacters") != count:
        raise RuntimeError("Manifest character count does not match its text")
    if args.clip and args.clip not in ids:
        parser.error("unknown clip ID")
    selected = [clip for clip in clips if not args.clip or clip["id"] == args.clip]
    records = json.loads(JOURNAL.read_text()) if JOURNAL.exists() else {}
    print(f"Manifest: {len(clips)} clips, {count} characters; selected: {len(selected)} clips.")
    if not args.generate and not args.import_audition:
        for clip in selected:
            print(f"{clip['id']}: {inspect(clip, records, args.voice)[0]}")
        print("Dry run only: no credentials read, provider calls made, or files written.")
        return
    if not shutil.which("ffprobe") or not shutil.which("ffmpeg"):
        raise RuntimeError("ffmpeg and ffprobe are required")
    WORK.mkdir(parents=True, exist_ok=True)
    with (WORK / "batch.lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeError("Another narration generation is running") from None
        records = json.loads(JOURNAL.read_text()) if JOURNAL.exists() else {}
        if args.import_audition:
            import_audition(selected[0], args.import_audition.resolve(), records, args.voice, args.replace_existing)
        else:
            generate(selected, records, args.voice, args.retry_uncertain, args.replace_existing)


if __name__ == "__main__":
    try:
        main()
    except RuntimeError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
        print("Local input or processing failed; inspect the journal before retrying.", file=sys.stderr)
        sys.exit(1)
