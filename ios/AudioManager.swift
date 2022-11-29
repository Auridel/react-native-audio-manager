//
//  AudioManager.swift
//  react-native-audio-manager
//
//  Created by Oleg Efimov on 24.11.2022.
//

import React
import AVFoundation

protocol AudioManagerProtocol {
    func start()
    func stop()
    func chooseAudioRoute(_ device: String)
}

@objc(AudioManager)
class AudioManager: RCTEventEmitter, AudioManagerProtocol  {
    
    enum OutputDeviceType: String {
        case earpiece = "EARPIECE", speaker = "SPEAKER_PHONE", bluetooth = "BLUETOOTH"
    }
    enum JSEvents: String {
        case onRouteAdded, onRouteRemoved, onRouteSelected, onRouteUnselected, onAudioDeviceChanged
    }
    
    private let debugTag = "AudioManager DEBUG: "
    private let headsetPorts: [AVAudioSession.Port] = [
        .bluetoothA2DP, .bluetoothHFP, .headphones, .bluetoothLE
    ]
    private let baseDevices: [DeviceInfo] = [
        DeviceInfo(
            id: UUID().uuidString,
            name: JSOutputType.EARPIECE.rawValue,
            type: .EARPIECE),
        DeviceInfo(
            id: UUID().uuidString,
            name: JSOutputType.SPEAKER_PHONE.rawValue,
            type: .SPEAKER_PHONE),
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
    private var connectedDevices = [OutputDevice]()
    
    
    //Original config
    private var originalOptions: AudioSessionPreferences
    //base options
    private let baseOptions = AudioSessionPreferences(
        category: .playAndRecord,
        options: [
            .allowBluetooth,
            .allowBluetoothA2DP,
            .defaultToSpeaker,
            .mixWithOthers
        ],
        mode: .videoChat)
    //speakerOptions
    private let speakerOptions = AudioSessionPreferences(
        category: .playAndRecord,
        options: [.defaultToSpeaker, .mixWithOthers],
        mode: .videoChat)
    //earpieceOptions
    private let earpieceOptions = AudioSessionPreferences(
        category: .playAndRecord,
        options: [.mixWithOthers],
        mode: .voiceChat)
    
    
    override init() {
        self.originalOptions = AudioSessionPreferences(
            category: audioSession.category,
            options: audioSession.categoryOptions,
            mode: audioSession.mode)
        
        super.init()
    }
    
    deinit {
        if isActive {
            stop()
        }
    }
    
    override func supportedEvents() -> [String]! {
        return ["onRouteAdded",
                "onRouteRemoved",
                "onRouteSelected",
                "onRouteUnselected",
                "onAudioDeviceChanged"]
    }
    
    @objc func start() {
        isActive = true
        saveOriginalMode()
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(routeChangeHandler(notification:)),
            name: AVAudioSession.routeChangeNotification,
            object: nil)
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(audioInterruptionHandler(notification:)),
            name: AVAudioSession.interruptionNotification,
            object: nil)
        NotificationCenter.default.addObserver(
            self,
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
    
    @objc(getDevices:rejecter:)
    func getDevices(_ resolve: @escaping RCTPromiseResolveBlock,
                    rejecter reject: @escaping RCTPromiseRejectBlock) {
        let outputDevices = getAudioDevices()
        
        resolve(outputDevices.map({$0.toPlainObject()}))
    }
    
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
            
            if let device = audioSession.currentRoute.outputs.first {
                handleDeviceAdded(device: device)
                
                if let prevDevice = userInfo[AVAudioSessionRouteChangePreviousRouteKey] as? AVAudioSessionRouteDescription,
                   let prevPort = prevDevice.outputs.first
                {
                    handleRouteSelected(prevPort: prevPort)
                    handleDevicesChanged()
                }
            }
        case .oldDeviceUnavailable:
            printLogs(log: "Audio route changed with reason: oldDeviceUnavailable")
            
            if let device = userInfo[AVAudioSessionRouteChangePreviousRouteKey] as? AVAudioSessionRouteDescription,
               let port = device.outputs.first
            {
                handleDeviceRemoved(device: port)
                handleRouteSelected(prevPort: port)
                handleDevicesChanged()
            }
        case .categoryChange:
            printLogs(log: "Audio route changed with reason: categoryChange")
        case .override:
            printLogs(log: "Audio route changed with reason: override")
            if let device = userInfo[AVAudioSessionRouteChangePreviousRouteKey] as? AVAudioSessionRouteDescription,
               let port = device.outputs.first
            {
                handleRouteSelected(prevPort: port)
            }
            handleDevicesChanged()
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
    
    private func getAudioDevices() -> [DeviceInfo] {
        guard let availableInputs = audioSession.availableInputs
        else { return baseDevices }
        
        connectedDevices = []
        
        for input in availableInputs {
            if headsetPorts.contains(input.portType) {
                if input.portType == .headphones {
                    connectedDevices.append(OutputDevice(
                        id: input.uid,
                        name: input.portName,
                        port: input))
                } else {
                    connectedDevices.append(OutputDevice(
                        id: input.uid,
                        name: input.portName,
                        port: input))
                }
            }
        }
        
        let availableExternalDevices = connectedDevices.map {
            DeviceInfo(id: $0.id,
                       name: $0.name,
                       type: $0.port.portType == .headphones ? .WIRED_HEADSET : .BLUETOOTH)
        }
        let outputDevices = baseDevices + availableExternalDevices
        
        printLogs(log: "get devices \(outputDevices)")
        
        return outputDevices
    }
    
    private func getJSPortType(_ output: AVAudioSessionPortDescription) -> JSOutputType? {
        if output.portType == .builtInSpeaker {
            return .SPEAKER_PHONE
        } else if output.portType == .builtInReceiver {
            return .EARPIECE
        } else if headsetPorts.contains(output.portType) {
            if output.portType == .headphones {
                return .WIRED_HEADSET
            } else {
                return .BLUETOOTH
            }
        }
        
        return nil
    }
    
    private func handleDevicesChanged() {
        let outputDevices = getAudioDevices()
        
        sendEvent(withName: JSEvents.onAudioDeviceChanged.rawValue,
                  body: outputDevices.map({$0.toPlainObject()}))
    }
    
    private func handleDeviceAdded(device: AVAudioSessionPortDescription) {
        guard let jsOutputType = getJSPortType(device)
        else { return }
        let jsDeviceData = RouteInfo(
            id: device.uid,
            name: device.portName,
            type: jsOutputType,
            isSelected: audioSession.currentRoute.outputs.first?.uid == device.uid)
        
        sendEvent(withName: JSEvents.onRouteAdded.rawValue,
                  body: jsDeviceData.toPlainObject())
    }
    
    private func handleDeviceRemoved(device: AVAudioSessionPortDescription) {
        guard let jsOutputType = getJSPortType(device)
        else { return }
        
        let jsDeviceData = RouteInfo(
            id: device.uid,
            name: device.portName,
            type: jsOutputType,
            isSelected: audioSession.currentRoute.outputs.first?.uid == device.uid)
        
        sendEvent(withName: JSEvents.onRouteRemoved.rawValue,
                  body: jsDeviceData.toPlainObject())
    }
    
    private func handleRouteSelected(prevPort: AVAudioSessionPortDescription) {
        guard let currentOutputPort = audioSession.currentRoute.outputs.first,
              prevPort.uid != currentOutputPort.uid,
              let jsCurrentOutputType = getJSPortType(currentOutputPort),
              let jsPrevOutputType = getJSPortType(prevPort)
        else { return }
        
        
        let jsPrevDeviceData = RouteInfo(
            id: prevPort.uid,
            name: prevPort.portName,
            type: jsPrevOutputType,
            isSelected: audioSession.currentRoute.outputs.first?.uid == prevPort.uid)
        let jsCurrentDeviceData = RouteInfo(
            id: currentOutputPort.uid,
            name: currentOutputPort.portName,
            type: jsCurrentOutputType,
            isSelected: audioSession.currentRoute.outputs.first?.uid == currentOutputPort.uid)
        
        sendEvent(withName: JSEvents.onRouteSelected.rawValue,
                  body: jsCurrentDeviceData.toPlainObject())
        sendEvent(withName: JSEvents.onRouteUnselected.rawValue,
                  body: jsPrevDeviceData.toPlainObject())
    }
}
