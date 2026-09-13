# Mafia background music playlist

The current game uses six owner-provided **The Don's Gambit** recordings. This document also preserves the score and renderer for the earlier original local candidate, **A Promise After Midnight — Mafia Waltz**.

## Shipping playlist

The web game rotates six bundled tracks. A new session randomly starts with one of the three preferred instrumentals. After a track finishes, weighted random selection chooses a different track, so the same recording never plays twice in a row. The lower-priority versions are excluded from every fresh start. Selection weights make the first vocal track a rare Easter egg and let the alternate vocal version appear occasionally without competing equally with the preferred instrumentals.

| Asset | Source | Duration | May start? | Later weight |
| --- | --- | ---: | :---: | ---: |
| `mafia-nocturne.mp3` | Existing owner-provided main track | 156.416 s | Yes | 100 |
| `mafia-dons-gambit-main.mp3` | User-provided Suno export | 187.512 s | Yes | 100 |
| `mafia-dons-gambit-short.mp3` | User-provided short Suno export | 60.048 s | Yes | 100 |
| `mafia-dons-gambit-late.mp3` | User-provided lower-priority Suno export | 174.00 s | No | 24 |
| `mafia-dons-gambit-vocal-alt.mp3` | User-provided lower-priority alternate vocal | 247.008 s | No | 12 |
| `mafia-dons-gambit-vocal-rare.mp3` | User-provided vocal Easter egg | 247.992 s | No | 2 |

The five newer Suno exports were copied from the owner's Downloads folder. Their embedded cover-art streams and metadata were removed, then the audio was normalized near the existing background level and encoded as stereo 44.1 kHz, 128 kbps MP3. The short version has a six-second fade from 54.048 seconds through its end. Its mean level falls from −20.3 dB in seconds 50–54 to −34.9 dB in seconds 58–60.

| Source filename | Source SHA-256 | Shipping SHA-256 |
| --- | --- | --- |
| `the-dons-gambit [usesuno.com].mp3` | `56011d00b1830795d00b785c59ab1d2bfb56ce1f3933c1262c5fb0788767788f` | `da2bfe721851378663603488a82e45381d97ed24ddeb7b7c3e8a48117ca9ceaf` |
| `the-dons-gambit [usesuno.com] (1).mp3` | `1ef49f4c7058ee201318684a43e65802c95bbcfebd311b09f74766fedc85de6a` | `f57b8b3b808c03cc9b4bda4f5861e6a2b40fa3e276d63aba0f13d544654efea9` |
| `the-dons-gambit [usesuno.com] (2).mp3` | `e8fe7d54477b89b1acec29b993d0fa3b84d34da2f657ffde0ff9239c967ddd10` | `ccc049ae901962b157d464a45b4950c0d38a8e23ccd883870e4e22704dbf2d5e` |
| `the-dons-gambit-with-vocal.mp3` | `5951f058aa3d0fd518020afdc65497117dd40ccb349079ef4d4192facf821197` | `fa9e6d3a98f877948490bebe8ad6b2d83e880ac5fe55dce27e0bb00a6025ac7a` |
| `the-dons-gambit-with-vocal2.mp3` | `6071670c2aee49a426793ed8760c9b80eba45e2cf432ad5c47d97bffbb929045` | `3ce58da055e0efc7522da2a61def22e7921784c211727f6129c7c9f4109569e7` |

The previously installed `mafia-nocturne.mp3` has shipping SHA-256 `efdd0402fe8ef3bb0c129f95f30a60146001845ee9c2c519e5db06e1193e9f04`.

## Earlier original score candidate

**A Promise After Midnight — Mafia Waltz** is an original local composition in the idiom of classic Italian crime cinema. It uses a newly written melodic theme; it does not quote the Godfather/Nino Rota melody, sample a film recording, or use ElevenLabs, a hosted music model, or any paid API.

The score is **32 bars of 3/4 at 72 BPM**, centered on **G minor**, lasting **80 seconds**. A foreground trumpet theme has eight-bar phrases and a varied second statement. A picked, tremolo-nylon melodic bridge answers it before the trumpet returns in a stronger reprise. Plucked acoustic bass marks the downbeats; quiet accordion chords mark beats two and three. Warm strings tie common notes across chord changes. The accompaniment includes mandolin-like tremolo made from a nylon-guitar sample, not an actual mandolin sample.

