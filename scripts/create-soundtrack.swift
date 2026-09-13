#!/usr/bin/env swift
// Original score: "A Promise After Midnight" (Mafia Waltz, v2).
// Local macOS sampled rendering only; no network, external song, or audio model.
import Foundation
import AVFoundation
import AudioToolbox

let sampleRate = 44_100.0
let bpm = 72.0
let beatsPerLoop = 96.0
let secondsPerBeat = 60.0 / bpm
let framesPerLoop = Int64((beatsPerLoop * secondsPerBeat * sampleRate).rounded())
let totalFrames = framesPerLoop * 3
let outputPath = CommandLine.arguments.dropFirst().first ?? "/tmp/mafia-waltz-v2-render.caf"
let bank = URL(fileURLWithPath: "/System/Library/Components/CoreAudio.component/Contents/Resources/gs_instruments.dls")

struct Instrument {
    let sampler: AVAudioUnitSampler
    let program: UInt8
    let gain: Float
    let pan: Float
}

let engine = AVAudioEngine()
let format = AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 2)!
let bus = AVAudioMixerNode()
let room = AVAudioUnitReverb()
let instruments = [
    Instrument(sampler: AVAudioUnitSampler(), program: 56, gain: -10, pan: -5), // Solo trumpet
    Instrument(sampler: AVAudioUnitSampler(), program: 24, gain: -23, pan: 24), // Tremolo nylon accompaniment
    Instrument(sampler: AVAudioUnitSampler(), program: 48, gain: -24, pan: -22), // Legato string ensemble
    Instrument(sampler: AVAudioUnitSampler(), program: 21, gain: -28, pan: 12), // Quiet accordion
    Instrument(sampler: AVAudioUnitSampler(), program: 32, gain: -19, pan: 0), // Plucked acoustic bass
    Instrument(sampler: AVAudioUnitSampler(), program: 24, gain: -9, pan: 8), // Picked nylon melody (mandolin-like)
]
engine.attach(bus)
engine.attach(room)
room.loadFactoryPreset(.mediumHall)
room.wetDryMix = 16
for instrument in instruments {
    engine.attach(instrument.sampler)
    try instrument.sampler.loadSoundBankInstrument(at: bank, program: instrument.program,
        bankMSB: UInt8(kAUSampler_DefaultMelodicBankMSB), bankLSB: 0)
    instrument.sampler.overallGain = instrument.gain
    instrument.sampler.stereoPan = instrument.pan
    instrument.sampler.sendController(7, withValue: 100, onChannel: 0)
    engine.connect(instrument.sampler, to: bus, format: format)
}
engine.connect(bus, to: room, format: format)
engine.connect(room, to: engine.mainMixerNode, format: format)
try engine.enableManualRenderingMode(.offline, format: format, maximumFrameCount: 4096)

struct Event {
    let frame: Int64
    let instrument: Int
    let note: UInt8
    let velocity: UInt8
    let isOn: Bool
}
var events: [Event] = []
func addNote(_ instrument: Int, _ note: Int, at beat: Double, duration: Double, velocity: Int) {
    for cycle in 0..<3 {
        let offset = Int64(cycle) * framesPerLoop
        let start = offset + Int64((beat * secondsPerBeat * sampleRate).rounded())
        let end = offset + Int64(((beat + duration) * secondsPerBeat * sampleRate).rounded())
        events.append(Event(frame: start, instrument: instrument, note: UInt8(note), velocity: UInt8(velocity), isOn: true))
        events.append(Event(frame: end, instrument: instrument, note: UInt8(note), velocity: 0, isOn: false))
    }
}

