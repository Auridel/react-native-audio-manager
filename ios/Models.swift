//
//  Models.swift
//  AudioManager
//
//  Created by Oleg Efimov on 24.11.2022.
//

import AVFoundation

struct OutputDevice {
    let name: String
    let port: AVAudioSessionPortDescription
    let uid: String
    let description: AVAudioSessionPortDescription
}

struct AudioSessionPreferences {
    let category: AVAudioSession.Category
    let options: AVAudioSession.CategoryOptions
    let mode: AVAudioSession.Mode
}
