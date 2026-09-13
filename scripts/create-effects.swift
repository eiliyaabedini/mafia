#!/usr/bin/env swift
// Original short Mafia cues, rendered offline with the macOS system sampler.
import Foundation
import AVFoundation
import AudioToolbox

struct Note {
    let instrument: Int; let pitch: UInt8; let start: Double; let length: Double; let velocity: UInt8
}
func n(_ i: Int, _ p: UInt8, _ t: Double, _ d: Double, _ v: UInt8 = 72) -> Note {
    Note(instrument: i, pitch: p, start: t, length: d, velocity: v)
}
// Pizzicato strings, acoustic bass, timpani, woodblock, vibraphone, string ensemble.
let programs: [UInt8] = [45, 32, 47, 115, 11, 48]
let cues: [(String, Double, [Note])] = [
    ("game_start", 2.5, [n(0,55,0.04,0.2), n(0,62,0.21,0.2), n(0,70,0.38,0.3),
        n(1,43,0.04,1.0,60), n(5,55,0.4,1.1,44), n(5,58,0.4,1.1,40), n(5,62,0.4,1.1,42)]),
    ("vote_open", 1.25, [n(3,72,0.03,0.08,62), n(3,77,0.22,0.08,54), n(0,62,0.22,0.35,52)]),
    ("vote_tied", 1.6, [n(3,67,0.04,0.07,56), n(3,67,0.3,0.07,45), n(0,57,0.08,0.4,50), n(0,62,0.32,0.5,46)]),
    ("eliminated", 2.15, [n(2,43,0.04,0.65,68), n(1,31,0.04,0.85,67),
        n(0,55,0.12,0.3,65), n(0,54,0.3,0.5,55), n(5,43,0.05,0.95,42)]),
    ("night_killed", 2.2, [n(2,36,0.03,0.22,62), n(2,36,0.29,0.3,44),
        n(1,31,0.03,0.85,62), n(5,43,0.11,1.05,42), n(5,50,0.11,1.05,36)]),
    ("night_saved", 1.65, [n(4,67,0.04,0.65,45), n(4,74,0.25,0.65,39), n(0,55,0.03,0.4,42)]),
    ("town_win", 3.05, [n(0,55,0.03,0.3,68), n(0,59,0.25,0.3,66), n(0,62,0.47,0.4,70),
        n(4,79,0.7,1.05,46), n(1,43,0.03,1.3,57),
        n(5,55,0.65,1.3,46), n(5,59,0.65,1.3,43), n(5,62,0.65,1.3,44)]),
    ("mafia_win", 3.1, [n(0,62,0.03,0.3,63), n(0,58,0.26,0.3,63), n(0,55,0.49,0.4,68),
        n(2,43,0.68,0.8,55), n(1,31,0.68,1.25,60),
        n(5,43,0.65,1.3,46), n(5,46,0.65,1.3,43), n(5,50,0.65,1.3,43)]),
]
let output = URL(fileURLWithPath: CommandLine.arguments.dropFirst().first ?? "/tmp/mafia-effects")
try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)
let bank = URL(fileURLWithPath: "/System/Library/Components/CoreAudio.component/Contents/Resources/gs_instruments.dls")
let rate = 44_100.0
let format = AVAudioFormat(standardFormatWithSampleRate: rate, channels: 2)!
for (name, seconds, notes) in cues {
    let engine = AVAudioEngine()
    let mixer = AVAudioMixerNode(), room = AVAudioUnitReverb()
    engine.attach(mixer); engine.attach(room)
    room.loadFactoryPreset(.mediumRoom); room.wetDryMix = 11
    let samplers = programs.map { program -> AVAudioUnitSampler in
        let s = AVAudioUnitSampler(); engine.attach(s)
        try! s.loadSoundBankInstrument(at: bank, program: program,
            bankMSB: UInt8(kAUSampler_DefaultMelodicBankMSB), bankLSB: 0)
        s.overallGain = -9
        engine.connect(s, to: mixer, format: format)
        return s
    }
    engine.connect(mixer, to: room, format: format)
    engine.connect(room, to: engine.mainMixerNode, format: format)
    try engine.enableManualRenderingMode(.offline, format: format, maximumFrameCount: 1024)
    try engine.start()
    var events: [(Int64, Int, UInt8, UInt8)] = []
    for note in notes {
        events.append((Int64(note.start * rate), note.instrument, note.pitch, note.velocity))
        events.append((Int64((note.start + note.length) * rate), note.instrument, note.pitch, 0))
    }
    events.sort { $0.0 < $1.0 }
    let file = try AVAudioFile(forWriting: output.appendingPathComponent(name + ".caf"), settings: format.settings)
    let buffer = AVAudioPCMBuffer(pcmFormat: engine.manualRenderingFormat, frameCapacity: 1024)!
    var index = 0
    let end = Int64(seconds * rate)
    while engine.manualRenderingSampleTime < end {
        let now = engine.manualRenderingSampleTime
        while index < events.count && events[index].0 <= now {
            let event = events[index]
            if event.3 > 0 { samplers[event.1].startNote(event.2, withVelocity: event.3, onChannel: 0) }
            else { samplers[event.1].stopNote(event.2, onChannel: 0) }
            index += 1
        }
        let next = index < events.count ? min(events[index].0, end) : end
        let count = AVAudioFrameCount(min(1024, max(1, next - now)))
        let status = try engine.renderOffline(count, to: buffer)
        if status == .success { try file.write(from: buffer) }
        else if status == .error { throw NSError(domain: "EffectRendering", code: 1) }
    }
    engine.stop()
    print("Rendered \(name)")
}
