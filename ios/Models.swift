//
//  Models.swift
//  react-native-audio-manager
//
//  Created by Oleg Efimov on 24.11.2022.
//

import Foundation
import AVFoundation

enum JSOutputType: String, Codable {
    case EARPIECE, SPEAKER_PHONE, BLUETOOTH, WIRED_HEADSET
}

struct OutputDevice {
    let id: String
    let name: String
    let port: AVAudioSessionPortDescription
}

struct AudioSessionPreferences {
    let category: AVAudioSession.Category
    let options: AVAudioSession.CategoryOptions
    let mode: AVAudioSession.Mode
}

struct RouteInfo: Codable {
    let id: String
    let name: String
    let type: JSOutputType
    let isSelected: Bool
}

struct DeviceInfo: Codable {
    let id: String
    let name: String
    let type: JSOutputType
}
