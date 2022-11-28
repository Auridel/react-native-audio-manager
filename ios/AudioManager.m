#import <React/RCTBridgeModule.h>

@interface RCT_EXTERN_MODULE(AudioManager, NSObject)

RCT_EXTERN_METHOD(start)

RCT_EXTERN_METHOD(stop)

RCT_EXTERN_METHOD(setPreferredDevice:(NSString)device)

+ (BOOL)requiresMainQueueSetup
{
  return NO;
}

@end
