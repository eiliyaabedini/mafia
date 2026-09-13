# Persian tutorial narration manifest

[`tutorial-narration.json`](tutorial-narration.json) contains the complete **41-clip** prerecorded teaching script. It contains **6,817 spoken characters**, counted as Unicode code points in each requested segment, including spaces, punctuation, and Persian zero-width non-joiners. JSON formatting, clip IDs, source references, and voice-direction metadata are excluded.

The narration was generated through AI Pass and installed as local application assets. Playing it makes no synthesis request and does not use the player's wallet. The character count is a reproducibility record, not a provider credit quote; billing depends on the speech model and account.

## Recording direction

Use `Charon` as a warm, mature and patient narrator speaking conversational Iranian Persian. In game examples, the narrator says whose turn it is and the character then speaks their own line: Sara uses `Kore`, Mina uses `Aoede`, Shahab uses `Iapetus`, and Arman uses `Orus`. This gives dialogue clear female and male voices without asking one narrator to impersonate everyone.

When a clip contains `segments`, speak the segment text in order with its declared voice; otherwise speak `clip.text` with the narrator. Do not prepend IDs, English labels, titles, metadata, or generated introductions. The spoken copy has no English words, speech markup, or hidden role information outside the explicitly fictional role assigned in its scenario. Numbers are written out in Persian for pronunciation.

The clips use one to four sentences, with short feedback clips and somewhat longer rule cards or attributed dialogue. Actual duration and pronunciation must be checked in the generated audio; no listening or synthesized-duration review has occurred yet. Reusing an accepted audition clip in the final batch avoids regenerating that same text unnecessarily.

## Stable clip contract

Every clip contains `id`, `text`, `kind`, `track`, and `sourceResources`. Lesson and scenario clips also have `contentId`; feedback clips additionally have a zero-based `choiceIndex` matching the actual UI order. Game examples may contain ordered `segments` with a speaker, Gemini voice, and spoken text.

| Content | IDs | Count |
| --- | --- | ---: |
| Beginner cards | `basic_welcome`, `basic_citizen`, `basic_roles`, `basic_cycle`, `basic_night`, `basic_outcomes` | 6 |
| Practice introductions | `practice_{listen,question,vote,night,finish}_intro` | 5 |
| Practice feedback | Each practice prefix followed by `_feedback_0`, `_feedback_1`, `_feedback_2` | 15 |
| Advanced introductions | `strategy_{evidence,detective,teammate}_intro` | 3 |
| Advanced feedback | Each advanced prefix followed by `_feedback_0`, `_feedback_1`, `_feedback_2` | 9 |
| Track completions | `complete_basics`, `complete_practice`, `complete_strategy` | 3 |

All IDs use only lowercase ASCII letters, digits, and underscores. Audio filenames should be the exact clip ID plus the chosen extension, for example `practice_vote_feedback_2.mp3`. A manifest consumer must not infer choice order from filenames beyond the explicit zero-based index.

## Choice attribution review

The feedback indices were checked against [`TutorialContent.kt`](../shared/src/commonMain/kotlin/ir/iact/mafiagame/ui/TutorialContent.kt):

| Scenario | Choice 0 | Choice 1 | Choice 2 |
| --- | --- | --- | --- |
| Practice: listen | Notice the mismatch | Claim Shahab is certainly Mafia | Claim Arman is certainly Town |
| Practice: question | Urge everyone to vote Shahab | Ask which statement Shahab means | Claim to be Detective |
| Practice: vote | Eliminate Shahab | Eliminate both tied players | Eliminate nobody |
| Practice: night | Assume Sara was protected | No death and no established role | Assume all Mafia are eliminated |
| Practice: finish | Town wins as a team | Only living players win | All roles must first be guessed |
| Strategy: evidence | Treat silence as proof | Ask Mina a specific question | Treat Arman's mistake as proof |
| Strategy: detective | Assume Doctor protection | Assume the result is public | Weigh disclosure's benefit and risk |
| Strategy: teammate | Question the inconsistency | Defend everything Sara says | Reveal the Mafia partnership |

## Meaning and privacy review

The source copy is [`tutorial_content.xml`](../shared/src/commonMain/composeResources/values/tutorial_content.xml), with track completion titles/bodies from [`tutorial.xml`](../shared/src/commonMain/composeResources/values/tutorial.xml). Each clip records its relevant resource names. Narration adapts the written register into conversational speech without changing rules or decisions; some feedback also voices the scenario's existing takeaway.

