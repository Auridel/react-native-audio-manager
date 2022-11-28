# react-native-audio-manager-ios

iOS audio routes manager for React Native

## Installation

```sh
npm install react-native-audio-manager
```

## Usage

```js
import { AudioManager } from 'react-native-audio-manager';

// Start service and intercept audio settings change from other apps

AudioManager.start()

// Set preffered output device
//device: TPreferredDeviceType = 'EARPIECE' | 'SPEAKER_PHONE' | 'BLUETOOTH';

AudioManager.setPreferredDevice(device);

// Stop service and set original audio settings

AudioManager.stop()
```

## Contributing

See the [contributing guide](CONTRIBUTING.md) to learn how to contribute to the repository and the development workflow.

## License

MIT
