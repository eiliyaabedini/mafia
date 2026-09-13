# مافیای هوش مصنوعی

A playable, fully Persian Mafia prototype: one human, six distinct AI characters, and real AI Pass wallet-funded responses. Kotlin Multiplatform owns both the deterministic game engine and Compose UI; Kotlin/Wasm is the first playable platform.

## Production website and redeployment

The production website is [mafiai.vercel.app](https://mafiai.vercel.app/), deployed to the `ai-mafia` project in Vercel scope `eiliya-s-projects`. The older `ai-mafia-pied.vercel.app` address redirects to it. Vercel serves the static app independently of this computer and any ngrok tunnel.

**AI Pass production callbacks are approved and provisioned.** The updated public client configuration has been deployed to the stable website. An earlier release completed a full wallet-funded game on localhost through a Day 3 result. The current public bundle matches the prepared release byte-for-byte; its pacing changes were compiled and reviewed without starting another paid game. See [verification status](docs/VERIFICATION.md).

From the repository root, with the Vercel CLI authenticated and `.vercel/project.json` linked to that existing project:

```sh
python3 scripts/prepare-vercel.py
vercel deploy --prebuilt --prod --yes --scope eiliya-s-projects
```

Run deployment only after packaging succeeds. The preparation script builds `:webApp:wasmJsBrowserDistribution`, then writes Vercel Build Output API v3 files to `.vercel/output`. If the production distribution is already current, use `python3 scripts/prepare-vercel.py --skip-build` before the same deploy command. The script preserves `.vercel/project.json`; on a fresh checkout, link the existing project with `vercel link --yes --project ai-mafia --scope eiliya-s-projects` first.

Only validated files from `webApp/build/dist/wasmJs/productionExecutable` enter `.vercel/output/static`. Packaging rejects unknown or private files, symbolic links, extra public-config fields, and recognized embedded credential patterns; source maps are omitted. `.vercelignore` permits the prepared output only. Repository source, `.aipass`, `local.properties`, build caches, and credentials stay outside the deployment.

The entry page, `aipass-config.json`, and `sw.js` use `Cache-Control: no-store`. Hashed Wasm files use `application/wasm` and immutable caching; unversioned assets must revalidate. After a redeploy, inspect the stable URL and these headers in a fresh browser tab. Existing game tabs retain their loaded code and in-memory game; leave them open until their players choose to finish. Games created with the current version save each committed turn locally. Reloading offers a paused resume; older tabs loaded before this update still hold their game only in memory.

Vercel Web Analytics is loaded from the platform-owned `/_vercel/insights/script.js` route. It records the automatic anonymous visitor and page-view metrics available in the Vercel dashboard. The app does not send custom events, player names, game state, conversations, roles, wallet values, or AI responses to Analytics.

This uses Vercel's documented [prebuilt deployment command](https://vercel.com/docs/cli/deploy) and [Build Output API](https://vercel.com/docs/build-output-api/configuration).

## AI Pass setup and stable-origin migration

The official AI Pass SDK is loaded only from a player action. Every player connects their own wallet; no developer/provider key is bundled. Each AI character has a fixed thinking model. Starting a game validates those exact model IDs against the current text/chat catalog; players cannot change the assignments.

The provisioned client approves `http://localhost:8080/` plus all three stable production aliases: `https://ai-mafia-pied.vercel.app/`, `https://mafiai.vercel.app/`, and `https://ai-mafia-eiliya-s-projects.vercel.app/`. Mobile redirect login must return to the exact origin the player opened. Per-deployment preview URLs and temporary ngrok origins remain separate OAuth origins and need their own approved callbacks.

Normal redeployments reuse this approved setup. For an intentional hosting-origin change, use `migrate` with the actual new stable origin; the Vercel setup command is:

```sh
python3 scripts/setup_aipass.py migrate \
  --origin https://ai-mafia-pied.vercel.app \
  --origin https://mafiai.vercel.app \
  --origin https://ai-mafia-eiliya-s-projects.vercel.app
# The owner reviews the browser approval page. The agent does not approve it.
python3 scripts/setup_aipass.py provision
```

If a migration approval request already exists, continue with `provision` after owner approval. Do not replace a pending request because a new coding session begins. A new project without previous setup uses `request` with the same complete origin list instead of `migrate`.

Provisioning writes the public runtime `webApp/src/webMain/resources/aipass-config.json` and updates an existing Wasm distribution. When only this configuration changed, package and redeploy it:

```sh
python3 scripts/prepare-vercel.py --skip-build
vercel deploy --prebuilt --prod --yes --scope eiliya-s-projects
```

Then verify the approved callbacks in `.aipass/config.json`, the public client ID served by the stable website, and the SDK wallet connection in a fresh production tab. Setup approval is separate from permission to make paid AI calls. Browser wallet sessions remain under SDK custody and are scoped to their origin; players connect on the stable website themselves.

`.aipass/config.json` contains public project metadata. `.aipass/project-grant.json` is an owner-readable, ignored setup recovery record; both the directory and all credentials are excluded from Vercel packaging. The bundle contains only the public `clientId` needed by the SDK.

## Optional local development and ngrok

```sh
./gradlew :webApp:wasmJsBrowserDistribution
scripts/start-web.sh --skip-build --background
# Optional HTTPS tunnel for development:
ngrok http http://127.0.0.1:8080
```

Open `http://localhost:8080/` locally. The local server serves only the production distribution on loopback; its PID/log live in `webApp/build/server-8080.{pid,log}`. A development tunnel depends on this server and the ngrok process remaining active. Wallet login through a tunnel requires its actual origin to be registered with the relevant AI Pass client; the production callback setup does not automatically authorize a new tunnel address.

Append `?debug=1` while developing gameplay to skip the deliberate turn-reading and final-ballot holds. It does not disable TTS or model requests; turn off `صدای هم‌بازی‌ها` before a non-speech gameplay test.

Browser support requires modern WasmGC. The JS target remains available for local use via `:webApp:jsBrowserDistribution` and server `--target js`. The Vercel preparation script packages the Wasm target.

## Playing

- Enter your name once, privately view your role, then join the table. Your name is saved locally and appears in every agent’s roster; agents can question, suspect, vote for, and legally target you.
- The lobby presents the six characters immediately after the introduction. Each card shows its assigned thinking model as a read-only label. Sound settings open from the small header control.
- A character uses its assigned model for speech, voting and night decisions. Models follow character identity through shuffled seats and roles. The assignments live in the AI layer's `CharacterModels.kt`; there are no player-facing model pickers or overrides. If an assigned model is unavailable, starting fails visibly without substituting another model.
- There are two Mafia, one Detective, one Doctor, and three Citizens.
- Each day shuffles the living players into a new speaking order, shared by that day's two discussion rounds. The human can occupy any position; the visible roster follows the order. Nomination voting follows discussion: each living person's portrait appears, and every other player answers yes/no in the day's order. A player can nominate several others or nobody. All votes become public in that order.
- At most two positive nomination leaders enter defense. If a tie at the cutoff would produce three or more finalists, the day ends without defense or elimination. With no nominations, the day also ends without elimination. Each valid finalist gets one defense turn. Every living player then chooses one eligible finalist or abstains; a unique highest non-abstaining total is eliminated. A final tie or everyone abstaining eliminates nobody. Self-votes are never legal.
- Each AI submits its nomination set in one request, held privately until the individual votes are revealed. The final ballot is a fresh AI decision after the defenses. Public history distinguishes first-round yes/no votes, defenses, final votes, abstentions and departures; agents can compare changes without receiving unrevealed ballot plans. Saved pre-update single ballots finish with their old rules; the next discussion uses the new flow.
- At night Mafia propose a non-Mafia victim, Doctor protects anyone including themselves, and Detective investigates someone else. Actions resolve simultaneously. A tied Mafia decision uses seeded random resolution. A saved-night event means the Doctor protected the chosen victim; neither private identity is announced. Missing Mafia actions cannot be treated as a save.
- Eliminated players cannot act. Roles remain hidden until the result. An eliminated human may watch or leave.
- Town wins when no Mafia remain. Mafia wins at parity.
- Click your private-role control to read investigations and locally saved notes. They are never included in other agents' context.
- A failed response leaves the current turn untouched. Retry is explicit and may cost another request. Pause retains the response already in flight and stops before the next one.
- Sound settings in the lobby header and at the table control Persian dialogue through AI Pass TTS. Speech defaults on; its menu switch remembers an explicit on/off choice locally. The dialog explains the additional wallet cost. Zero volume skips new speech requests.
- Each public AI line appears before it is spoken. The next character waits for playback to finish. Pause, mute, and leaving stop playback; a speech failure pauses further narration without changing the saved preference, while the text game continues.
- With speech muted, each AI line remains alone long enough to read before the next character answers; an eliminated human gets the longer spectator pace. With speech enabled, playback itself supplies that reading time and a short pause follows it.
- The conversation follows new lines only while the reader is already at the bottom. Scrolling into older messages anchors that position as new AI lines arrive; `پیام‌های تازه` returns to the live edge.
- At the end of a nomination or final ballot, the last vote stays on the existing voting screen for 2.8 seconds before the result advances. There is no separate last-vote overlay.
- Background music has its own switch and volume in `صدا و موسیقی`. It defaults on and starts after the first real interaction, respecting browser playback rules. An explicit on/off choice is remembered locally. Six bundled tracks use a weighted random rotation without immediate repeats. Three instrumentals may open a session; the lower-priority versions only appear later, including one vocal Easter egg with a very small selection weight. The one-minute version fades out over its final six seconds. Music has no wallet charge, softens during spoken dialogue, continues beneath the tutorial, and suspends when the game is paused or the tab is hidden. [Soundtrack sources and playback rules](docs/MUSIC.md).
- Ten short public event effects include a real rooster at daybreak, owl hoots at nightfall, voting cues, elimination announcements, and victory cues. Effects have a third default-on switch and volume, independent of music and speech; their on/off preference is saved locally. They never signal private night choices and never use wallet credit. [Sources, cues, and reproduction](docs/SOUND_EFFECTS.md).

## Learn without spending credit

New visitors see a Persian “Do you know Mafia?” welcome with a beginner guide or a skip option. The header's `آموزش` button reopens the guide from the lobby. Welcome dismissal is saved locally. Tutorial answers reset on replay; live-game saves are separate from the scripted guide.

The illustrated tutorial contains a short welcome/history card, five rule cards, five guided practice decisions, and three optional strategy scenarios. Practice uses predefined dialogue, choices, and specific feedback; it does not invoke the game engine, request model/TTS output, or require a wallet. Its seven-player sample covers a misquoted statement, asking for clarification, nomination versus final voting, a tied final vote, a protected night, and team victory. Advanced scenes explain evidence versus certainty, a detective's private information, and Mafia teammate reactions. Replay clears the selected track's answers.

Forty-one prerecorded Persian lessons generated through AI Pass are bundled with the app and play automatically when each card opens. The warm narrator uses Gemini 3.1 Flash TTS; game examples switch to distinct female and male voices for the quoted characters. Replay and stop controls remain available, and tutorial playback never spends the player's wallet credit. Partial clip sets cannot deploy. [Scripts and generation instructions](docs/TUTORIAL_NARRATION.md).

The entire guide is bundled in `TutorialContent.kt`, localized resource files, and four generated illustrations. [Tutorial artwork and exact prompts](docs/TUTORIAL_ARTWORK.md) record the built-in image generation process and saved assets. The final “ready for a real game” action explicitly leaves practice and starts normal role assignment; real AI responses still use the player's wallet.

## Wallet credit

The compact header uses an AI Pass branded chip. A disconnected session shows `AI CONNECT`; a connected session shows `AI PASS` and the remaining balance in the SDK's `$0.00` style. Clicking it opens the official SDK account surface on desktop and mobile. On mobile, choosing sign-in inside that surface uses the SDK's full-page OAuth route. The public SDK and client configuration warm while the Wasm app loads, and wallet opening is deferred past the canvas tap event so the browser's synthesized click cannot immediately dismiss the new modal. Connected users receive the SDK account dialog with authoritative balance, funding, gift-card, dashboard, and disconnect actions. The application does not duplicate that account UI.

The first welcome/tutorial path does not initialize AI Pass. The lobby checks an existing SDK session without opening a sign-in dialog; players connect through the ordinary real-game flow. SDK balance/login/logout events and independent reads after model or speech requests update the displayed credit. Balance-read failure never changes or blocks a game turn. No runtime token or account credential crosses into shared game state, and balance state is kept in memory only.

The real game has no simulated AI or custom backend. Local storage automatically keeps the latest game, private notes, player name, and all three sound switches and volumes. Reloading offers a paused resume from the last committed action; it never automatically makes AI requests. Before mobile OAuth, a short-lived game ID marker is copied to both session and local browser storage. On return, the app consumes both copies, verifies that ID against the local save, and constructs the table in its paused state before the first UI frame. An already committed human ballot is preserved. The tutorial is a separately labeled scripted learning experience. The PWA caches static assets only; offline navigation explains that a connection is required. Wallet sessions are managed independently by the official SDK. Vercel Analytics records anonymous page-view and visitor totals as described above.

## Local save and AI Pass backup

Local storage is the primary store (`mafia.save.v1`). A compact `ذخیره و پشتیبان` link beside the player name can explicitly back up the current snapshot or restore the wallet account’s previous backup from the lobby. Nothing uploads automatically. The official `AiPass.data` API keeps one private app document; backup preserves unrelated document fields and checks the fetched revision to avoid overwriting another device’s newer write. Restore validates the game before replacing local state. A full/blocked local store shows a warning.

A save includes hidden roles and per-agent private state so the game can resume, but those fields still pass through the engine’s filtered context for each AI. Credentials and generated audio are excluded. Local state is per browser; AI Pass backup is per account. The latest game is retained, not an unlimited history. Starting a new game replaces the local game slot.

Ballot and night choices now have an action-only final prompt, separate from speaking instructions. Local parsing accepts unambiguous exact target IDs, supported JSON key variations, and exact unique legal names. It also accepts one final fenced JSON block following prose, with no competing JSON or extra code blocks; only validated fields enter the game or TTS. Conflicting, dead, self, or otherwise illegal targets fail without advancing the turn or triggering automatic paid retries. The bounded local repair handles occasional explanatory text around one correct fenced JSON action without exposing the prose or automatically paying for another call.

## Code boundaries

- `shared/src/commonMain/.../domain`: roles, characters, private/public projections, and pure game transitions. Models cannot control rules.
- `shared/src/commonMain/.../ai`: provider-neutral service, prompts, response validation, and a string-only gateway.
- `shared/src/commonMain/.../ui` and `App.kt`: orchestration, local save checkpoints, and Persian Compose UI.
- `webApp`: minimal SDK bridge, Web entry point, PWA shell, manifest and service worker.

Android and iOS entry points remain compatible with the shared UI; native AI authentication is deferred. No new automated test suite is included, as requested. Verification consists of compilation and manual browser/play checks; a successful build alone does not verify wallet-funded gameplay.

Agent prompts use an attributed transcript: each public speech includes its speaker ID, name and whether it belongs to the current character. The human keeps stable ID `player_0` and a saved roster name. The prompt explicitly identifies the human as a separate, fully participating player; the interface may still use `شما` to address them. Each turn explicitly lists that character's own earlier speech IDs and recent literal name mentions. Mentions are not automatically classified as accusations. Public context also includes the speaking order and whether each player has had a turn today; waiting before a first turn must not be framed as deliberate silence. Speech responses must identify the expected speaker before text is accepted or voiced. These checks improve attribution but cannot prove that an LLM's prose always preserves perspective. Manual semantic review cases are in [docs/AI_IDENTITY_SCENARIOS.md](docs/AI_IDENTITY_SCENARIOS.md).

Thinking-model presets live in the AI layer, outside the deterministic rules and private-context projection. The controller snapshots the validated assignments at game creation and resolves the acting character's model on every AI turn. Expensive Haiku and Gemini 3.6 assignments are excluded from the default table. Each returned usage entry retains the requested model ID and token counts. Exact prices currently remain unknown when absent from the response; AI Pass exposes authoritative charges through a separate settlement API, which is not yet connected to these usage entries. Unknown cost must not be displayed as zero. A failed provider request remains on that character's turn for explicit retry; there is no automatic substitution of another model. GPT and the fixed DeepSeek V4 Flash model request low reasoning effort; DeepSeek otherwise defaults to high effort. Speech output is capped at 800 tokens including hidden reasoning; private decisions are capped at 320. Generation has a 90-second deadline, separate from the wallet-login allowance and 45-second TTS deadline. TTS voice/model selection is independent.

Agent context is compacted locally without an AI summary. Every current-day speech stays visible. Older dialogue retains recent lines per player, the character's own recent lines, and direct mentions of that character. Complete nomination approvals, final ballots, eliminations and night outcomes are encoded as compact engine facts for every day, so vote changes remain usable evidence without replaying dozens of repetitive yes/no event objects.

| Character | Fixed thinking model |
| --- | --- |
| Arman | GPT-5.6 Luna |
| Sara | GPT-5.6 Luna |
| Shahab | DeepSeek V4 Flash 0731 |
| Nika | Gemini 3.5 Flash Lite |
| Dariush | DeepSeek V4 Flash 0731 |
| Mina | GPT-5.6 Luna |

## Character voices

The browser calls the official SDK's `generateSpeech` with `gpt-4o-mini-tts`, Persian `text`, MP3 output, `1.25×` speech speed, and one stable voice per character: Arman → onyx, Sara → nova, Shahab → echo, Nika → shimmer, Dariush → fable, Mina → coral. Only the accepted public dialogue is sent; roles, agent beliefs, and private night actions never enter the TTS request. Shared code uses a platform-neutral narration interface; WebAudio handles browser playback and volume.

No speech request is retried automatically. Stopping an in-flight generation cannot guarantee that AI Pass has not already charged it. Speech charges are visible in the AI Pass wallet history; the current SDK returns an audio Blob without per-request cost metadata, so the in-memory chat token totals exclude TTS. Audio buffers are held only for the current line and are not persisted or service-worker cached.

Eight generated images and their prompts are documented in [docs/ARTWORK.md](docs/ARTWORK.md). Vazirmatn is bundled under the SIL Open Font License. External AI Pass wallet/authorization screens are controlled by AI Pass; application-owned UI and game speech are Persian.
