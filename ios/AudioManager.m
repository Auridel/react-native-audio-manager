#import <React/RCTBridgeModule.h>
#import <React/RCTEventEmitter.h>

@interface RCT_EXTERN_MODULE(AudioManager, RCTEventEmitter)

RCT_EXTERN_METHOD(start)

RCT_EXTERN_METHOD(stop)

RCT_EXTERN_METHOD(chooseAudioRoute:(NSString)device)

RCT_EXTERN_METHOD(supportedEvents)

RCT_EXTERN_METHOD(getDevices:(RCTPromiseResolveBlock *) resolve
                  rejecter:(RCTPromiseRejectBlock *) reject)

+ (BOOL)requiresMainQueueSetup
{
  return NO;
}

@end
