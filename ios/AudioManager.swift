//
//  AudioManager.swift
//  AudioManager
//
//  Created by Oleg Efimov on 24.11.2022.
//

import AVFoundation

protocol AudioManagerProtocol {
    func start()
    func stop()
    func chooseAudioRoute(_ device: String)
}

@objc(AudioManager)
class AudioManager: NSObject, AudioManagerProtocol {

    enum OutputDeviceType: String {
        case earpiece = "EARPIECE", speaker = "SPEAKER_PHONE", bluetooth = "BLUETOOTH"
    }
    
    private let debugTag = "AudioManager DEBUG: "
    private let headsetPorts: [AVAudioSession.Port] = [
        .bluetoothA2DP, .bluetoothHFP, .headphones, .bluetoothLE
    ]
    
    private let audioSession = AVAudioSession.sharedInstance()
    
    private var isActive = false
    private var availableDevices = [OutputDevice]()
    private var preferredDevice: OutputDeviceType? = .bluetooth {
        didSet {
            guard let device = preferredDevice,
                  isActive
            else {
                setCategoryPreferences(baseOptions, true)
                return;
            }
            
            let options = getSessionConfigurationByType(for: device)
            setCategoryPreferences(options, true)
        }
    }
    
    
    //Original config
    private var originalOptions: AudioSessionPreferences
    //base options
    private let baseOptions = AudioSessionPreferences(category: .playAndRecord,
                                                      options: [
                                                        .allowBluetooth,
                                                        .allowBluetoothA2DP,
                                                        .defaultToSpeaker,
                                                        .mixWithOthers
                                                      ],
                                                      mode: .videoChat)
    //speakerOptions
    private let speakerOptions = AudioSessionPreferences(category: .playAndRecord,
                                                         options: [.defaultToSpeaker, .mixWithOthers],
                                                         mode: .videoChat)
    //earpieceOptions
    private let earpieceOptions = AudioSessionPreferences(category: .playAndRecord,
                                                            options: [.mixWithOthers],
                                                            mode: .voiceChat)
    
    
    override init() {
        self.originalOptions = AudioSessionPreferences(
            category: audioSession.category,
            options: audioSession.categoryOptions,
            mode: audioSession.mode)
        
        super.init()
    }
    
    @objc func start() {
        isActive = true
        saveOriginalMode()
        
        NotificationCenter.default.addObserver(self,
                                               selector: #selector(routeChangeHandler(notification:)),
                                               name: AVAudioSession.routeChangeNotification,
                                               object: nil)
        NotificationCenter.default.addObserver(self,
                                               selector: #selector(audioInterruptionHandler(notification:)),
                                               name: AVAudioSession.interruptionNotification,
                                               object: nil)
        NotificationCenter.default.addObserver(self,
                                               selector: #selector(silenceSecondaryAudioHintHandler(notification:)),
                                               name: AVAudioSession.silenceSecondaryAudioHintNotification,
                                               object: nil)
        
        setCategoryPreferences(baseOptions, true)
    }
    
    @objc func stop() {
        isActive = false
        
        NotificationCenter.default.removeObserver(self)
        
        setCategoryPreferences(originalOptions, false)
        preferredDevice = .bluetooth
    }
    
    @objc func chooseAudioRoute(_ device: String) {
        guard let deviceType = OutputDeviceType(rawValue: device)
        else {
            return
        }
        
        if preferredDevice == .speaker || deviceType == .speaker {
            do {
                try audioSession.overrideOutputAudioPort(deviceType == .speaker ? .speaker : .none)
            } catch let error {
                printLogs(log: "overrideOutputAudioPort failure \(error.localizedDescription)")
            }
        }
        
        preferredDevice = deviceType
    }
    
    //TODO: add devices list
    //    func getAccessories() {
    //        let inputs = audioSession.availableInputs
    //
    //        guard let res = inputs
    //        else { return }
    //
    //        //TODO: Get devices through inputs loop
    //        for route in res {
    //            printLogs(log: "output \(route.portName)")//LOCALIZED NAME
    //            printLogs(log: "output \(route.portType)")//AVAudioSessionPort(_rawValue: Speaker)
    //            printLogs(log: "output \(route.uid)")//SPEAKER
    //            printLogs(log: "output \(route.description)")
    //        }
    //
    //    }
    