// Original 32-bar waltz: A (8), A variation (8), contrasting B (8), A reprise (8).
// G minor with harmonic-minor dominant motion, inversions, and an Eb/Bb-colored bridge.
// Chord roots move on beat one; quiet accordion offbeats make the triple meter explicit.
let gm = [55, 58, 62]
let d7 = [54, 57, 60, 62]
let cm6 = [55, 57, 60, 63]
let eb = [55, 58, 62, 63]
let chords: [[Int]] = [
    gm, d7, gm, cm6, eb, cm6, d7, gm,
    gm, [53, 58, 62, 65], [55, 60, 63], gm, eb, cm6, [54, 58, 60, 62], d7,
    [53, 58, 62], [53, 57, 60], gm, d7, eb, cm6, cm6, d7,
    gm, d7, gm, cm6, eb, cm6, d7, gm,
]
let bass = [
    43, 42, 46, 39, 39, 45, 38, 43,
    43, 41, 36, 38, 39, 36, 38, 38,
    34, 33, 43, 42, 39, 36, 45, 38,
    43, 42, 46, 39, 39, 45, 38, 43,
]

// All melody pitches and rhythms below are authored for this cue, with no film-theme quotation.
// Eight-bar phrases breathe before answering; the bridge gives the theme to tremolo nylon.
let phraseA: [[(Double, Int, Double)]] = [
    [(0.08, 70, 0.70), (0.85, 74, 0.38), (1.38, 69, 1.32)],
    [(0.09, 67, 0.73), (0.93, 66, 1.70)],
    [(0.10, 74, 1.13), (1.50, 70, 0.42), (2.03, 67, 0.76)],
    [(0.12, 75, 1.40), (1.85, 74, 0.38), (2.40, 72, 0.40)],
    [(0.08, 70, 1.57), (2.08, 67, 0.70)],
    [(0.07, 69, 0.66), (0.90, 72, 0.68), (1.75, 67, 1.00)],
    [(0.12, 66, 1.57), (2.08, 69, 0.70)],
    [(0.08, 67, 2.13)],
]
let phraseA2: [[(Double, Int, Double)]] = [
    phraseA[0],
    [(0.10, 74, 0.71), (0.93, 72, 0.63), (1.77, 69, 0.93)],
    [(0.08, 75, 0.86), (1.15, 74, 0.51), (1.93, 72, 0.83)],
    [(0.10, 70, 1.30), (1.80, 69, 0.41), (2.36, 67, 0.44)],
    [(0.08, 67, 0.80), (1.12, 70, 0.60), (1.95, 74, 0.80)],
    [(0.08, 72, 1.20), (1.62, 75, 1.03)],
    [(0.10, 74, 1.20), (1.65, 72, 0.50), (2.30, 69, 0.40)],
    [(0.12, 66, 2.12)],
]
let phraseB: [[(Double, Int, Double)]] = [
    [(0.08, 65, 0.68), (0.91, 70, 1.70)],
    [(0.08, 69, 0.80), (1.08, 72, 0.70), (2.08, 69, 0.70)],
    [(0.08, 70, 1.20), (1.70, 74, 1.05)],
    [(0.08, 69, 2.27)],
    [(0.10, 67, 0.55), (0.85, 70, 0.55), (1.62, 75, 1.12)],
    [(0.08, 74, 0.60), (0.85, 72, 1.78)],
    [(0.08, 72, 0.70), (1.05, 69, 0.70), (2.08, 67, 0.70)],
    [(0.08, 66, 1.30), (1.78, 69, 0.92)],
]
var reprise = phraseA
reprise[5] = [(0.08, 69, 0.63), (0.91, 72, 0.60), (1.77, 75, 0.96)]
reprise[6] = [(0.08, 72, 0.60), (0.87, 69, 0.65), (1.75, 66, 0.98)]
let melody = phraseA + phraseA2 + phraseB + reprise
let phraseDynamics = [0, -5, 1, 5, -1, -3, -4, -8]
let pickDynamics = [1, -8, -3, -9, 0, -7, -2, -8, -1, -9, -4, -10]

