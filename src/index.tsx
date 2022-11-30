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
  id: string;
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
    this.removeAllListeners();
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
   * @return List audio devices from system
   */
  public async getDevices() {
    return (await AudioManagerModule.getDevices()) as IDeviceInfo[];
  }

  /**
   * @param action
   * @param callback
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

  /**
   * @description Remove all listeners
   */
  removeAllListeners(): void;
  /**
   * @param event
   * @description Remove all listeners from event
   */
  removeAllListeners(event: keyof TEventListenerActionData): void;
  public removeAllListeners(event?: keyof TEventListenerActionData) {
    if (event) {
      this.subscriptions.filter(({ action }) => action !== event);
      AudioManagerEmitter.removeAllListeners(event);
    } else {
      this.subscriptions.forEach(({ subscription }) => {
        AudioManagerEmitter.removeSubscription(subscription);
      });

      this.subscriptions = [];
    }
  }
}

export const AudioManager = new AudioManagerService();
