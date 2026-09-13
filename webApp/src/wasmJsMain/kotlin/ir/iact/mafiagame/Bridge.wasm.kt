@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ir.iact.mafiagame

@JsFun("(operation, payload) => window.MafiaBridge.start(operation, payload)")
private external fun startJs(operation: String, payload: String): Int
@JsFun("(id) => window.MafiaBridge.poll(id)")
private external fun pollJs(id: Int): String?
@JsFun("(id) => window.MafiaBridge.cancel(id)")
private external fun cancelJs(id: Int)
@JsFun("(action, value) => window.MafiaBridge.audioControl(action, value)")
private external fun audioControlJs(action: String, value: Double)
@JsFun("() => window.MafiaShell.ready()")
private external fun readyJs()
internal actual fun bridgeStart(operation: String, payload: String) = startJs(operation, payload)
internal actual fun bridgePoll(id: Int) = pollJs(id)
internal actual fun bridgeCancel(id: Int) = cancelJs(id)
internal actual fun bridgeAudioControl(action: String, value: Double) = audioControlJs(action, value)
internal actual fun browserReady() = readyJs()

// One harmless onboarding preference; no account, tutorial answers, or game state.
@JsFun("() => { try { return localStorage.getItem('mafia.tutorial.welcome.v1') === 'seen'; } catch (_) { return false; } }")
private external fun welcomeSeenJs(): Boolean
@JsFun("() => { try { localStorage.setItem('mafia.tutorial.welcome.v1', 'seen'); } catch (_) {} }")
private external fun markWelcomeSeenJs()
internal actual fun tutorialWelcomeSeen() = welcomeSeenJs()
internal actual fun rememberTutorialWelcome() = markWelcomeSeenJs()

@JsFun("() => { try { return localStorage.getItem('mafia.player.name.v1') || ''; } catch (_) { return ''; } }")
private external fun savedPlayerNameJs(): String
@JsFun("(name) => { try { localStorage.setItem('mafia.player.name.v1', name); } catch (_) {} }")
private external fun savePlayerNameJs(name: String)
internal actual fun savedPlayerName() = savedPlayerNameJs()
internal actual fun savePlayerName(name: String) = savePlayerNameJs(name)
@JsFun("() => window.MafiaBridge.readLocalSave()")
private external fun readLocalSaveJs(): String
@JsFun("(value) => window.MafiaBridge.writeLocalSave(value)")
private external fun writeLocalSaveJs(value: String): Boolean
internal actual fun readLocalSave() = readLocalSaveJs()
internal actual fun writeLocalSave(value: String) = writeLocalSaveJs(value)

@JsFun("() => { try { return new URLSearchParams(location.search).get('debug') === '1'; } catch (_) { return false; } }")
private external fun fastPacingJs(): Boolean
internal actual fun fastPacingEnabled() = fastPacingJs()

@JsFun("(gameId) => window.MafiaBridge.setActiveGameId(gameId)")
private external fun setActiveGameIdJs(gameId: String)
@JsFun("() => window.MafiaBridge.consumeAuthResumeGameId()")
private external fun consumeAuthResumeGameIdJs(): String
internal actual fun setActiveGameId(gameId: String) = setActiveGameIdJs(gameId)
internal actual fun consumeAuthResumeGameId() = consumeAuthResumeGameIdJs()
