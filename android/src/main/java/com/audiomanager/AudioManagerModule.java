package com.audiomanager;

import androidx.annotation.NonNull;
import androidx.annotation.MainThread;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.pm.PackageManager;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;

import androidx.mediarouter.media.MediaRouter;
import androidx.mediarouter.media.MediaRouter.RouteInfo;
import androidx.mediarouter.media.MediaRouter.Callback;
import androidx.mediarouter.media.MediaRouteSelector;
import androidx.mediarouter.media.MediaControlIntent;

import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.NativeModule;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.UiThreadUtil;
import com.facebook.react.module.annotations.ReactModule;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.lang.Runnable;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Timer;
import java.util.TimerTask;
import java.util.ArrayList;

@ReactModule(name = AudioManagerModule.NAME)
public class AudioManagerModule extends ReactContextBaseJavaModule implements AudioManager.OnAudioFocusChangeListener {
  public static final String NAME = "AudioManager";
  private static final String TAG = NAME;
  private static final String ROUTE_ADDED_EVENT_NAME = "onRouteAdded";
  private static final String ROUTE_REMOVED_EVENT_NAME = "onRouteRemoved";
  private static final String ROUTE_SELECTED_EVENT_NAME = "onRouteSelected";
  private static final String ROUTE_UNSELECTED_EVENT_NAME = "onRouteUnselected";
  private static final String DEVICE_CHANGED_EVENT_NAME = "onAudioDeviceChanged";
  private final static int HEADSET_PLUGGED = 1;
  private final static int HEADSET_UNPLUGGED = 0;

  // AudioAttributes
  private AudioAttributes mAudioAttributes;
  private AudioFocusRequest mAudioFocusRequest;

  // AudioRouter
  private AudioManager audioManager;

  // MediaRouter
  private MediaRouter mediaRouter;
  private MediaRouter.Callback mediaRouterCallback;

  // BluetoothReceiver
  private final BroadcastReceiver headsetReceiver;
  private final BluetoothProfile.ServiceListener bluetoothServiceListener;
  private BluetoothHeadset bluetoothHeadset;

  // Common variables
  private final ReactApplicationContext reactContext;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final int DEVICE_TYPE_BLUETOOTH = 3;
  private Timer scoStatusTimer;
  public enum AudioDevice { SPEAKER_PHONE, WIRED_HEADSET, EARPIECE, BLUETOOTH, NONE }


  public AudioManagerModule(ReactApplicationContext reactContext) {
      super(reactContext);
      this.reactContext = reactContext;

      audioManager = ((AudioManager) reactContext.getSystemService(Context.AUDIO_SERVICE));
      headsetReceiver = new HeadsetBroadcastReceiver();

      bluetoothServiceListener = new BluetoothServiceListener();
      BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
      bluetoothAdapter.getProfileProxy(reactContext, bluetoothServiceListener, BluetoothProfile.HEADSET);
      Log.d(TAG, TAG + "- initialized");
  }

  @Override
  @NonNull
  public String getName() {
      return NAME;
  }


  // REACT METHODS
  @ReactMethod
  public void multiply(double a, double b, Promise promise) {
      promise.resolve(a * b);
  }

  @ReactMethod
  public void start(Promise promise) {
      IntentFilter headsetFilter = new IntentFilter();
      headsetFilter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
      headsetFilter.addAction(Intent.ACTION_HEADSET_PLUG);
      reactContext.registerReceiver(headsetReceiver, headsetFilter);

      requestAudioFocus();

      audioManager.registerAudioDeviceCallback(audioDeviceCallback, null);
      audioManager.setMicrophoneMute(false);

      AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
      WritableMap data = Arguments.createMap();

      handler.post(() -> {
         mediaRouter = MediaRouter.getInstance(reactContext);
         mediaRouterCallback = new MediaRouterCallback();
         MediaRouteSelector mSelector = new MediaRouteSelector.Builder()
                         .addControlCategory(MediaControlIntent.CATEGORY_LIVE_AUDIO)
                         .build();
         mediaRouter.addCallback(mSelector, mediaRouterCallback);
      });

      data.putString("selectedDevice", getCurrentSelectedDevice());
      data.putArray("devices", createJSDevices(devices));

      scoStatusTimer = new Timer();
      TimerTask task = new TimerTask() {
          @Override
          public void run() {
              checkBluetoothDeviceTask();
          }
      };

      // scoStatusTimer.schedule(task, 0, 1000);
      promise.resolve(data);
  }