// Tie common string notes over adjacent bars instead of re-attacking every chord.
let stringPitches = Set(chords.flatMap { $0 }).sorted()
for pitch in stringPitches {
    var bar = 0
    while bar < 32 {
        if !chords[bar].contains(pitch) { bar += 1; continue }
        let first = bar
        while bar < 32 && chords[bar].contains(pitch) { bar += 1 }
        addNote(2, pitch, at: Double(first * 3) + 0.03,
            duration: Double((bar - first) * 3) - 0.10, velocity: first >= 24 ? 49 : 44)
    }
}
for bar in 0..<32 {
    let start = Double(bar * 3)
    let shade = phraseDynamics[bar % 8]
    let repriseLift = bar >= 24 ? 4 : 0
    // The downbeat and two quieter chord offbeats provide a gentle waltz pulse.
    addNote(4, bass[bar], at: start + 0.025, duration: 1.12, velocity: 57 + shade / 2)
    if bar % 4 == 2 {
        addNote(4, bass[bar] + 7, at: start + 2.03, duration: 0.60, velocity: 35)
    }
    for offbeat in 1...2 {
        for (voiceIndex, pitch) in chords[bar].enumerated() {
            addNote(3, pitch, at: start + Double(offbeat) + 0.045 + Double(voiceIndex) * 0.006,
                duration: offbeat == 1 ? 0.69 : 0.76, velocity: 39 + shade / 2 - offbeat * 2)
        }
    }
    // Alternating repeated strokes create a soft mandolin-like shimmer from nylon samples.
    for stroke in 0..<12 {
        let voice = stroke < 4 ? 1 : stroke < 8 ? 2 : 0
        let pitch = chords[bar][voice] + 12
        addNote(1, pitch, at: start + 0.105 + Double(stroke) * 0.24,
            duration: 0.17, velocity: 42 + pickDynamics[stroke] + shade / 3)
    }
    for (noteIndex, note) in melody[bar].enumerated() {
        let velocity = 61 + shade + repriseLift - noteIndex * 2
        if (16..<24).contains(bar) {
            // A picked melodic bridge answers the trumpet's A theme, not a new ambient pad.
            var stroke = 0
            while Double(stroke) * 0.22 < note.2 {
                let offset = Double(stroke) * 0.22
                addNote(5, note.1, at: start + note.0 + offset,
                    duration: min(0.16, note.2 - offset), velocity: velocity + (stroke % 2 == 0 ? 3 : -5))
                stroke += 1
            }
        } else {
            addNote(0, note.1, at: start + note.0, duration: note.2, velocity: velocity)
        }
    }
}
events.sort {
    if $0.frame == $1.frame { return !$0.isOn && $1.isOn }
    return $0.frame < $1.frame
}

let output = try AVAudioFile(forWriting: URL(fileURLWithPath: outputPath), settings: format.settings)
let buffer = AVAudioPCMBuffer(pcmFormat: engine.manualRenderingFormat,
    frameCapacity: engine.manualRenderingMaximumFrameCount)!
try engine.start()
var cursor: Int64 = 0
var eventIndex = 0
var transientFailures = 0
while cursor < totalFrames {
    while eventIndex < events.count && events[eventIndex].frame <= cursor {
        let event = events[eventIndex]
        let sampler = instruments[event.instrument].sampler
        if event.isOn { sampler.startNote(event.note, withVelocity: event.velocity, onChannel: 0) }
        else { sampler.stopNote(event.note, onChannel: 0) }
        eventIndex += 1
    }
    let nextEvent = eventIndex < events.count ? events[eventIndex].frame : totalFrames
    let wanted = min(Int64(buffer.frameCapacity), min(totalFrames, nextEvent) - cursor)
    if wanted <= 0 { continue }
    let status = try engine.renderOffline(AVAudioFrameCount(wanted), to: buffer)
    switch status {
    case .success:
        try output.write(from: buffer)
        cursor += Int64(buffer.frameLength)
        transientFailures = 0
    case .cannotDoInCurrentContext:
        transientFailures += 1
        if transientFailures > 100 { throw NSError(domain: "Soundtrack", code: 1, userInfo: [NSLocalizedDescriptionKey: "Offline renderer stalled"]) }
    case .insufficientDataFromInputNode, .error:
        throw NSError(domain: "Soundtrack", code: 2, userInfo: [NSLocalizedDescriptionKey: "Offline rendering failed: \(status)"])
    @unknown default:
        throw NSError(domain: "Soundtrack", code: 3)
    }
}
engine.stop()
print("Rendered \(outputPath): three cycles, \(framesPerLoop) frames per cycle, \(Double(framesPerLoop) / sampleRate) seconds each")
