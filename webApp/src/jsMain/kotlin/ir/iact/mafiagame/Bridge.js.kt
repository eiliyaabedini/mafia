package ir.iact.mafiagame

internal actual fun bridgeStart(operation: String, payload: String): Int = js("window.MafiaBridge.start(operation, payload)") as Int
internal actual fun bridgePoll(id: Int): String? = js("window.MafiaBridge.poll(id)") as String?
internal actual fun bridgeCancel(id: Int) { js("window.MafiaBridge.cancel(id)") }
internal actual fun bridgeAudioControl(action: String, value: Double) { js("window.MafiaBridge.audioControl(action, value)") }
internal actual fun browserReady() { js("window.MafiaShell.ready()") }
internal actual fun tutorialWelcomeSeen(): Boolean = js("(function(){try{return localStorage.getItem('mafia.tutorial.welcome.v1') === 'seen';}catch(_){return false;}})()") as Boolean
internal actual fun rememberTutorialWelcome() { js("(function(){try{localStorage.setItem('mafia.tutorial.welcome.v1','seen');}catch(_){}})()") }
internal actual fun savedPlayerName(): String = js("(function(){try{return localStorage.getItem('mafia.player.name.v1') || '';}catch(_){return '';}})()") as String
internal actual fun savePlayerName(name: String) { js("(function(value){try{localStorage.setItem('mafia.player.name.v1',value);}catch(_){}})(name)") }
internal actual fun readLocalSave(): String = js("window.MafiaBridge.readLocalSave()") as String
internal actual fun writeLocalSave(value: String): Boolean = js("window.MafiaBridge.writeLocalSave(value)") as Boolean
internal actual fun fastPacingEnabled(): Boolean = js("(function(){try{return new URLSearchParams(location.search).get('debug') === '1';}catch(_){return false;}})()") as Boolean
internal actual fun setActiveGameId(gameId: String) { js("window.MafiaBridge.setActiveGameId(gameId)") }
internal actual fun consumeAuthResumeGameId(): String = js("window.MafiaBridge.consumeAuthResumeGameId()") as String