  @ReactMethod
  public void stop() {
      if (scoStatusTimer != null) {
          scoStatusTimer.cancel();
          scoStatusTimer = null;
      }

      abandonAudioFocus();

      audioManager.unregisterAudioDeviceCallback(audioDeviceCallback);
      reactContext.unregisterReceiver(bluetoothHeadsetReceiver);

      handler.post(() -> {
         mediaRouter.removeCallback(mediaRouterCallback);
      });
  }

  @ReactMethod
  public void chooseAudioRoute(String audioRoute, Promise promise) {
    Log.d(TAG, "USER CHOSEN AUDIO ROUTE" + audioRoute);

    setMode();
    requestAudioFocus();

    handler.post(() -> {
        setAudioRouteFromRoutes(audioRoute);
    });

    String currentSelectedDevice = getCurrentSelectedDevice();

    promise.resolve(currentSelectedDevice);
  }

  @ReactMethod
  public void getDevices(Promise promise) {
      AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
      promise.resolve(createJSDevices(devices));
  }

  @ReactMethod
  public void getRoutes(Promise promise) {
      List<RouteInfo> routes = mediaRouter.getRoutes();
      promise.resolve(createJSRoutes(routes));
  }

  @ReactMethod
  public void abandonAudioFocusJS(Promise promise) {
      promise.resolve(abandonAudioFocus());
  }

  @ReactMethod
  public void requestAudioFocusJS(Promise promise) {
      promise.resolve(requestAudioFocus());
  }

  @ReactMethod
  public void isWiredHeadsetPluggedIn(Promise promise) {
      promise.resolve(hasWiredHeadset());
  }

  // COMMON METHODS
  private void selectAudioRoute(MediaRouter.RouteInfo route, boolean isSpeakerPhone) {
      audioManager.setSpeakerphoneOn(isSpeakerPhone);
      route.select();
  }

  private void setMode() {
      audioManager.setMode(AudioManager.MODE_NORMAL);
  }

  @MainThread
  private void setAudioRouteFromRoutes(String audioRoute) {
      Log.d(TAG, "setAudioRouteFromRoutes route: " + audioRoute);
      List<RouteInfo> routes = mediaRouter.getRoutes();

      if (audioRoute.equals(AudioDevice.BLUETOOTH.name())) {
          RouteInfo bluetoothRoute = routes.stream()
              .filter(route -> route.isBluetooth())
              .findAny()
              .orElse(null);

          if (bluetoothRoute) {
              selectAudioRoute(bluetoothRoute, false);
          }
      } else if (audioRoute.equals(AudioDevice.SPEAKER_PHONE.name())) {
          RouteInfo speakerRoute = routes.stream()
              .filter(route -> route.isDeviceSpeaker())
              .findAny()
              .orElse(null);

          if (speakerRoute) {
              selectAudioRoute(speakerRoute, true);
          }
      }
  }

  private String getCurrentSelectedDevice() {
      boolean hasBluetooth = hasBluetoothDevices();
      Log.d(TAG, "BLUETOOTH has DEVICES: " + hasBluetooth);

      String currentRoute = AudioDevice.NONE.name();

      if (hasBluetoothDevices() && audioManager.isBluetoothScoOn()) {
        currentRoute = AudioDevice.BLUETOOTH.name();
      } else if (hasWiredHeadset()) {
        currentRoute = AudioDevice.WIRED_HEADSET.name();
      } else if (audioManager.isSpeakerphoneOn()) {
        currentRoute = AudioDevice.SPEAKER_PHONE.name();
      } else {
        currentRoute = AudioDevice.EARPIECE.name();
      }

      Log.d(TAG, "SELECTED ROUTE: " +  currentRoute);

      return currentRoute;
  }

