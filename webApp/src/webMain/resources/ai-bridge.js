/* Browser-only adapter. Credentials remain inside the official AI Pass SDK. */
(() => {
  let sdkPromise, setupPromise, sequence = 0, activeCompletion = null;
  const pending = new Map();
  const PUBLIC_CONFIG = './aipass-config.json';
  const NETWORK_TIMEOUT = 20_000;
  const AUTH_TIMEOUT = 300_000;
  // Providers occasionally exceed 45s. Prefer low reasoning effort for the
  // table's reasoning models, with a bounded allowance for a slow response.
  const COMPLETION_TIMEOUT = 90_000;
  const SPEECH_TIMEOUT = 45_000;
  const NARRATION_TIMEOUT = 120_000;
  const VOICES = Object.freeze({ arman: 'onyx', sara: 'nova', reza: 'echo', nika: 'shimmer', ali: 'fable', mina: 'coral' });
  const AUDIO_PREFERENCE_KEY = 'mafia.audio.enabled.v1';
  // Keep gameplay narration on the documented low-cost model. If it is absent,
  // fail visibly instead of silently switching the player's wallet to another model.
  const SPEECH_MODEL_PREFERENCES = Object.freeze(['gpt-4o-mini-tts']);
  const SPEECH_SPEED = 1.25;
  const AUTH_RESUME_KEY = 'mafia.auth.resume.v1';
  const TUTORIAL_AUDIO_READY = false;
  const TUTORIAL_CLIPS = new Set([
    ...['welcome', 'citizen', 'roles', 'cycle', 'night', 'outcomes'].map(id => `basic_${id}`),
    ...['listen', 'question', 'vote', 'night', 'finish'].flatMap(id =>
      ['intro', 'feedback_0', 'feedback_1', 'feedback_2'].map(part => `practice_${id}_${part}`)),
    ...['evidence', 'detective', 'teammate'].flatMap(id =>
      ['intro', 'feedback_0', 'feedback_1', 'feedback_2'].map(part => `strategy_${id}_${part}`)),
    ...['basics', 'practice', 'strategy'].map(id => `complete_${id}`),
  ]);
  // New players hear character voices by default; an explicit stored mute wins.
  let audioEnabled = readPreference(AUDIO_PREFERENCE_KEY, true), audioVolume = 0.8, audioEpoch = 0;
  let audioContext = null, audioGain = null, audioUnlock = null;
  let activeNarration = null, speechModel = null, speechModelPromise = null;
  let activeTutorial = null, tutorialClipId = null, tutorialEpoch = 0, mediaError = null;
  // Bundled music has no SDK dependency and uses its own context and volume.
  const MUSIC_TRACKS = Object.freeze([
    { id: 'nocturne', url: '/audio/mafia-nocturne.mp3', canStart: true, weight: 100 },
    { id: 'dons-main', url: '/audio/mafia-dons-gambit-main.mp3', canStart: true, weight: 100 },
    { id: 'dons-short', url: '/audio/mafia-dons-gambit-short.mp3', canStart: true, weight: 100 },
    { id: 'dons-late', url: '/audio/mafia-dons-gambit-late.mp3', canStart: false, weight: 24 },
    { id: 'dons-vocal-alt', url: '/audio/mafia-dons-gambit-vocal-alt.mp3', canStart: false, weight: 12 },
    { id: 'dons-vocal-rare', url: '/audio/mafia-dons-gambit-vocal-rare.mp3', canStart: false, weight: 2 },
  ]);
  const MUSIC_MAX_BYTES = 8 * 1024 * 1024;
  const MUSIC_PREFERENCE_KEY = 'mafia.music.enabled.v1';
  let musicEnabled = readPreference(MUSIC_PREFERENCE_KEY), musicVolume = 0.25, musicActive = false, musicError = null;
  let musicContext = null, musicGain = null, musicBuffer = null, musicSource = null, musicTrack = null;
  let musicEpoch = 0, musicJob = null, musicOffset = 0, musicStartedAt = 0;
  let musicPageHidden = false, musicGestureAt = -Infinity, musicGestureSeen = false, speechPlaybackEntry = null;
  const EFFECTS_PREFERENCE_KEY = 'mafia.effects.enabled.v1';
  const EFFECT_CUES = new Set(['game_start', 'daybreak', 'nightfall', 'vote_open', 'vote_tied',
    'eliminated', 'night_killed', 'night_saved', 'town_win', 'mafia_win']);
  let effectsEnabled = readPreference(EFFECTS_PREFERENCE_KEY), effectsVolume = 0.55, effectsActive = false;
  let effectsContext = null, effectsGain = null, effectsSource = null, effectsRelease = null;
  let effectsEpoch = 0, effectsJob = null, effectsGestureSeen = false, effectsCacheBytes = 0;
  const effectsQueue = [], effectsCache = new Map();
  let walletRevision = 0, walletEpoch = 0, walletBalanceVersion = 0, activeGameId = null;
  let walletRequest = null, walletController = null;
  let walletSnapshot = {
    connection: 'UNKNOWN', balance: null, stale: false, error: null,
    updatedAtEpochMs: null, revision: 0,
  };

  function walletConnected(api = window.AiPass) {
    try { return Boolean(api?.initialized && api.isAuthenticated()); }
    catch (_) { return false; }
  }

  function mobileAuthBrowser() {
    const userAgent = navigator.userAgent || '';
    const touchIpad = navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1;
    const compactTouch = typeof window.matchMedia === 'function'
      && window.matchMedia('(pointer: coarse)').matches
      && Math.min(window.screen?.width || innerWidth, window.screen?.height || innerHeight) <= 1024;
    return /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i.test(userAgent)
      || touchIpad || compactTouch;
  }

  function validGameId(value) {
    return typeof value === 'string' && /^game--?\d+$/.test(value) && value.length <= 32;
  }

  function setActiveGameId(value) {
    activeGameId = validGameId(value) ? value : null;
    if (!activeGameId) clearAuthResume();
  }

  function authResumeStores() {
    const stores = [];
    try { stores.push(window.sessionStorage); } catch (_) {}
    // Mobile OAuth may return in a fresh browsing context. The ordinary local
    // save already lives here; this short-lived marker contains only its game ID.
    try { stores.push(window.localStorage); } catch (_) {}
    return stores;
  }

  function clearAuthResume() {
    for (const store of authResumeStores()) {
      try { store.removeItem(AUTH_RESUME_KEY); } catch (_) {}
    }
  }

  function rememberAuthResume() {
    if (!activeGameId) return;
    const value = JSON.stringify({ gameId: activeGameId, createdAt: Date.now() });
    for (const store of authResumeStores()) {
      try { store.setItem(AUTH_RESUME_KEY, value); } catch (_) {}
    }
  }

  function consumeAuthResumeGameId() {
    try {
      const url = new URL(window.location.href);
      if (!url.searchParams.has('code') || !url.searchParams.has('state')) return '';
      let raw = null;
      for (const store of authResumeStores()) {
        try { raw ||= store.getItem(AUTH_RESUME_KEY); } catch (_) {}
      }
      clearAuthResume();
      if (!raw) return '';
      const value = JSON.parse(raw);
      return validGameId(value?.gameId) && Number.isFinite(value?.createdAt)
        && Date.now() - value.createdAt >= 0 && Date.now() - value.createdAt <= 15 * 60_000
        ? value.gameId : '';
    } catch (_) { return ''; }
  }

  function oauthCallbackInUrl() {
    try {
      const url = new URL(window.location.href);
      return url.searchParams.has('code') && url.searchParams.has('state');
    } catch (_) { return false; }
  }

  function publishWallet(status) {
    walletSnapshot = { ...status, revision: ++walletRevision };
    return walletSnapshot;
  }

  function resetWallet(connection) {
    walletEpoch += 1;
    walletController?.abort();
    walletRequest = walletController = null;
    return publishWallet({ connection, balance: null, stale: false, error: null, updatedAtEpochMs: null });
  }

  function walletSessionSnapshot() {
    // A tutorial/initial cached read never loads or initializes the SDK.
    const api = window.AiPass;
    if (!api?.initialized) return walletSnapshot;
    if (!walletConnected(api)) {
      return walletSnapshot.connection === 'SIGNED_OUT' ? walletSnapshot : resetWallet('SIGNED_OUT');
    }
    if (walletSnapshot.connection !== 'CONNECTED') return resetWallet('CONNECTED');
    return walletSnapshot;
  }

  function decimalUsd(value) {
    if (typeof value !== 'number' && typeof value !== 'string') return null;
    if (typeof value === 'number' && !Number.isFinite(value)) return null;
    const raw = String(value).trim();
    // Preserve the SDK's decimal digits, including balances below one cent.
    // Scientific notation is expanded with strings, never rounded with toFixed.
    if (raw.length > 96) return null;
    const match = /^([+-]?)(\d+)(?:\.(\d+))?(?:e([+-]?\d+))?$/i.exec(raw);
    if (!match) return null;
    const exponent = Number(match[4] || 0);
    if (!Number.isInteger(exponent) || Math.abs(exponent) > 60) return null;
    const digits = match[2] + (match[3] || '');
    const decimal = match[2].length + exponent;
    let expanded = decimal <= 0 ? '0.' + '0'.repeat(-decimal) + digits
      : decimal >= digits.length ? digits + '0'.repeat(decimal - digits.length)
      : digits.slice(0, decimal) + '.' + digits.slice(decimal);
    expanded = expanded.replace(/^0+(?=\d)/, '').replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '');
    return (match[1] === '-' && /[1-9]/.test(expanded) ? '-' : '') + expanded;
  }

  function publishWalletBalance(value) {
    const amount = decimalUsd(value);
    if (amount === null) return false;
    walletBalanceVersion += 1;
    publishWallet({ connection: 'CONNECTED', balance: { remainingUsd: amount }, stale: false,
      error: null, updatedAtEpochMs: Date.now() });
    return true;
  }

  function walletUnavailable() {
    return publishWallet({ ...walletSnapshot, stale: walletSnapshot.balance !== null, error: 'WALLET_UNAVAILABLE' });
  }

  function refreshWalletSnapshot(api = window.AiPass) {
    walletSessionSnapshot();
    if (!walletConnected(api)) return Promise.resolve(walletSnapshot);
    if (walletRequest) return walletRequest;
    const epoch = walletEpoch;
    const balanceVersion = walletBalanceVersion;
    const controller = new AbortController();
    walletController = controller;
    let timer;
    const deadline = new Promise((_, reject) => {
      timer = setTimeout(() => {
        controller.abort();
        reject(new Error('WALLET_UNAVAILABLE'));
      }, 12_000);
    });
    walletRequest = (async () => {
      // Yield once so even a synchronous SDK failure clears the assigned job.
      await Promise.resolve();
      try {
        // This documented GET only uses an existing SDK session. It does not
        // call login/openWallet, inspect tokens, or spend from any wallet.
        const summary = await Promise.race([api.getUserBalance({ signal: controller.signal }), deadline]);
        if (epoch !== walletEpoch) return walletSnapshot;
        if (!walletConnected(api)) return resetWallet('SIGNED_OUT');
        // A newer SDK balance event wins over this earlier in-flight read.
        if (balanceVersion === walletBalanceVersion && !publishWalletBalance(summary?.data?.remainingBudget)) walletUnavailable();
      } catch (_) {
        if (epoch === walletEpoch) {
          if (!walletConnected(api)) resetWallet('SIGNED_OUT');
          else if (balanceVersion === walletBalanceVersion) walletUnavailable();
        }
      } finally {
        clearTimeout(timer);
        if (walletController === controller) walletRequest = walletController = null;
      }
      return walletSnapshot;
    })();
    return walletRequest;
  }

  function refreshWalletAfterPaid(previousBalanceVersion) {
    // The SDK normally emits its own freshly fetched balance after each call.
    // Fill that gap only when it did not; never hold up or fail a game turn.
    if (walletBalanceVersion === previousBalanceVersion || walletSnapshot.stale || !walletSnapshot.balance) {
      if (walletSnapshot.balance) publishWallet({ ...walletSnapshot, stale: true });
      void refreshWalletSnapshot();
    }
  }

  async function readWalletStatus(entry, payload) {
    if (payload?.refresh !== true) return walletSessionSnapshot();
    try {
      const api = await sdk();
      assertCurrent(entry);
      return await refreshWalletSnapshot(api);
    } catch (_) {
      assertCurrent(entry);
      return walletUnavailable();
    }
  }

  async function openWallet(entry) {
    let api;
    try {
      api = await sdk();
    } catch (firstError) {
      // A quick tap can race a cold SDK/config load on slower mobile browsers.
      // Retrying this non-billable setup once gives it the same warm path that
      // users were reaching by holding the button and trying again.
      if (!mobileAuthBrowser()) throw firstError;
      await new Promise(resolve => setTimeout(resolve, 250));
      assertCurrent(entry);
      api = await sdk();
    }
    assertCurrent(entry);
    // Show the same official connection dialog on desktop and mobile. The SDK
    // switches the dialog's actual sign-in step to a full-page redirect where
    // mobile browsers require it.
    if (!walletConnected(api) && mobileAuthBrowser()) rememberAuthResume();
    try {
      await api.openWallet();
    } catch (firstError) {
      const dismissed = /AUTH_REQUIRED|dismiss|cancel/i.test(String(firstError?.code || firstError?.message || firstError));
      if (!mobileAuthBrowser() || dismissed) throw firstError;
      await new Promise(resolve => setTimeout(resolve, 250));
      assertCurrent(entry);
      await api.openWallet();
    }
    assertCurrent(entry);
    return await refreshWalletSnapshot(api);
  }

  async function sdk() {
    if (!sdkPromise) sdkPromise = new Promise((resolve, reject) => {
      if (window.AiPass) return resolve(window.AiPass);
      const script = document.createElement('script');
      const timer = setTimeout(() => finish(new Error('SDK_UNAVAILABLE')), NETWORK_TIMEOUT);
      function finish(error) {
        clearTimeout(timer);
        script.onload = script.onerror = null;
        if (error) { script.remove(); reject(error); }
        else resolve(window.AiPass);
      }
      script.src = 'https://aipass.one/aipass-sdk.js';
      script.async = true;
      script.onload = () => finish(window.AiPass ? null : new Error('SDK_UNAVAILABLE'));
      script.onerror = () => finish(new Error('SDK_UNAVAILABLE'));
      document.head.appendChild(script);
    }).catch(e => { sdkPromise = null; throw e; });
    const api = await sdkPromise;
    if (!setupPromise) setupPromise = (async () => {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), NETWORK_TIMEOUT);
      try {
        const res = await fetch(PUBLIC_CONFIG, { cache: 'no-store', signal: controller.signal });
        if (!res.ok) throw new Error('SETUP_REQUIRED');
        const config = await res.json();
        if (typeof config.clientId !== 'string' || !config.clientId.trim()) throw new Error('SETUP_REQUIRED');
        api.initialize({ clientId: config.clientId.trim(), scopes: ['api:access'], darkMode: true });
      } finally { clearTimeout(timer); }
    })().catch(e => { setupPromise = null; throw e; });
    await setupPromise;
    return api;
  }

  function errorCode(e) {
    // Return only fixed UI codes; SDK errors can contain request or account data.
    const message = String(e?.code || e?.message || e);
    if (/STORAGE_INVALID/.test(message)) return 'STORAGE_INVALID';
    if (/revision.conflict|changed elsewhere/i.test(message)) return 'STORAGE_CONFLICT';
    if (/too large|413/i.test(message)) return 'STORAGE_FULL';
    if (/SETUP_REQUIRED/.test(message)) return 'SETUP_REQUIRED';
    if (/balance|budget|credit|402|fund/i.test(message)) return 'BALANCE';
    if (/TIMEOUT|timed.out|timeout/i.test(message)) return 'TIMEOUT';
    if (/INVALID_RESPONSE|EMPTY_RESPONSE/i.test(message)) return 'INVALID_RESPONSE';
    if (/auth|login|cancel|dismiss|401/i.test(message)) return 'AUTH';
    if (/429|rate.limit/i.test(message)) return 'RATE_LIMIT';
    if (/SDK_UNAVAILABLE|fetch|network|offline|AbortError/i.test(message)) return 'NETWORK';
    return 'REQUEST_FAILED';
  }

  function current(entry) {
    return pending.get(entry.id) === entry && entry.result === null && !entry.controller.signal.aborted;
  }
  function assertCurrent(entry) {
    if (!current(entry)) throw new Error('CANCELLED');
  }
  function clearEntry(entry) {
    clearTimeout(entry.timer);
    if (entry.onLogin) document.removeEventListener('aipass:login', entry.onLogin);
    entry.onLogin = null;
    if (activeCompletion === entry) activeCompletion = null;
    if (activeNarration === entry) activeNarration = null;
    if (activeTutorial === entry) activeTutorial = null;
    if (entry.onAudioGesture) {
      entry.onAudioGesture();
      entry.onAudioGesture = null;
    }
    if (entry.releaseAudio) {
      const release = entry.releaseAudio;
      entry.releaseAudio = null;
      release();
    }
  }
  function finish(entry, response) {
    if (!current(entry)) return;
    entry.result = JSON.stringify(response);
    clearEntry(entry);
  }
  function armTimeout(entry, milliseconds) {
    clearTimeout(entry.timer);
    entry.timer = setTimeout(() => {
      if (!current(entry)) return;
      if (entry.op === 'narrate' || entry.op === 'tutorialPlay') audioError(entry, new Error('AUDIO_FAILED'));
      else finish(entry, { error: 'TIMEOUT' });
      // A signal aborted during login also prevents the SDK from sending the
      // eventual model request if that old login completes after timeout/leave.
      entry.controller.abort();
    }, milliseconds);
  }
  function tokenCount(value) {
    const number = Number(value);
    return Number.isSafeInteger(number) && number >= 0 ? number : 0;
  }

  function readPreference(key, defaultValue = true) {
    // Only this harmless boolean is stored; SDK/account storage stays private.
    try {
      const stored = window.localStorage.getItem(key);
      return stored === null ? defaultValue : stored !== '0';
    } catch (_) { return defaultValue; }
  }

  function saveMusicPreference() {
    try { window.localStorage.setItem(MUSIC_PREFERENCE_KEY, musicEnabled ? '1' : '0'); }
    catch (_) { /* The current in-memory preference still works. */ }
  }

  function mediaStatus() {
    const playing = Boolean(speechPlaybackEntry && audioContext?.state === 'running');
    return { enabled: audioEnabled, playing, tutorialAvailable: TUTORIAL_AUDIO_READY,
      pending: Boolean((activeTutorial || activeNarration) && !playing),
      clipId: tutorialClipId, error: mediaError };
  }

  function resolveSpeechModel(api, signal) {
    if (speechModel) return Promise.resolve(speechModel);
    if (!speechModelPromise) {
      speechModelPromise = api.getModelCatalog({ type: 'audio', method: 'audio_speech', signal })
        .then(catalog => {
          const available = new Set((catalog.data || []).map(model => model?.id).filter(Boolean));
          const selected = SPEECH_MODEL_PREFERENCES.find(model => available.has(model));
          if (!selected) throw new Error('AUDIO_FAILED');
          speechModel = selected;
          return selected;
        })
        .finally(() => { speechModelPromise = null; });
    }
    return speechModelPromise;
  }

  function refreshAudioPreference() {
    try {
      const stored = window.localStorage.getItem(AUDIO_PREFERENCE_KEY);
      if (stored === '0' || stored === '1') audioEnabled = stored === '1';
    } catch (_) { /* Keep the last explicit in-memory choice. */ }
  }

  function musicCanPlay() {
    return musicEnabled && !musicError && musicGestureSeen && musicActive && musicVolume > 0
      && !document.hidden && !musicPageHidden;
  }

  function musicStatus() {
    const playing = Boolean(musicSource && musicContext?.state === 'running' && musicCanPlay());
    return { enabled: musicEnabled, error: musicError, playing,
      pending: musicEnabled && !musicError && musicActive && musicVolume > 0
        && !document.hidden && !musicPageHidden && !playing };
  }

  function updateMusicGain() {
    if (!musicGain || !musicContext || musicContext.state === 'closed') return;
    try {
      const gain = musicGain.gain;
      const now = musicContext.currentTime;
      const target = musicCanPlay() ? musicVolume * (speechPlaybackEntry ? 0.18 : 1) : 0;
      gain.cancelScheduledValues(now);
      gain.setValueAtTime(gain.value, now);
      gain.setTargetAtTime(target, now, speechPlaybackEntry ? 0.06 : 0.3);
    } catch (_) { /* A closing audio context must never interrupt game state. */ }
  }

  function weightedMusicChoice(tracks) {
    const total = tracks.reduce((sum, track) => sum + track.weight, 0);
    let choice = Math.random() * total;
    for (const track of tracks) {
      choice -= track.weight;
      if (choice < 0) return track;
    }
    return tracks[tracks.length - 1];
  }

  function selectMusicTrack(initial = false) {
    const candidates = initial || !musicTrack
      ? MUSIC_TRACKS.filter(track => track.canStart)
      : MUSIC_TRACKS.filter(track => track.id !== musicTrack.id);
    musicTrack = weightedMusicChoice(candidates);
    return musicTrack;
  }

  function suspendMusic(reset = false) {
    musicEpoch += 1;
    if (musicJob) {
      clearTimeout(musicJob.timer);
      musicJob.controller.abort();
      musicJob = null;
    }
    if (musicSource) {
      if (musicBuffer && musicContext) {
        musicOffset = (musicOffset + Math.max(0, musicContext.currentTime - musicStartedAt)) % musicBuffer.duration;
      }
      musicSource.onended = null;
      try { musicSource.stop(); } catch (_) { /* Already stopped. */ }
      try { musicSource.disconnect(); } catch (_) { /* Already disconnected. */ }
      try { musicSource.buffer = null; } catch (_) { /* Disconnected node is released below. */ }
      musicSource = null;
    }
    if (reset) {
      musicBuffer = null;
      musicOffset = 0;
      musicTrack = null;
    }
    try {
      if (musicGain && musicContext?.state !== 'closed') {
        musicGain.gain.cancelScheduledValues(musicContext.currentTime);
        musicGain.gain.value = 0;
      }
      if (musicContext && musicContext.state !== 'closed') musicContext.suspend().catch(() => {});
    } catch (_) { /* Suspending is best effort after stopping the source. */ }
  }

  function finishMusicTrack(source) {
    if (musicSource !== source) return;
    source.onended = null;
    try { source.disconnect(); } catch (_) { /* The completed source may already be detached. */ }
    try { source.buffer = null; } catch (_) { /* Release the decoded track. */ }
    musicSource = null;
    musicBuffer = null;
    musicOffset = 0;
    musicEpoch += 1;
    selectMusicTrack(false);
    syncMusic();
  }

  function failMusic(error) {
    musicError = error?.name === 'NotAllowedError' || error?.message === 'MUSIC_BLOCKED'
      ? 'MUSIC_BLOCKED' : 'MUSIC_FAILED';
    suspendMusic(true);
  }

  function ensureMusicContext() {
    if (musicContext && musicContext.state !== 'closed') return musicContext;
    const Context = window.AudioContext || window.webkitAudioContext;
    if (!Context) throw new Error('MUSIC_FAILED');
    const context = new Context();
    musicContext = context;
    musicGain = context.createGain();
    musicGain.gain.value = 0;
    musicGain.connect(context.destination);
    return context;
  }

  function unlockMusic(trustedGesture = false) {
    if (!musicEnabled || musicError) return;
    const activated = trustedGesture || (navigator.userActivation
      ? navigator.userActivation.isActive : performance.now() - musicGestureAt < 1000);
    if (!activated) return; // Passive startup waits quietly for a real gesture.
    musicGestureSeen = true;
    try {
      const context = ensureMusicContext();
      const epoch = musicEpoch;
      // A welcome/tutorial tap unlocks permission, but never loads the track.
      context.resume().then(() => {
        if (context !== musicContext) return;
        if (!musicCanPlay()) return context.suspend();
      }).catch(error => {
        if (context === musicContext && musicEpoch === epoch && musicEnabled && !musicError) failMusic(error);
      }).catch(() => {});
    } catch (error) { failMusic(error); }
  }

  function syncMusic() {
    if (!musicCanPlay()) { suspendMusic(); return; }
    if (musicSource && musicContext?.state === 'running') { updateMusicGain(); return; }
    if (musicJob) return;
    // A source interrupted by the browser is replaced at its saved position.
    if (musicSource) suspendMusic();
    let context, resumed;
    try {
      context = ensureMusicContext();
      // Called synchronously by musicEnable so the actual gesture owns unlock.
      resumed = context.resume();
    } catch (error) { failMusic(error); return; }
    const track = musicTrack || selectMusicTrack(true);
    const job = { epoch: ++musicEpoch, track, controller: new AbortController(), timer: null };
    musicJob = job;
    const valid = () => musicJob === job && musicEpoch === job.epoch
      && musicTrack === track && musicCanPlay();
    job.timer = setTimeout(() => {
      if (valid()) failMusic(new Error(context.state === 'running' ? 'MUSIC_FAILED' : 'MUSIC_BLOCKED'));
    }, 20_000);
    // Every rejection is handled here; music cannot reach the app's global
    // unhandled-rejection UI or trigger a provider request.
    void (async () => {
      await resumed;
      if (!valid()) return;
      if (context.state !== 'running') throw new Error('MUSIC_BLOCKED');
      if (!musicBuffer) {
        const response = await fetch(track.url, { signal: job.controller.signal, credentials: 'omit' });
        if (!valid()) return;
        const length = Number(response.headers.get('content-length'));
        if (!response.ok || length > MUSIC_MAX_BYTES) throw new Error('MUSIC_FAILED');
        let bytes = await response.arrayBuffer();
        if (!valid()) return;
        if (!bytes.byteLength || bytes.byteLength > MUSIC_MAX_BYTES) throw new Error('MUSIC_FAILED');
        const buffer = await context.decodeAudioData(bytes);
        bytes = null;
        if (!valid()) return;
        if (!Number.isFinite(buffer.duration) || buffer.duration <= 0 || buffer.duration > 300
            || buffer.numberOfChannels > 2) throw new Error('MUSIC_FAILED');
        musicBuffer = buffer;
      }
      if (!valid()) return;
      if (context.state !== 'running') throw new Error('MUSIC_BLOCKED');
      const source = context.createBufferSource();
      musicSource = source;
      source.buffer = musicBuffer;
      source.loop = false;
      source.connect(musicGain);
      source.onended = () => {
        if (musicSource === source && musicEnabled) finishMusicTrack(source);
      };
      musicStartedAt = context.currentTime;
      source.start(0, musicOffset % musicBuffer.duration);
      updateMusicGain();
    })().catch(error => {
      if (valid()) failMusic(error);
    }).finally(() => {
      clearTimeout(job.timer);
      if (musicJob === job) musicJob = null;
    }).catch(() => {});
  }

  function controlMusic(action, value) {
    if (action === 'musicEnable') {
      // This command is an explicit switch/retry, never a lifecycle update.
      musicEnabled = Number(value) === 1;
      musicError = null;
      saveMusicPreference();
      if (!musicEnabled) {
        suspendMusic(true);
        return;
      }
      unlockMusic();
      syncMusic();
    } else if (action === 'musicVolume') {
      const volume = Number(value);
      if (!Number.isFinite(volume)) return;
      musicVolume = Math.max(0, Math.min(1, volume));
      syncMusic();
    } else if (action === 'musicActive') {
      musicActive = Number(value) === 1;
      syncMusic();
    } else if (action === 'musicStop') {
      musicActive = false;
      musicError = null;
      suspendMusic(true);
    }
  }

  function effectsCanPlay() {
    return effectsEnabled && effectsVolume > 0 && effectsActive && effectsGestureSeen
      && !document.hidden && !musicPageHidden && effectsContext?.state === 'running';
  }

  function updateEffectsGain() {
    if (!effectsGain || !effectsContext || effectsContext.state === 'closed') return;
    try {
      const gain = effectsGain.gain, now = effectsContext.currentTime;
      gain.cancelScheduledValues(now);
      gain.setValueAtTime(gain.value, now);
      gain.setTargetAtTime(effectsVolume * (speechPlaybackEntry ? 0.3 : 1), now, speechPlaybackEntry ? 0.04 : 0.2);
    } catch (_) { /* Effects are always best effort. */ }
  }

  function stopEffects(clearCache = false, suspendContext = false) {
    effectsEpoch += 1;
    effectsQueue.length = 0;
    if (effectsJob) {
      clearTimeout(effectsJob.timer);
      effectsJob.controller.abort();
      effectsJob = null;
    }
    if (effectsRelease) effectsRelease();
    if (clearCache) { effectsCache.clear(); effectsCacheBytes = 0; }
    try {
      // A game transition may stop and reactivate in the same task. Keep its
      // unlocked context running so the first cue cannot race an async resume.
      if (suspendContext && effectsContext && effectsContext.state !== 'closed') {
        effectsContext.suspend().catch(() => {});
      }
    } catch (_) { /* The source was already stopped above. */ }
  }

  function unlockEffects(trustedGesture = false) {
    if (!effectsEnabled || effectsVolume === 0 || document.hidden || musicPageHidden) return;
    if (!trustedGesture && !navigator.userActivation?.isActive && !effectsGestureSeen) return;
    effectsGestureSeen = true;
    try {
      if (!effectsContext || effectsContext.state === 'closed') {
        const Context = window.AudioContext || window.webkitAudioContext;
        if (!Context) return;
        const context = new Context();
        effectsContext = context;
        effectsGain = context.createGain();
        effectsGain.gain.value = effectsVolume;
        effectsGain.connect(context.destination);
        context.addEventListener('statechange', () => {
          if (effectsContext === context && context.state !== 'running'
              && (effectsSource || effectsJob || effectsQueue.length)) stopEffects();
        });
      }
      effectsContext.resume().catch(() => {});
    } catch (_) { /* A blocked device silently drops effects; no gameplay error. */ }
  }

  function pumpEffects() {
    if (effectsJob || effectsSource) return;
    if (!effectsCanPlay()) { effectsQueue.length = 0; return; }
    let cue;
    while (effectsQueue.length) {
      cue = effectsQueue.shift();
      if (cue.epoch === effectsEpoch && performance.now() - cue.createdAt <= 8_000) break;
      cue = null;
    }
    if (!cue) return;
    const context = effectsContext;
    const job = { controller: new AbortController(), timer: null };
    effectsJob = job;
    const valid = () => effectsJob === job && cue.epoch === effectsEpoch && effectsCanPlay()
      && performance.now() - cue.createdAt <= 8_000;
    job.timer = setTimeout(() => {
      if (effectsJob !== job) return;
      job.controller.abort();
      effectsJob = null;
      pumpEffects();
    }, 4_000);
    void (async () => {
      let buffer = effectsCache.get(cue.id);
      if (!buffer) {
        const response = await fetch(`/audio/effects/${cue.id}.mp3`, {
          credentials: 'omit', signal: job.controller.signal,
        });
        if (!valid()) return;
        if (!response.ok || Number(response.headers.get('content-length')) > 1024 * 1024) return;
        let bytes = await response.arrayBuffer();
        if (!valid() || !bytes.byteLength || bytes.byteLength > 1024 * 1024) return;
        buffer = await context.decodeAudioData(bytes);
        bytes = null;
        if (!valid() || !Number.isFinite(buffer.duration) || buffer.duration <= 0
            || buffer.duration > 5 || buffer.numberOfChannels > 2) return;
        const size = buffer.length * buffer.numberOfChannels * 4;
        if (!Number.isFinite(size) || size <= 0 || size > 8 * 1024 * 1024) return;
        while (effectsCache.size && effectsCacheBytes + size > 8 * 1024 * 1024) {
          const oldest = effectsCache.keys().next().value;
          const old = effectsCache.get(oldest);
          effectsCacheBytes -= old.length * old.numberOfChannels * 4;
          effectsCache.delete(oldest);
        }
        effectsCache.set(cue.id, buffer);
        effectsCacheBytes += size;
      }
      if (!valid()) return;
      const source = context.createBufferSource();
      effectsSource = source;
      let endTimer;
      const release = () => {
        if (effectsSource !== source) return;
        clearTimeout(endTimer);
        source.onended = null;
        try { source.stop(); } catch (_) { /* Already ended. */ }
        try { source.disconnect(); } catch (_) { /* Already disconnected. */ }
        try { source.buffer = null; } catch (_) { /* Disconnected node is released. */ }
        effectsSource = effectsRelease = null;
        pumpEffects();
      };
      effectsRelease = release;
      source.buffer = buffer;
      source.connect(effectsGain);
      source.onended = release;
      updateEffectsGain();
      source.start();
      endTimer = setTimeout(release, Math.ceil(buffer.duration * 1000) + 500);
    })().catch(() => {
      if (effectsJob === job && effectsRelease) effectsRelease();
    }).finally(() => {
      clearTimeout(job.timer);
      if (effectsJob === job) { effectsJob = null; pumpEffects(); }
    }).catch(() => {});
  }

  function enqueueEffect(entry, cue) {
    if (entry.effectsEpoch !== effectsEpoch || performance.now() - entry.effectsCreatedAt > 8_000
        || !EFFECT_CUES.has(cue) || !effectsCanPlay()) return;
    if (effectsQueue.length >= 4) effectsQueue.shift();
    effectsQueue.push({ id: cue, epoch: effectsEpoch, createdAt: entry.effectsCreatedAt });
    pumpEffects();
  }

  function controlEffects(action, value) {
    if (action === 'effectsEnable') {
      effectsEnabled = Number(value) === 1;
      try { window.localStorage.setItem(EFFECTS_PREFERENCE_KEY, effectsEnabled ? '1' : '0'); }
      catch (_) { /* Preserve the in-memory choice. */ }
      if (effectsEnabled) unlockEffects(); else stopEffects(true, true);
    } else if (action === 'effectsVolume') {
      const volume = Number(value);
      if (!Number.isFinite(volume)) return;
      effectsVolume = Math.max(0, Math.min(1, volume));
      if (effectsVolume === 0) stopEffects(false, true); else { unlockEffects(); updateEffectsGain(); }
    } else if (action === 'effectsActive') {
      effectsActive = Number(value) === 1;
      if (effectsActive) unlockEffects(); else stopEffects();
    } else if (action === 'effectsStop') stopEffects();
  }

  function stopNarration() {
    // Invalidate synthesis, decoding, and starts still waiting in a microtask.
    audioEpoch += 1;
    const entry = activeNarration;
    if (!entry) return;
    finish(entry, { data: null });
    entry.controller.abort();
  }

  function stopTutorial() {
    tutorialEpoch += 1;
    const entry = activeTutorial;
    tutorialClipId = null;
    if (!entry) return;
    finish(entry, { data: null });
    entry.controller.abort();
  }

  function audioError(entry, error) {
    if (!current(entry)) return;
    const mapped = errorCode(error);
    const code = error?.message === 'AUDIO_UNAVAILABLE' ? 'AUDIO_UNAVAILABLE'
      : error?.message === 'AUDIO_BLOCKED' || error?.name === 'NotAllowedError'
      ? 'AUDIO_BLOCKED'
      : ['BALANCE', 'AUTH', 'NETWORK', 'TIMEOUT', 'RATE_LIMIT', 'SETUP_REQUIRED'].includes(mapped)
        ? mapped : 'AUDIO_FAILED';
    mediaError = code;
    finish(entry, { error: code });
    entry.controller.abort();
  }

  function unlockAudio(trustedGesture = false) {
    if (!audioEnabled || audioVolume === 0) return;
    // Never resume from an async synthesis callback. A real user gesture owns
    // creation/resume, including a later tap if the first browser unlock failed.
    if (!trustedGesture && !navigator.userActivation?.isActive) return;
    try {
      if (!audioContext || audioContext.state === 'closed') {
        const Context = window.AudioContext || window.webkitAudioContext;
        if (!Context) return;
        const context = new Context();
        audioContext = context;
        audioGain = context.createGain();
        audioGain.gain.value = audioVolume;
        audioGain.connect(context.destination);
        context.addEventListener('statechange', () => {
          if (audioContext === context && context.state !== 'running' && speechPlaybackEntry) {
            audioError(speechPlaybackEntry, new Error('AUDIO_BLOCKED'));
          }
        });
      }
      if (audioContext.state !== 'running') {
        const context = audioContext;
        audioUnlock = context.resume().then(() => context.state === 'running').catch(() => false);
      }
    } catch (_) {
      // A later narration returns the fixed AUDIO_BLOCKED code to the app.
      audioUnlock = null;
    }
  }

  function narrationCurrent(entry) {
    return current(entry) && entry.audioEpoch === audioEpoch && audioEnabled && audioVolume > 0
      && (entry.op !== 'tutorialPlay' || entry.tutorialEpoch === tutorialEpoch);
  }

  function waitAudioUnlock(entry) {
    // AudioContext.resume itself cannot be aborted; navigation still releases
    // the waiting clip immediately and prevents any late playback.
    return new Promise(resolve => {
      const signal = entry.controller.signal;
      const done = () => { signal.removeEventListener('abort', done); resolve(); };
      if (signal.aborted) { done(); return; }
      signal.addEventListener('abort', done, { once: true });
      Promise.resolve(audioUnlock).then(done, done);
    });
  }

  function playBuffer(entry, context, buffer) {
    return new Promise((resolve, reject) => {
      if (!narrationCurrent(entry)) { resolve(); return; }
      if (context !== audioContext || context.state !== 'running') {
        reject(new Error('AUDIO_BLOCKED'));
        return;
      }
      let source;
      let settled = false;
      const release = error => {
        if (settled) return;
        settled = true;
        if (source) {
          source.onended = null;
          try { source.stop(); } catch (_) { /* Already ended or not started. */ }
          try { source.disconnect(); } catch (_) { /* Already disconnected. */ }
          try { source.buffer = null; } catch (_) { /* The stopped node is disconnected. */ }
          source = null;
        }
        buffer = null;
        entry.releaseAudio = null;
        if (speechPlaybackEntry === entry) {
          speechPlaybackEntry = null;
          updateMusicGain();
          updateEffectsGain();
        }
        if (error) reject(error); else resolve();
      };
      entry.releaseAudio = release;
      try {
        source = context.createBufferSource();
        source.buffer = buffer;
        buffer = null;
        source.connect(audioGain);
        source.onended = () => release();
        source.start();
        speechPlaybackEntry = entry;
        updateMusicGain();
        updateEffectsGain();
      } catch (error) { release(error); }
    });
  }

  async function narrate(entry, payload) {
    refreshAudioPreference();
    if (!narrationCurrent(entry)) return null;
    if (!payload || typeof payload.characterId !== 'string'
        || !Object.prototype.hasOwnProperty.call(VOICES, payload.characterId)
        || typeof payload.text !== 'string' || !payload.text.trim() || payload.text.length > 420) {
      throw new Error('AUDIO_FAILED');
    }
    if (activeNarration) throw new Error('AUDIO_FAILED');
    stopTutorial();
    mediaError = null;
    activeNarration = entry;
    if (audioUnlock) await waitAudioUnlock(entry);
    if (!narrationCurrent(entry)) return null;
    const context = audioContext;
    if (!context || context.state !== 'running') throw new Error('AUDIO_BLOCKED');
    const api = await sdk();
    if (!narrationCurrent(entry)) return null;
    // Narration follows an accepted public turn. It never opens a fresh login
    // flow or uses a developer/trial budget when a game account is unavailable.
    if (!api.isAuthenticated()) {
      if (mobileAuthBrowser()) rememberAuthResume();
      throw new Error('AUDIO_FAILED');
    }
    const signal = entry.controller.signal;
    await resolveSpeechModel(api, signal);
    if (!narrationCurrent(entry)) return null;
    if (context !== audioContext || context.state !== 'running') throw new Error('AUDIO_BLOCKED');
    // Re-read this harmless preference immediately before the paid call, so a
    // saved mute also wins over a delayed initial Kotlin status synchronization.
    refreshAudioPreference();
    if (!narrationCurrent(entry)) return null;
    // Only this already-accepted public speech is sent; no prompts, roles,
    // private reasoning, game history, or character profile enter TTS.
    const previousBalanceVersion = walletBalanceVersion;
    let blob;
    try {
      blob = await api.generateSpeech({
        text: payload.text.trim(), model: speechModel, voice: VOICES[payload.characterId],
        responseFormat: 'mp3', speed: SPEECH_SPEED, signal, timeout: SPEECH_TIMEOUT,
      });
    } finally { refreshWalletAfterPaid(previousBalanceVersion); }
    if (!narrationCurrent(entry)) return null;
    if (!(blob instanceof Blob) || blob.size === 0 || blob.size > 5 * 1024 * 1024) throw new Error('AUDIO_FAILED');
    let bytes = await blob.arrayBuffer();
    blob = null;
    if (!narrationCurrent(entry)) return null;
    let buffer = await context.decodeAudioData(bytes);
    bytes = null;
    if (!narrationCurrent(entry)) return null;
    if (!Number.isFinite(buffer.duration) || buffer.duration <= 0 || buffer.duration > 90) throw new Error('AUDIO_FAILED');
    const playback = playBuffer(entry, context, buffer);
    buffer = null;
    await playback;
    return null;
  }

  async function tutorialPlay(entry, payload) {
    if (!TUTORIAL_AUDIO_READY) throw new Error('AUDIO_UNAVAILABLE');
    refreshAudioPreference();
    if (!narrationCurrent(entry)) return null;
    if (!payload || typeof payload.clipId !== 'string' || !TUTORIAL_CLIPS.has(payload.clipId)) {
      throw new Error('AUDIO_FAILED');
    }
    if (!narrationCurrent(entry) || document.hidden || musicPageHidden) return null;
    activeTutorial = entry;
    tutorialClipId = payload.clipId;
    mediaError = null;
    // A passive tutorial render waits for a real gesture without an autoplay
    // error, a timer, a fetch, or any SDK/provider access.
    if (!audioContext || audioContext.state !== 'running') {
      if (audioUnlock) await waitAudioUnlock(entry);
      if (!narrationCurrent(entry)) return null;
      if (!audioContext || audioContext.state !== 'running') {
        await new Promise(resolve => { entry.onAudioGesture = resolve; });
      }
      if (audioUnlock) await waitAudioUnlock(entry);
    }
    if (!narrationCurrent(entry)) return null;
    const context = audioContext;
    if (!context || context.state !== 'running') throw new Error('AUDIO_BLOCKED');
    armTimeout(entry, 20_000);
    const response = await fetch(`/audio/tutorial/${payload.clipId}.mp3`, {
      credentials: 'omit', signal: entry.controller.signal,
    });
    if (!narrationCurrent(entry)) return null;
    if (!response.ok || Number(response.headers.get('content-length')) > 5 * 1024 * 1024) throw new Error('AUDIO_FAILED');
    let bytes = await response.arrayBuffer();
    if (!narrationCurrent(entry)) return null;
    if (!bytes.byteLength || bytes.byteLength > 5 * 1024 * 1024) throw new Error('AUDIO_FAILED');
    let buffer = await context.decodeAudioData(bytes);
    bytes = null;
    if (!narrationCurrent(entry)) return null;
    if (!Number.isFinite(buffer.duration) || buffer.duration <= 0 || buffer.duration > 90
        || buffer.numberOfChannels > 2) throw new Error('AUDIO_FAILED');
    armTimeout(entry, Math.ceil(buffer.duration * 1000) + 5_000);
    const playback = playBuffer(entry, context, buffer);
    buffer = null;
    await playback;
    return null;
  }

  const LOCAL_SAVE_KEY = 'mafia.save.v1';
  function validSave(data) {
    return data && typeof data === 'object' && !Array.isArray(data) && data.version === 1
      && new TextEncoder().encode(JSON.stringify(data)).byteLength <= 850_000;
  }
  function readLocalSave() {
    try { return window.localStorage.getItem(LOCAL_SAVE_KEY) || ''; } catch (_) { return ''; }
  }
  function writeLocalSave(raw) {
    try {
      const data = JSON.parse(raw);
      if (!validSave(data)) return false;
      data.welcomeSeen = data.welcomeSeen === true || window.localStorage.getItem('mafia.tutorial.welcome.v1') === 'seen';
      window.localStorage.setItem(LOCAL_SAVE_KEY, JSON.stringify(data));
      if (data.welcomeSeen) window.localStorage.setItem('mafia.tutorial.welcome.v1', 'seen');
      return true;
    } catch (_) { return false; }
  }
  // Backups are explicit user actions. Menus/tutorials never trigger cloud login
  // or silently overwrite an account's save with another browser user's state.
  async function appBackup(entry, op, payload) {
    const api = await sdk();
    assertCurrent(entry);
    if (!api.data?.get || !api.data?.set) throw new Error('STORAGE_INVALID');
    const document = await api.data.get();
    assertCurrent(entry);
    if (!document || typeof document !== 'object' || Array.isArray(document)) throw new Error('STORAGE_INVALID');
    if (op === 'restoreBackup') {
      const saved = document.mafia?.state;
      if (saved == null) return null;
      if (!validSave(saved)) throw new Error('STORAGE_INVALID');
      return saved;
    }
    if (!validSave(payload)) throw new Error('STORAGE_INVALID');
    const revision = api.data.revision;
    if (!Number.isInteger(revision) || revision < 0) throw new Error('STORAGE_INVALID');
    let welcomeSeen = payload.welcomeSeen === true;
    try { welcomeSeen ||= window.localStorage.getItem('mafia.tutorial.welcome.v1') === 'seen'; } catch (_) {}
    await api.data.set({ ...document, mafia: { state: { ...payload, welcomeSeen }, savedAt: new Date().toISOString() } }, { ifRevision: revision });
    assertCurrent(entry);
    return null;
  }

  async function perform(entry, op, payload) {
    assertCurrent(entry);
    if (!['models', 'complete', 'narrate', 'walletOpen', 'walletStatus', 'musicStatus', 'mediaStatus', 'tutorialPlay', 'effectsStatus', 'effect', 'backup', 'restoreBackup'].includes(op)) throw new Error('UNKNOWN_OPERATION');
    if (op === 'backup' || op === 'restoreBackup') return appBackup(entry, op, payload);
    if (op === 'effectsStatus') return { enabled: effectsEnabled };
    if (op === 'effect') { enqueueEffect(entry, payload?.cue); return null; }
    if (op === 'mediaStatus') return mediaStatus();
    if (op === 'tutorialPlay') return tutorialPlay(entry, payload);
    if (op === 'musicStatus') return musicStatus();
    if (op === 'walletStatus') return readWalletStatus(entry, payload);
    if (op === 'walletOpen') return openWallet(entry);
    if (op === 'narrate') return narrate(entry, payload);
    if (op === 'complete') {
      // Acquire before SDK loading/authentication; requests are rejected, never queued.
      if (activeCompletion) throw new Error('REQUEST_RUNNING');
      activeCompletion = entry;
    }
    try {
      const api = await sdk();
      assertCurrent(entry);
      const signal = entry.controller.signal;
      if (op === 'models') {
        // Resolve the free TTS catalog alongside the text catalog so the first
        // spoken turn does not pay a second sequential catalog round-trip.
        const speechWarmup = audioEnabled ? resolveSpeechModel(api, signal).catch(() => null) : null;
        const result = await api.getModelCatalog({ type: 'text', method: 'chat_completions', signal });
        if (speechWarmup) await speechWarmup;
        assertCurrent(entry);
        return (result.data || []).filter(m => typeof m.id === 'string' && m.id.trim())
          .map(m => ({ id: m.id, displayName: typeof m.name === 'string' ? m.name : m.id }));
      }
      if (!payload || typeof payload.model !== 'string' || !payload.model.trim()
          || !Array.isArray(payload.messages) || payload.messages.length === 0
          || payload.messages.some(m => !m || !['system', 'user'].includes(m.role) || typeof m.content !== 'string')) {
        throw new Error('INVALID_RESPONSE');
      }
      if (!walletConnected(api) && mobileAuthBrowser()) rememberAuthResume();
      // This app uses an ordinary public OAuth client. Fail closed if someone
      // deploys it in the SDK's server-injected anonymous showcase environment.
      if (window.__AIPASS_TRIAL__?.enabled && !api.isAuthenticated()) throw new Error('SETUP_REQUIRED');

      if (api.isAuthenticated()) armTimeout(entry, COMPLETION_TIMEOUT);
      else {
        armTimeout(entry, AUTH_TIMEOUT);
        // Do not inspect event.detail: the SDK owns all authentication material.
        entry.onLogin = () => {
          if (current(entry)) armTimeout(entry, COMPLETION_TIMEOUT);
        };
        document.addEventListener('aipass:login', entry.onLogin, { once: true });
      }
      const reasoningModel = /^(?:openai\/)?(?:gpt-[56](?:[.-]|$)|o[34](?:[.-]|$))/.test(payload.model);
      // DeepSeek V4 otherwise defaults to HIGH thinking effort. Keep genuine
      // reasoning enabled, but use its documented low effort for short turns.
      const deepseekModel = payload.model === 'deepseek-v4-flash-0731';
      const maxTokens = Number.isInteger(payload.maxTokens)
        ? Math.min(2048, Math.max(256, payload.maxTokens)) : 1024;
      assertCurrent(entry);
      // No application retries. The SDK may refresh authentication once on 401,
      // but network errors, timeouts, 5xx and malformed responses stop this move.
      const previousBalanceVersion = walletBalanceVersion;
      let response;
      try {
        response = await api.generateCompletion({
          model: payload.model, messages: payload.messages, maxTokens,
          ...(reasoningModel ? { reasoning_effort: 'low', temperature: 1 }
            : deepseekModel ? { reasoning_effort: 'low' } : {}),
          stream: false, signal, timeout: COMPLETION_TIMEOUT,
        });
      } finally { refreshWalletAfterPaid(previousBalanceVersion); }
      assertCurrent(entry);
      const choice = response.choices?.[0];
      const rawContent = choice?.message?.content;
      const content = typeof rawContent === 'string' ? rawContent
        : Array.isArray(rawContent) && rawContent.every(block => block?.type === 'text' && typeof block.text === 'string')
          ? rawContent.map(block => block.text).join('') : '';
      const rejection = choice?.message?.refusal ? 'AI_REFUSED'
        : choice?.finish_reason === 'length' ? 'AI_TRUNCATED'
        : !content.trim() ? 'AI_EMPTY'
        : content.length > 24_000 ? 'AI_BAD_JSON' : null;
      const usage = response.usage || {};
      return { content: rejection ? '' : content, rejection, usage: {
        model: payload.model,
        inputTokens: tokenCount(usage.prompt_tokens),
        cachedInputTokens: Math.min(tokenCount(usage.prompt_tokens), tokenCount(usage.prompt_tokens_details?.cached_tokens)),
        outputTokens: tokenCount(usage.completion_tokens),
        estimatedCost: typeof usage.cost === 'number' && Number.isFinite(usage.cost) && usage.cost >= 0 ? usage.cost : null,
      }};
    } finally {
      if (activeCompletion === entry) activeCompletion = null;
    }
  }

  window.MafiaBridge = {
    readLocalSave,
    writeLocalSave,
    setActiveGameId,
    consumeAuthResumeGameId,
    start(op, raw) {
      if (op === 'tutorialPlay') { stopTutorial(); stopNarration(); }
      const entry = { id: ++sequence, op, result: null, controller: new AbortController(), timer: null,
        onLogin: null, onAudioGesture: null, audioEpoch, tutorialEpoch, effectsEpoch,
        effectsCreatedAt: performance.now(), releaseAudio: null };
      pending.set(entry.id, entry);
      if (op !== 'tutorialPlay') armTimeout(entry, op === 'narrate' ? NARRATION_TIMEOUT
        : ['complete', 'backup', 'restoreBackup'].includes(op) ? AUTH_TIMEOUT + COMPLETION_TIMEOUT + NETWORK_TIMEOUT
          : op === 'walletOpen' ? AUTH_TIMEOUT + NETWORK_TIMEOUT : 40_000);
      const run = () => Promise.resolve().then(() => {
        assertCurrent(entry);
        return perform(entry, op, JSON.parse(raw));
      }).then(data => finish(entry, { data }))
        .catch(e => ['narrate', 'tutorialPlay'].includes(op) ? audioError(entry, e)
          : finish(entry, op === 'effect' ? { data: null } : { error: errorCode(e) }));
      // Compose dispatches a tap from its canvas before some mobile browsers
      // synthesize the final DOM click. If the SDK inserts its modal in that
      // same event turn, the synthetic click lands on the new backdrop and
      // immediately dismisses it. Open the wallet in the next browser task.
      if (op === 'walletOpen') setTimeout(run, 32);
      else run();
      return entry.id;
    },
    audioControl(action, value) {
      if (['effectsEnable', 'effectsVolume', 'effectsActive', 'effectsStop'].includes(action)) {
        controlEffects(action, value);
        return;
      }
      if (['musicEnable', 'musicVolume', 'musicActive', 'musicStop'].includes(action)) {
        controlMusic(action, value);
        return;
      }
      if (action === 'enable') {
        audioEnabled = Number(value) === 1;
        mediaError = null;
        try { window.localStorage.setItem(AUDIO_PREFERENCE_KEY, audioEnabled ? '1' : '0'); }
        catch (_) { /* Keep the in-memory preference when browser storage is blocked. */ }
        if (audioEnabled) unlockAudio();
        else { stopTutorial(); stopNarration(); }
      } else if (action === 'volume') {
        const volume = Number(value);
        if (!Number.isFinite(volume)) return;
        audioVolume = Math.max(0, Math.min(1, volume));
        if (audioGain) audioGain.gain.value = audioVolume;
        if (audioVolume === 0) { stopTutorial(); stopNarration(); } else unlockAudio();
      } else if (action === 'stop') { stopTutorial(); stopNarration(); }
      else if (action === 'tutorialStop') stopTutorial();
    },
    poll(id) {
      const entry = pending.get(id);
      if (!entry || entry.result === null) return null;
      pending.delete(id);
      return entry.result;
    },
    cancel(id) {
      const entry = pending.get(id);
      if (!entry) return;
      pending.delete(id);
      clearEntry(entry);
      entry.controller.abort();
    },
  };

  // Warm the public SDK and client configuration while the much larger Wasm
  // bundle loads. This makes the first short tap use the same ready path as a
  // later/long tap. Never initialize ahead of callback consumption: the SDK
  // may clean OAuth parameters after it exchanges them.
  if (!oauthCallbackInUrl()) {
    const warmSdk = () => { void sdk().catch(() => {}); };
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', warmSdk, { once: true });
    else setTimeout(warmSdk, 0);
  }
  const audioGesture = event => {
    if (event.isTrusted) {
      musicGestureAt = performance.now();
      unlockAudio(true);
      if (activeTutorial?.onAudioGesture) {
        activeTutorial.onAudioGesture();
        activeTutorial.onAudioGesture = null;
      }
      unlockMusic(true);
      unlockEffects(true);
      if (musicCanPlay()) syncMusic();
    }
  };
  window.addEventListener('pointerdown', audioGesture, { capture: true, passive: true });
  window.addEventListener('keydown', audioGesture, { capture: true });
  window.addEventListener('pointerup', audioGesture, { capture: true, passive: true });
  window.addEventListener('click', audioGesture, { capture: true, passive: true });
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) { stopTutorial(); stopEffects(false, true); }
    syncMusic();
  });
  window.addEventListener('pageshow', () => {
    musicPageHidden = false;
    if (musicEnabled) syncMusic();
  });
  document.addEventListener('aipass:logout', stopNarration);
  document.addEventListener('aipass:login', () => {
    // Login event details can contain credentials; deliberately never read them.
    resetWallet(walletConnected() ? 'CONNECTED' : 'SIGNED_OUT');
    void refreshWalletSnapshot();
  });
  document.addEventListener('aipass:logout', () => resetWallet('SIGNED_OUT'));
  document.addEventListener('aipass:balance', event => {
    // Read only the documented numeric balance field, never the whole event.
    if (walletConnected() && !publishWalletBalance(event.detail?.balance)) walletUnavailable();
  });
  window.addEventListener('pagehide', () => {
    musicPageHidden = true;
    suspendMusic();
    stopEffects(true, true);
    stopTutorial();
    stopNarration();
    walletEpoch += 1;
    walletController?.abort();
    walletRequest = walletController = null;
    if (walletSnapshot.balance) publishWallet({ ...walletSnapshot, stale: true });
    // A back/forward-cache restore can resume the same Kotlin coroutine. Keep
    // terminal results for its poll instead of leaving it waiting on a missing ID.
    for (const entry of pending.values()) {
      if (current(entry)) finish(entry, entry.op === 'musicStatus'
        ? { data: musicStatus() } : entry.op === 'mediaStatus'
          ? { data: mediaStatus() } : entry.op === 'effectsStatus'
            ? { data: { enabled: effectsEnabled } } : entry.op === 'effect' ? { data: null } : { error: 'NETWORK' });
      entry.controller.abort();
    }
    const context = audioContext;
    audioContext = audioGain = audioUnlock = null;
    if (context && context.state !== 'closed') context.close().catch(() => {});
  });
})();