    //MARK: handlers
    
    @objc private func routeChangeHandler(notification: Notification) {
        printLogs(log: "routeChangeHandler \(notification)")
        
        guard let userInfo = notification.userInfo,
              let reasonValue = userInfo[AVAudioSessionRouteChangeReasonKey] as? UInt,
              let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue)
        else {
            return
        }
        
        switch reason {
        case .unknown:
            printLogs(log: "Audio route changed with reason: unknown")
        case .newDeviceAvailable:
            printLogs(log: "Audio route changed with reason: newDeviceAvailable")
        case .oldDeviceUnavailable:
            printLogs(log: "Audio route changed with reason: oldDeviceUnavailable")
        case .categoryChange:
            printLogs(log: "Audio route changed with reason: categoryChange")
        case .override:
            printLogs(log: "Audio route changed with reason: override")
        case .wakeFromSleep:
            printLogs(log: "Audio route changed with reason: wakeFromSleep")
        case .noSuitableRouteForCategory:
            printLogs(log: "Audio route changed with reason: noSuitableRouteForCategory")
        case .routeConfigurationChange:
            printLogs(log: "Audio route changed with reason: routeConfigurationChange")
        @unknown default:
            printLogs(log: "Audio route changed with reason: unknown default")
        }
        
        handleRouteChange()
    }
    
    @objc private func audioInterruptionHandler(notification: Notification) {
        printLogs(log: "audioInterruptionHandler \(notification)")
    }
    
    @objc private func silenceSecondaryAudioHintHandler(notification: Notification) {
        printLogs(log: "silenceSecondaryAudioHintHandler \(notification)")
    }
    
    //MARK: Private
    private func printLogs(log: String) {
        print("\(debugTag) \(log)")
    }
    
    private func saveOriginalMode() {
        originalOptions = AudioSessionPreferences(
            category: audioSession.category,
            options: audioSession.categoryOptions,
            mode: audioSession.mode)
    }
    
    private func handleRouteChange() {
        guard let device = preferredDevice
        else { return }
        
        let currentOptions = getSessionConfigurationByType(for: device)
        handleAudioSessionChange(with: currentOptions)
    }
    
    private func handleAudioSessionChange(with preferredOptions: AudioSessionPreferences) {
        let newCategory = audioSession.category
        let newRoute = audioSession.currentRoute
        let newMode = audioSession.mode
        let newOptions = audioSession.categoryOptions
        let currentOutput = newRoute.outputs.first
        
        printLogs(log: "category \(newCategory.rawValue)")
        printLogs(log: "desired category \(preferredOptions.category)")
        printLogs(log: "mode \(newMode)")
        printLogs(log: "desired mode \(preferredOptions.mode)")
        printLogs(log: "options \(audioSession.categoryOptions)")
        printLogs(log: "desired options \(preferredOptions.options)")
        
        printLogs(log: "current output \(String(describing: currentOutput?.portName))")
        
        if newCategory != preferredOptions.category || newMode != preferredOptions.mode || newOptions != preferredOptions.options {
            setCategoryPreferences(preferredOptions, true)
        }
    }
    
    private func setCategoryPreferences(_ options: AudioSessionPreferences, _ setActive: Bool?) {
        do {
            try audioSession.setCategory(options.category,
                                         mode: options.mode,
                                         options: options.options)
            if setActive == true {
                try audioSession.setActive(true)
            } else if setActive == false {
                try audioSession.setActive(false, options: [.notifyOthersOnDeactivation])
            }
        } catch let error {
            printLogs(log: "start error \(error.localizedDescription)")
        }
    }
    
    private func getSessionConfigurationByType(for deviceType: OutputDeviceType) -> AudioSessionPreferences {
        switch deviceType {
        case .earpiece:
            return earpieceOptions
        case .speaker:
            return speakerOptions
        case .bluetooth:
            return baseOptions
        }
    }
}