- The current opening card is “مافیا را پیدا کن”: seven players, two Mafia and five Town members, randomly assigned real-game roles, and a Citizen role in the guided exercise.
- The role counts, two discussion passes, no self-vote, simultaneous ballot reveal, tie rule, private night choices, repeatable Doctor self-protection, hidden eliminated roles, and living-player victory counts are preserved. The dedicated night card teaches the engine's exact Mafia, Doctor, Detective order, the kill/protection interaction, and the Detective's private Mafia/Town result before the no-death practice question.
- The practice vote is three for Shahab, three for Sara, and one for Arman. No one is eliminated; the same seven reach the night and remain after the no-death announcement.
- The night narration names no protected player or Doctor. Protection is explained conditionally in feedback. The hypothetical ending is explicitly an example, not a claim that a complete game occurred.
- The advanced scenes are explicitly separate. The Detective knows only the stated private result about Shahab, has no result about Mina, and cannot assume Doctor protection. Other players receive a claim, not the private result itself.
- The Mafia scene explicitly gives the learner private knowledge that Sara is the teammate. Feedback discusses strategic tradeoffs without treating automatic defense or performative attacks as always successful.
- Introductions present the situation and attributed dialogue without naming a recommended answer. Only the practice-question introduction reads its three options, in the same order as the UI.
- Each of the 24 choices has its own gentle feedback. Track completion narration describes finishing an exercise, not winning an actual Mafia match.

All referenced resources resolve, all 41 IDs are unique and safe, and spoken fields contain no Latin letters. The manifest text is frozen at the character count above for generation; any later wording change should be flagged before reusing previously generated clips.

## Local batch generator

[`generate-tutorial-narration.py`](../scripts/generate-tutorial-narration.py) defaults to an entirely offline inventory. It reads no credentials, calls no provider, and writes no files in this mode:

```sh
python3 scripts/generate-tutorial-narration.py
```

The current manifest is **41 clips and 6,817 requested characters**. Python syntax is checked before generation.

Generation requires an explicit `--generate`. The key is read from an exported `AIPASS_API_KEY` in the environment, or an exact literal `AIPASS_API_KEY=...` line in the repository's ignored `.env.local`. The script never sources that file, expands shell expressions, prints the key, or includes it in its journal. The generated files are bundled static assets, so playing the tutorial does not call AI Pass or spend a player's wallet balance.

The selected narrator is Gemini's mature `Charon` voice. To produce only the 175-character rules card for an audition:

```sh
export AIPASS_API_KEY
python3 scripts/generate-tutorial-narration.py --generate --clip basic_citizen
```

After reviewing the voice, generate the remaining batch with the same configuration:

```sh
export AIPASS_API_KEY
python3 scripts/generate-tutorial-narration.py --generate
```

Before a speech request, the script verifies the live AI Pass catalog still exposes `gemini-3.1-flash-tts` with the `audio_speech` method. Paid requests are sequential, spaced to respect the provider's rate limit, and never retried automatically.

Requests use fixed model `gemini-3.1-flash-tts`, declared narrator/character voices, MP3 output, and normal speaking speed. Multi-voice clips are assembled locally in their declared order. The provider's 24 kHz sources are normalized locally and encoded as mono 44.1 kHz MP3 for consistent browser playback. There is no voice cloning, model fallback, provider fallback, or automatic retry.

## Resuming and uncertain requests

The local receipt is `build/tutorial-narration-aipass/journal.json`; generated originals are cached beside it. A process lock prevents two instances from generating this batch concurrently. Receipts record provider, model, voice, text hash, source and final audio hashes, attempt number, and status. Matching completed MP3s are validated and skipped; a matching cached success can restore a missing installed asset without synthesis.

Before every potentially charged POST, the script durably writes `requesting`. A timeout, HTTP error, interrupt, invalid audio response, or ambiguous failure leaves `requesting` or `uncertain`, with no automatic resend. After checking the provider's history and usage, an explicit retry must name one clip:

```sh
python3 scripts/generate-tutorial-narration.py --generate \
  --clip basic_citizen --retry-uncertain
```

This may incur another charge. `--retry-uncertain` cannot target the whole batch. A different text, voice, provider receipt, or unrecognized existing artifact additionally requires `--replace-existing`; that flag alone does not bypass an uncertain prior request. Existing installed successes remain until a replacement has actually succeeded and passed validation.

Only actual MP3 audio at 44.1 kHz, greater than zero and at most 90 seconds, and no larger than 5 MiB is installed. `ffprobe` validates both the provider response and final file before an atomic write to `webApp/src/webMain/resources/audio/tutorial/<clip_id>.mp3`. These limits match browser playback and packaging. Provider errors expose only an HTTP status or a fixed failure label; no response body, headers, credentials, or account payload is logged.