  private boolean hasBluetoothDevices() {
      final AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_ALL);

      for (AudioDeviceInfo device : devices) {
          if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
              Log.d(TAG, "BluetoothHeadset: found");
              return true;
          }
      }

      return false;
  }

  private boolean hasWiredHeadset() {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
          return audioManager.isWiredHeadsetOn();
      } else {
          final AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_ALL);
          for (AudioDeviceInfo device : devices) {
              final int type = device.getType();
              if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
                  Log.d(TAG, "hasWiredHeadset: found wired headset");
                  return true;
              } else if (type == AudioDeviceInfo.TYPE_USB_DEVICE) {
                  Log.d(TAG, "hasWiredHeadset: found USB audio device");
                  return true;
              } else if (type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES) {
                  Log.d(TAG, "hasWiredHeadset: found wired headphones");
                  return true;
              }
          }
          return false;
      }
  }

  private String getCorrectDeviceType(int type) {
      if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES) {
          return AudioDevice.WIRED_HEADSET.name();
      } else if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
          return AudioDevice.BLUETOOTH.name();
      } else if (type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
          return AudioDevice.EARPIECE.name();
      } else if (type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
          return AudioDevice.SPEAKER_PHONE.name();
      } else  {
          return AudioDevice.NONE.name();
      }
  }

  public void chooseAudioRouteWithSco(String audioRoute) {
      audioManager.setSpeakerphoneOn(audioRoute.equals(AudioDevice.SPEAKER_PHONE.name()));
      setBluetoothScoOn(audioRoute.equals(AudioDevice.BLUETOOTH.name()));
  }

  private void logRouteInfo(String action, RouteInfo route) {
      Log.d(TAG, action
          + "NAME= " + route.getName()
          + ", DEVICE TYPE= " + route.getDeviceType()
          + ", isDefault= " + route.isDefault()
          + ", isBluetooth= " + route.isBluetooth()
          + ", isEnabled= " + route.isEnabled()
          + ", isDeviceSpeaker= " + route.isDeviceSpeaker()
          + ", isSelected= " + route.isSelected());
  }

  private void setBluetoothScoOn(boolean enabled) {
      if (enabled) {
          audioManager.startBluetoothSco();
          audioManager.setBluetoothScoOn(true);
      } else {
          audioManager.setBluetoothScoOn(false);
          audioManager.stopBluetoothSco();
      }
  }

  private String requestAudioFocus() {
      String requestAudioFocusResStr = (android.os.Build.VERSION.SDK_INT >= 26)
              ? requestAudioFocusV26()
              : requestAudioFocusOld();
      Log.d(TAG, "requestAudioFocus(): " + requestAudioFocusResStr);
      return requestAudioFocusResStr;
  }

  private String requestAudioFocusV26() {
      if (mAudioAttributes == null) {
         mAudioAttributes = new AudioAttributes.Builder()
                             .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                             .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                             .build();
      }

      if(mAudioFocusRequest == null) {
        mAudioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                                    .setAudioAttributes(mAudioAttributes)
                                    .setAcceptsDelayedFocusGain(false)
                                    .setOnAudioFocusChangeListener(this)
                                    .build();
      }

      int requestAudioFocusRes = audioManager.requestAudioFocus(mAudioFocusRequest);

      String requestAudioFocusResStr;
      switch (requestAudioFocusRes) {
          case AudioManager.AUDIOFOCUS_REQUEST_FAILED:
              requestAudioFocusResStr = "AUDIOFOCUS_REQUEST_FAILED";
              break;
          case AudioManager.AUDIOFOCUS_REQUEST_GRANTED:
              requestAudioFocusResStr = "AUDIOFOCUS_REQUEST_GRANTED";
              break;
          case AudioManager.AUDIOFOCUS_REQUEST_DELAYED:
              requestAudioFocusResStr = "AUDIOFOCUS_REQUEST_DELAYED";
              break;
          default:
              requestAudioFocusResStr = "AUDIOFOCUS_REQUEST_UNKNOWN";
              break;
      }

      return requestAudioFocusResStr;
  }

  private String requestAudioFocusOld() {
      int requestAudioFocusRes = audioManager.requestAudioFocus(this, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);

      String requestAudioFocusResStr;
      switch (requestAudioFocusRes) {
          case AudioManager.AUDIOFOCUS_REQUEST_FAILED:
              requestAudioFocusResStr = "AUDIOFOCUS_REQUEST_FAILED";
              break;
          case AudioManager.AUDIOFOCUS_REQUEST_GRANTED:
              requestAudioFocusResStr = "AUDIOFOCUS_REQUEST_GRANTED";
              break;
          default:
              requestAudioFocusResStr = "AUDIOFOCUS_REQUEST_UNKNOWN";
              break;
      }

      return requestAudioFocusResStr;
  }

  private String abandonAudioFocus() {
      String abandonAudioFocusResStr = (android.os.Build.VERSION.SDK_INT >= 26)
              ? abandonAudioFocusV26()
              : abandonAudioFocusOld();
      Log.d(TAG, "abandonAudioFocus(): " + abandonAudioFocusResStr);
      return abandonAudioFocusResStr;
  }

  private String abandonAudioFocusV26() {
      int abandonAudioFocusRes = audioManager.abandonAudioFocusRequest(mAudioFocusRequest);

      String abandonAudioFocusResStr;

      switch (abandonAudioFocusRes) {
          case AudioManager.AUDIOFOCUS_REQUEST_FAILED:
              abandonAudioFocusResStr = "AUDIOFOCUS_REQUEST_FAILED";
              break;
          case AudioManager.AUDIOFOCUS_REQUEST_GRANTED:
              abandonAudioFocusResStr = "AUDIOFOCUS_REQUEST_GRANTED";
              break;
          default:
              abandonAudioFocusResStr = "AUDIOFOCUS_REQUEST_UNKNOWN";
              break;
      }

      return abandonAudioFocusResStr;
  }

  private String abandonAudioFocusOld() {
      int abandonAudioFocusRes = audioManager.abandonAudioFocus(this);

      String abandonAudioFocusResStr;
      switch (abandonAudioFocusRes) {
          case AudioManager.AUDIOFOCUS_REQUEST_FAILED:
              abandonAudioFocusResStr = "AUDIOFOCUS_REQUEST_FAILED";
              break;
          case AudioManager.AUDIOFOCUS_REQUEST_GRANTED:
              abandonAudioFocusResStr = "AUDIOFOCUS_REQUEST_GRANTED";
              break;
          default:
              abandonAudioFocusResStr = "AUDIOFOCUS_REQUEST_UNKNOWN";
              break;
      }

      return abandonAudioFocusResStr;
  }

  private void checkBluetoothDeviceTask() {
      boolean state = audioManager.isBluetoothScoOn();

      if (bluetoothHeadset != null) {
         List<BluetoothDevice> devices = bluetoothHeadset.getConnectedDevices();

         if (!devices.isEmpty()) {
             BluetoothDevice bluetoothDevice = devices.get(0);
             if (bluetoothDevice != null) {
               Log.d(TAG, "Connected bluetooth headset: "
               + "name=" + bluetoothDevice.getName() + ", "
               + "state=" + bluetoothHeadset.getConnectionState(bluetoothDevice)
               + ", SCO audio=" + bluetoothHeadset.isAudioConnected(bluetoothDevice));
             } else {
               Log.d(TAG, "bluetoothDevice is NULL");
             }
         }
      }

      Log.d(TAG, "isBluetoothScoOn: " + state);
  }

  // REACT UTILITY METHODS
  private void emitEvent(String eventName,  Object data) {
    executor.execute(() -> {
      if (reactContext != null) {
      Log.d(TAG, "SEND EVENT IN REACT: " + eventName);
          reactContext
              .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
              .emit(eventName, data);
      }
    });
  }

  private WritableArray createJSDevices(AudioDeviceInfo[] devices) {
      WritableArray allDeviceInfos = Arguments.createArray();

      Set<Integer> checks = Set.of(
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET
      );

      for (AudioDeviceInfo device : devices) {
          if (checks.contains(device.getType())) {
            allDeviceInfos.pushMap(createJSDeviceObject(device));
          }
      }

      return allDeviceInfos;
  }

  private WritableArray createJSRoutes(List<RouteInfo> routes) {
       WritableArray allRouteInfos = Arguments.createArray();

       for (RouteInfo route : routes) {
          allRouteInfos.pushMap(createJSRouteObject(route));
       }
  }

  private WritableMap createJSRouteObject(RouteInfo route) {
    WritableMap routeInfo = Arguments.createMap();
    int originType = route.getDeviceType();
    String routeType = AudioDevice.NONE.name();

    if (originType == DEVICE_TYPE_BLUETOOTH) {
      routeType = AudioDevice.BLUETOOTH.name();
    } else if (originType == RouteInfo.DEVICE_TYPE_SPEAKER) {
      routeType = AudioDevice.SPEAKER_PHONE.name();
    }

    routeInfo.putString("name", route.getName());
    routeInfo.putString("type", routeType);
    routeInfo.putBoolean("isSelected", route.isSelected());

    return routeInfo;
  }

  private WritableMap createJSDeviceObject(AudioDeviceInfo device) {
      final int type = device.getType();
      final int id = device.getId();
      final String name = device.getProductName().toString();
      WritableMap deviceInfo = Arguments.createMap();

      deviceInfo.putString("type", getCorrectDeviceType(type));
      deviceInfo.putString("name", name);
      deviceInfo.putInt("id", id);

      return deviceInfo;
  }


  // CLASSES, RUNNABLES, TIMERS
  private class MediaRouterCallback extends MediaRouter.Callback {
      @Override
      public void onRouteAdded(MediaRouter router, RouteInfo route) {
          logRouteInfo("MediaRouterCallback onRouteAdded: ", route);
          emitEvent(ROUTE_ADDED_EVENT_NAME, createJSRouteObject(route));

          if (route.getDeviceType() == DEVICE_TYPE_BLUETOOTH) {
              setMode();
              requestAudioFocus();
              selectAudioRoute(route, false);
          }
      }

      @Override
      public void onRouteRemoved(MediaRouter router, RouteInfo route) {
          logRouteInfo("MediaRouterCallback onRouteRemoved: ", route);
          emitEvent(ROUTE_REMOVED_EVENT_NAME, createJSRouteObject(route));

          if (route.getDeviceType() == DEVICE_TYPE_BLUETOOTH && !hasWiredHeadset()) {
              setMode();
              requestAudioFocus();
              audioManager.setSpeakerphoneOn(true);
          }
      }

      @Override
      public void onRouteSelected(MediaRouter router, RouteInfo route) {
          logRouteInfo("MediaRouterCallback onRouteSelected: ", route);
          emitEvent(ROUTE_SELECTED_EVENT_NAME, createJSRouteObject(route));
      }

      @Override
      public void onRouteUnselected(MediaRouter router, RouteInfo route) {
          logRouteInfo("MediaRouterCallback onRouteUnselected: ", route);
          emitEvent(ROUTE_UNSELECTED_EVENT_NAME, createJSRouteObject(route));
      }
  }

  private class BluetoothServiceListener implements BluetoothProfile.ServiceListener {
      @Override
      public void onServiceConnected(int profile, BluetoothProfile proxy) {
          if (profile == BluetoothProfile.HEADSET && bluetoothHeadset == null) {
             Log.d(TAG, "BluetoothServiceListener.onServiceConnected");
             bluetoothHeadset = (BluetoothHeadset) proxy;
          }
      }

      @Override
      public void onServiceDisconnected(int profile) {
          if (profile == BluetoothProfile.HEADSET && bluetoothHeadset != null) {
              Log.d(TAG, "BluetoothServiceListener.onServiceDisconnected");
              bluetoothHeadset = null;
          }
      }
  }

  private class HeadsetBroadcastReceiver extends BroadcastReceiver {
      @Override
      public void onReceive(Context context, Intent intent) {
          final String action = intent.getAction();

          if (action.equals(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)) {
              Log.d(TAG, "ACTION  : " + "BluetoothHeadset - ACTION_CONNECTION_STATE_CHANGED");
              final int state = intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, BluetoothHeadset.STATE_DISCONNECTED);

              if (state == BluetoothHeadset.STATE_CONNECTED) {
                Log.d(TAG, "BT STATE : " + "--STATE_CONNECTED");
              } else if (state == BluetoothHeadset.STATE_DISCONNECTED) {
                Log.d(TAG, "BT STATE : " + "--STATE_DISCONNECTED");
              }
          } else if (action.equals(Intent.ACTION_HEADSET_PLUG)) {
              Log.d(TAG, "ACTION  : " + "WeiredHeadset - ACTION_HEADSET_PLUG");
              final int state = intent.getIntExtra("state", -1);

              if (state == HEADSET_PLUGGED) {
                Log.d(TAG, "ACTION  : " + "WeiredHeadset - HEADSET_PLUGGED");
              } else if (state == HEADSET_UNPLUGGED) {
                Log.d(TAG, "ACTION  : " + "WeiredHeadset - HEADSET_UNPLUGGED");
              }
          }
      }
  }

  private final android.media.AudioDeviceCallback audioDeviceCallback =
      new android.media.AudioDeviceCallback() {
          @Override
          public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
              executor.execute(onAudioDeviceChangeRunner);
          }

          @Override
          public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
              executor.execute(onAudioDeviceChangeRunner);
          }
      };

  private final Runnable onAudioDeviceChangeRunner = new Runnable() {
      @Override
      public void run() {
          AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);

          WritableArray devicesMap = createJSDevices(devices);
          Log.d(TAG, "DEVICE UPDATED" + devicesMap);
          emitEvent(DEVICE_CHANGED_EVENT_NAME, devicesMap);
      }
  };

  @Override
  public void onAudioFocusChange(int focusChange) {
      String focusChangeStr;
      switch (focusChange) {
          case AudioManager.AUDIOFOCUS_GAIN:
              focusChangeStr = "AUDIOFOCUS_GAIN";
              break;
          case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT:
              focusChangeStr = "AUDIOFOCUS_GAIN_TRANSIENT";
              break;
          case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE:
              focusChangeStr = "AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE";
              break;
          case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK:
              focusChangeStr = "AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK";
              break;
          case AudioManager.AUDIOFOCUS_LOSS:
              focusChangeStr = "AUDIOFOCUS_LOSS";
              break;
          case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
              focusChangeStr = "AUDIOFOCUS_LOSS_TRANSIENT";
              break;
          case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
              focusChangeStr = "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK";
              break;
          case AudioManager.AUDIOFOCUS_NONE:
              focusChangeStr = "AUDIOFOCUS_NONE";
              break;
          default:
              focusChangeStr = "AUDIOFOCUS_UNKNOWN";
              break;
      }

      Log.d(TAG, "AUDIO FOCUS CHANGED: " + focusChange + " - " + focusChangeStr);
  }
}
