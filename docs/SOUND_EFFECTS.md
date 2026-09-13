# Game sound effects

Ten short bundled MP3s announce public game events. They use no provider, wallet,
or hosted generation at playback time. Effects have their own default-on sound
menu switch, remembered as `mafia.effects.enabled.v1` in local storage. Browser
playback still requires a user gesture.

| Cue | Sound | Public trigger |
| --- | --- | --- |
| `game_start` | Pizzicato flourish and warm minor chord | New game role reveal |
| `daybreak` | Real rooster crow | Day after night |
| `nightfall` | Real owl hoots | Night begins |
| `vote_open` | Wooden ballot taps | Discussion finishes |
| `vote_tied` | Two balanced taps | Tied public vote |
| `eliminated` | Low orchestral thud | Public daytime elimination |
| `night_killed` | Muted low pulse | Public night death announcement |
| `night_saved` | Gentle bell | Public no-death announcement |
| `town_win` | Rising major resolution | Town victory |
| `mafia_win` | Descending minor resolution | Mafia victory |

No sound is emitted for individual secret night selections or investigations.
Effects do not indicate a victim's hidden role, protected target, or killer.
They are optional presentation, never awaited by the game engine or paid AI turn.

The rooster recording is [“Medium rooster crowing” by alys / PDSounds](https://commons.wikimedia.org/wiki/File:Medium_rooster_crowing.ogg),
released into the public domain worldwide. The owl recording is the
[National Park Service's Yellowstone owl recording](https://commons.wikimedia.org/wiki/File:Barred_Owl,_Yellowstone_National_Park.oga),
a U.S. federal government work marked public domain in the United States.
Source credits and adaptation details are also shipped in
`audio/effects/CREDITS.txt`.

The other eight cues are original brief compositions, rendered using the macOS
sampled instruments: pizzicato strings, acoustic bass, timpani, woodblock,
vibraphone, and string ensemble. Rebuild with:

```sh
python3 scripts/create-effects.py --download-sources
```

After the first download, omit `--download-sources` to rebuild offline. Source
recordings, intermediate audio, and measurements remain in `build/sound-effects`.
Only the ten MP3s and credits enter the public app. They are 44.1 kHz stereo,
128 kbps, at most 4.7 seconds each, with fades and controlled peaks. The generator
checks decoded duration, size, and clipping and writes `report.json`.

The ElevenLabs tutorial narration is a separate pending asset set. Until all 39
recordings exist, its controls and automatic narration remain hidden. Packaging
accepts either none or all 39; a partial set fails. Only a complete set enables
the bridge's tutorial audio flag in the packaged output. This allows game updates
to ship while the teacher recordings await credentials and credits.
