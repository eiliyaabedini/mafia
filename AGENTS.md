# AI Mafia agent notes

## Paid speech during development

- Keep character TTS enabled by default for real users. Respect an existing user choice stored by the app.
- Before any manual or automated gameplay test, turn **صدای هم‌بازی‌ها** off in the audio menu. On web, an agent may instead set `localStorage['mafia.audio.enabled.v1'] = '0'` before starting the test.
- Keep TTS off for the entire test run because synthesis adds wallet cost and delays every AI turn. Do not change the product default merely to make tests cheaper.
- Run a paid TTS check only when the user explicitly asks to test speech itself. Use the shortest useful utterance and report that a paid call was made.
- Bundled music and event effects are local and free; they do not need to be disabled for cost reasons.
- Add `?debug=1` to the web URL during gameplay development to skip artificial reading and final-ballot holds. This does not disable TTS or paid model requests, so the TTS rule above still applies.