The complete pitches, rhythms, harmonies, velocities, breath rests, instrument balances, and arrangement are specified in [`create-soundtrack.swift`](../scripts/create-soundtrack.swift). Expressive timing and dynamics are authored by phrase, not randomized. There is no piano, percussion, or vocal part in version 2.

## Original-score reproduction files

Version 2 is retained as a reproducible earlier candidate and is no longer the shipping `mafia-nocturne.mp3`. The renderer writes the following temporary review files; rerendering alone does not replace any bundled asset.

| File | Format | Size |
| --- | --- | ---: |
| `/tmp/mafia-waltz-v2.wav` | Stereo, 44.1 kHz, 16-bit PCM | 14,112,044 bytes |
| `/tmp/mafia-waltz-v2.mp3` | Stereo, 44.1 kHz, 128 kbps MP3 | 1,281,272 bytes |
| `/tmp/mafia-waltz-v2.png` | Stereo waveform overview | PNG |
| `/tmp/mafia-waltz-v2.json` | Rendering and audio measurements | JSON |

Both audio formats decode to exactly **3,528,000 frames / 80 seconds**. On macOS, `/tmp` can appear as its resolved path `/private/tmp` in the report.

## Local rendering and provenance

The renderer uses macOS `AVAudioUnitSampler` with the installed system sound bank:

```
/System/Library/Components/CoreAudio.component/Contents/Resources/gs_instruments.dls
```

The zero-based General MIDI programs are:

| Musical role | Sample-bank instrument | Program |
| --- | --- | ---: |
| Solo lead | Trumpet | 56 |
| Tremolo accompaniment and picked melodic bridge | Nylon guitar | 24 |
| Sustained harmony | String ensemble | 48 |
| Quiet waltz offbeats | Accordion | 21 |
| Plucked downbeat bass | Acoustic bass | 32 |

The nylon accompaniment and bridge use separate sampler instances so their note releases cannot cut one another off. The system bank is read in place; no bank or individual sample is copied into the repository or redistributed.

Apple documents [loading DLS instruments into the sampler](https://developer.apple.com/documentation/avfaudio/avaudiounitsampler/loadsoundbankinstrument(at:program:bankmsb:banklsb:)) and [offline audio-engine rendering](https://developer.apple.com/documentation/avfaudio/avaudioengine/renderoffline(_:to:)). Reproduction requires macOS with that bank, Swift, Python 3 with NumPy, and `ffmpeg` with `libmp3lame`. Rendering requires no network access or credentials.

From the repository root:

```sh
python3 scripts/create-soundtrack.py
```

To choose another candidate path:

```sh
python3 scripts/create-soundtrack.py --output-prefix /tmp/another-mafia-candidate
```

The wrapper renders the score, applies modest room reverb and tonal filtering, then uses constant mastering gain toward −19 LUFS with at least approximately 4 dB of peak headroom. It writes the WAV, MP3, waveform, and measurement report, and removes intermediate files. Installed sound-bank and audio-unit versions may change exact sample waveforms; the original score is fully reproducible from source.

## Continuous looping

Three identical score cycles are rendered. The first lets instrument and room tails settle. The remaining two are combined at matching musical positions using complementary smooth weights. The final loop begins at the third cycle's first sample and ends at the second cycle's last sample; those samples were adjacent in the continuous source render. This preserves all 32 bars and creates no recurring fade to silence. The last bar resolves to the opening G-minor harmony, with accompaniment continuing under the trumpet's breath.

The MP3 includes a Xing/LAME header for gapless decoding. Its decoded frame count was measured directly. Playback should loop the full decoded buffer; reloading separate media elements can introduce a player-side gap regardless of the audio file's seam.

## Numerical production checks

| Measurement | WAV | Decoded MP3 |
| --- | ---: | ---: |
| Integrated loudness | −19.00 LUFS | −19.45 LUFS |
| True peak | −6.17 dBTP | −6.68 dBTP |
| Loudness range | 3.5 LU | 3.5 LU |
| Quietest 100 ms RMS | −38.34 dBFS | −38.78 dBFS |
| 100 ms windows below −60 dBFS | 0 | 0 |
| Clipped samples | 0 | 0 |
| Last-to-first sample step | −60.48 dBFS | −54.37 dBFS |
| Nearby sample steps, 99th percentile | −33.00 dBFS | −33.34 dBFS |

The join's step is substantially smaller than normal nearby waveform steps in both formats. These checks establish levels, decoded duration, and numerical continuity; they are not a claim of audible listening review. Final acceptance should include listening in the game alongside speech, including the intended background-volume setting.
