import {
  EmitterSubscription,
  NativeEventEmitter,
  NativeModules,
  Platform,
} from 'react-native';

const LINKING_ERROR =
  `The package 'react-native-audio-manager-ios' doesn't seem to be linked. Make sure: \n\n` +
  Platform.select({ ios: "- You have run 'pod install'\n", default: '' }) +
  '- You rebuilt the app after installing the package\n' +
  '- You are not using Expo Go\n';

const AudioManagerModule = NativeModules.AudioManager
  ? NativeModules.AudioManager
  : new Proxy(
      {},
      {
        get() {
          throw new Error(LINKING_ERROR);
        },
      }
    );

const AudioManagerEmitter = new NativeEventEmitter(AudioManagerModule);

export interface IRouteInfo {
  name: string;
  type: TAudioRoute;
  isSelected: boolean;
}

export interface IDeviceInfo {
  id: string;
  name: string;
  type: TAudioRoute;
}

export type TAudioRoute =
  | 'EARPIECE'
  | 'SPEAKER_PHONE'
  | 'BLUETOOTH'
  | 'WIRED_HEADSET';

export type TEventListenerActionData = {
  onRouteAdded: IRouteInfo;
  onRouteRemoved: IRouteInfo;
  onRouteSelected: IRouteInfo;
  onRouteUnselected: IRouteInfo;
  onAudioDeviceChanged: IDeviceInfo[];
};

class AudioManagerService {
  private isAndroid = Platform.OS === 'android';
  private subscriptions: Array<{
    action: keyof TEventListenerActionData;
    subscription: EmitterSubscription;
    callback: (
      data: TEventListenerActionData[keyof TEventListenerActionData]
    ) => void;
  }> = [];

  /**
   * @description Start AudioManager service
   */
  public start() {
    AudioManagerModule.start();
  }

  /**
   * @description Stop AudioManager service
   */
  public stop() {
    AudioManagerModule.stop();
  }

  /**
   * @param route TAudioRoute
   * @description Only Android Platform
   */
  public chooseAudioRoute(route: TAudioRoute) {
    AudioManagerModule.chooseAudioRoute(route);
  }

  /**
   * @description Only Android Platform
   * @return List audio routes from system
   */
  public async getRoutes() {
    if (this.isAndroid) {
      return (await AudioManagerModule.getRoutes()) as IRouteInfo[];
    }

    return [];
  }

  /**
   * @description Only Android Platform
   * @return List audio devices from system
   */
  public async getDevices() {
    if (this.isAndroid) {
      return (await AudioManagerModule.getDevices()) as IDeviceInfo[];
    }

    return [];
  }

  /**
   * @param action
   * @param callback
   * @description Only Android Platform
   */
  public addEventListener<
    K extends keyof TEventListenerActionData = keyof TEventListenerActionData
  >(action: K, callback: (data: TEventListenerActionData[K]) => void) {
    const subscription = AudioManagerEmitter.addListener(
      action as string,
      callback
    );
    this.subscriptions.push({
      action,
      subscription,
      callback: callback as (
        data: TEventListenerActionData[keyof TEventListenerActionData]
      ) => void,
    });
  }

  /**
   * @param callback
   * @description Only Android Platform
   */
  public removeEventListener(
    callback: (
      data: TEventListenerActionData[keyof TEventListenerActionData]
    ) => void
  ) {
    const result = this.subscriptions.find((el) => el.callback === callback);

    if (result) {
      AudioManagerEmitter.removeSubscription(result.subscription);
    }
  }
}

export const AudioManager = new AudioManagerService();
