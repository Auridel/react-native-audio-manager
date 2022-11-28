import { NativeModules, Platform } from 'react-native';

const LINKING_ERROR =
  `The package 'react-native-audio-manager-ios' doesn't seem to be linked. Make sure: \n\n` +
  Platform.select({ ios: "- You have run 'pod install'\n", default: '' }) +
  '- You rebuilt the app after installing the package\n' +
  '- You are not using Expo Go\n';

const AudioManagerIosModule = NativeModules.AudioManagerIos
  ? NativeModules.AudioManagerIos
  : new Proxy(
      {},
      {
        get() {
          throw new Error(LINKING_ERROR);
        },
      }
    );

//TODO: implement decorators
// function platformGuard(_: unknown, __: string, descriptor: PropertyDescriptor) {
//   const method = descriptor.value!;

//   descriptor.value = function (...args: []) {
//     if (Platform.OS === 'ios') {
//       return method.apply(AudioManagerIos, args);
//     }
//     return Promise.resolve();
//   };
// }

export type TPreferredDeviceType = 'EARPIECE' | 'SPEAKER_PHONE' | 'BLUETOOTH';

export class AudioManagerIos {
  private static isIos = Platform.OS === 'ios';

  // @platformGuard
  public static start() {
    if (!this.isIos) {
      console.warn('Android devices are not supported');

      return;
    }
    AudioManagerIosModule.start();
  }

  // @platformGuard
  public static stop() {
    if (!this.isIos) {
      console.warn('Android devices are not supported');

      return;
    }
    AudioManagerIosModule.stop();
  }

  // @platformGuard
  public static setPreferredDevice(device: TPreferredDeviceType) {
    if (!this.isIos) {
      console.warn('Android devices are not supported');

      return;
    }
    AudioManagerIosModule.setPreferredDevice(device);
  }
}
