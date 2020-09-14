// MIT License
//
// Copyright (c) [2019] [Befovy]
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

#import "FijkPlayer.h"
#import "FijkHostOption.h"
#import "FijkPlugin.h"
#import "FijkQueuingEventSink.h"
#import "FijkEventConstants.h"

#import <Flutter/Flutter.h>
#import <Foundation/Foundation.h>
#import <IJKMediaFramework/IJKMediaPlayer.h>
#import <libkern/OSAtomic.h>
#import <stdatomic.h>

@interface FLTIJKFrameUpdater : NSObject
@property(nonatomic) int64_t textureId;
@property(nonatomic, readonly) NSObject<FlutterTextureRegistry>* registry;
- (void)onDisplayLink:(CADisplayLink*)link;
@end


@implementation FLTIJKFrameUpdater
- (FLTIJKFrameUpdater*)initWithRegistry:(NSObject<FlutterTextureRegistry> *)registry
{
    NSAssert(self, @"super init cannot be nil");
    if (self == nil) {
        return nil;
    };
    _registry = registry;
    return self;
}
- (void)onDisplayLink:(CADisplayLink*)link {
    [_registry textureFrameAvailable: _textureId];
}
@end

@interface FijkPlugin ()

- (void)onPlayingChange:(int)delta;
- (void)onPlayableChange:(int)delta;
- (void)setScreenOn:(BOOL)on;

@end

static atomic_int atomicId = 0;

@implementation FijkPlayer {
    IJKFFMoviePlayerController *_playerController;

    FijkQueuingEventSink *_eventSink;
    FlutterMethodChannel *_methodChannel;
    FlutterEventChannel *_eventChannel;

    id<FlutterPluginRegistrar> _registrar;
    id<FlutterTextureRegistry> _textureRegistry;
    
    FLTIJKFrameUpdater *_frameUpdater;
    CVPixelBufferRef *_latestPixelBuffer;
    CADisplayLink *_displayLink;

    int _width;
    int _height;
    int _rotate;

    FijkHostOption *_hostOption;
    int _state;
    int _pid;
    int64_t _vid;
}

static const int idle = 0;
static const int initialized = 1;
static const int asyncPreparing = 2;
static const int __attribute__((unused)) prepared = 3;
static const int __attribute__((unused)) started = 4;
static const int paused = 5;
static const int completed = 6;
static const int stopped = 7;
static const int __attribute__((unused)) error = 8;
static const int end = 9;

- (instancetype)initJustTexture {
    self = [super init];
    if (self) {
        int pid = atomic_fetch_add(&atomicId, 1);
        _playerId = @(pid);
        _pid = pid;
        _vid = -1;
    }
    return self;
}

- (instancetype)initWithRegistrar:(id<FlutterPluginRegistrar>)registrar {
    self = [super init];
    if (self) {
        _registrar = registrar;
        int pid = atomic_fetch_add(&atomicId, 1);
        _playerId = @(pid);
        _pid = pid;
        _eventSink = [[FijkQueuingEventSink alloc] init];
        _vid = -1;
        _rotate = 0;
        _state = 0;
        _hostOption = [[FijkHostOption alloc] init];

        [IJKFFMoviePlayerController setLogLevel:k_IJK_LOG_INFO];

        _methodChannel = [FlutterMethodChannel
            methodChannelWithName:[@"befovy.com/fijkplayer/" stringByAppendingString:[_playerId stringValue]]
                  binaryMessenger:[registrar messenger]];

        __block typeof(self) weakSelf = self;
        [_methodChannel setMethodCallHandler: ^(FlutterMethodCall *call,FlutterResult result) {
          [weakSelf handleMethodCall:call result:result];
        }];

        _eventChannel = [
             FlutterEventChannel
                 eventChannelWithName:[@"befovy.com/fijkplayer/event/"
                 stringByAppendingString:[_playerId stringValue]]
                 binaryMessenger:[registrar messenger]
        ];

        [_eventChannel setStreamHandler:self];
    }

    return self;
}

- (void) initPlayer: (NSString *) url {
    _playerController = [[IJKFFMoviePlayerController alloc] initWithContentURLString:url withOptions:nil];
    [_playerController setOptionIntValue:1 forKey:@"enable-position-notify" ofCategory:kIJKFFOptionCategoryPlayer];
    [_playerController setOptionIntValue:0 forKey:@"start-on-prepared" ofCategory:kIJKFFOptionCategoryPlayer];
    [self installMovieNotificationObservers];
}


- (void)installMovieNotificationObservers {

    [[NSNotificationCenter defaultCenter] addObserver:self
                                             selector:@selector(moviePlayBackFinish:)
                                                 name:IJKMPMoviePlayerPlaybackDidFinishNotification
                                               object:_playerController];
    
    [[NSNotificationCenter defaultCenter] addObserver:self
                                            selector:@selector(movieSizeChange:)
                                                name:IJKMPMovieNaturalSizeAvailableNotification
                                              object:_playerController];
    
    [[NSNotificationCenter defaultCenter] addObserver:self
                                            selector:@selector(movieAudioFirstFrameStart:)
                                                name:IJKMPMoviePlayerFirstAudioFrameRenderedNotification
                                              object:_playerController];
    
    [[NSNotificationCenter defaultCenter] addObserver:self
                                            selector:@selector(movieVideoFirstFrameStart:)
                                                name:IJKMPMoviePlayerFirstVideoFrameRenderedNotification
                                              object:_playerController];
    
    [[NSNotificationCenter defaultCenter] addObserver:self
                                             selector:@selector(mediaIsPreparedToPlayDidChange:)
                                                 name:IJKMPMediaPlaybackIsPreparedToPlayDidChangeNotification
                                               object:_playerController];
    
}

- (void)removeMovieNotificationObservers {
    [[NSNotificationCenter defaultCenter] removeObserver:self
                                                    name:IJKMPMoviePlayerPlaybackDidFinishNotification
                                                  object:_playerController];
    
    [[NSNotificationCenter defaultCenter] removeObserver:self
                                                name:IJKMPMovieNaturalSizeAvailableNotification
                                              object:_playerController];
    
    [[NSNotificationCenter defaultCenter] removeObserver:self
                                                name:IJKMPMoviePlayerFirstAudioFrameRenderedNotification
                                              object:_playerController];
    
    [[NSNotificationCenter defaultCenter] removeObserver:self
                                                name:IJKMPMoviePlayerFirstVideoFrameRenderedNotification
                                              object:_playerController];
    
    [[NSNotificationCenter defaultCenter] removeObserver:self
                                                    name:IJKMPMediaPlaybackIsPreparedToPlayDidChangeNotification
                                                  object:_playerController];
    
}

- (void)setup {

}

- (void)movieSizeChange: (NSNotification*) notification {
    int width = _playerController.naturalSize.width;
    int height = _playerController.naturalSize.height;
    [self handleEvent:IJKMPET_VIDEO_SIZE_CHANGED andArg1:width andArg2:height andExtra:nil];
}

- (void)movieAudioFirstFrameStart:(NSNotification*) notification {
    [self handleEvent:IJKMPET_AUDIO_RENDERING_START andArg1:0 andArg2:0 andExtra:nil];
}

- (void)movieVideoFirstFrameStart:(NSNotification*) notification {
    [self handleEvent:IJKMPET_VIDEO_RENDERING_START andArg1:0 andArg2:0 andExtra:nil];
}

- (void)moviePlayBackFinish: (NSNotification*) notification {
    [self handleEvent:IJKMPET_COMPLETED andArg1:0 andArg2:0 andExtra:nil];
}

- (void)mediaIsPreparedToPlayDidChange: (NSNotification*) notification {
    [self handleEvent:IJKMPET_PREPARED andArg1:0 andArg2:0 andExtra:nil];
}

- (void)shutdown {
    [self handleEvent:IJKMPET_PLAYBACK_STATE_CHANGED
              andArg1:end
              andArg2:_state
             andExtra:nil];
    
    [_displayLink invalidate];
    _displayLink = nil;
    _frameUpdater = nil;
    
    if (_playerController) {
        [self removeMovieNotificationObservers];
        [_playerController stop];
        [_playerController shutdown];
            _playerController = nil;
    }
    
    if (_latestPixelBuffer) {
        CVPixelBufferRelease(_latestPixelBuffer);
        _latestPixelBuffer = nil;
    }
    
    if (_vid >= 0) {
        [_textureRegistry unregisterTexture:_vid];
        _vid = -1;
        _textureRegistry = nil;
    }

    [_methodChannel setMethodCallHandler:nil];
    _methodChannel = nil;

    [_eventSink setDelegate:nil];
    _eventSink = nil;
    
    [_eventChannel setStreamHandler:nil];
    _eventChannel = nil;
}

- (FlutterError *_Nullable)onCancelWithArguments:(id _Nullable)arguments {
    [_eventSink setDelegate:nil];
    return nil;
}

- (FlutterError *_Nullable)onListenWithArguments:(id _Nullable)arguments eventSink:(nonnull FlutterEventSink)events {
    [_eventSink setDelegate:events];
    return nil;
}

- (CVPixelBufferRef _Nullable)copyPixelBuffer {
    NSLog(@"copyPixelBuffer");
    CVPixelBufferRef newBuffer = [_playerController framePixelBuffer];
    if(newBuffer){
        CFRetain(newBuffer);
        CVPixelBufferRef pixelBuffer = _latestPixelBuffer;
        while (!OSAtomicCompareAndSwapPtrBarrier(pixelBuffer, newBuffer, (void *) &_latestPixelBuffer)) {
            pixelBuffer = _latestPixelBuffer;
        }
        return pixelBuffer;
    }
    return nil;
}

- (NSNumber *)setupSurface {
//    [self setup];
    if (_vid < 0) {
        _textureRegistry = [_registrar textures];
        int64_t vid = [_textureRegistry registerTexture:self];
        
        _frameUpdater = [[FLTIJKFrameUpdater alloc] initWithRegistry: _textureRegistry];
        _frameUpdater.textureId = vid;
        
        _displayLink = [CADisplayLink displayLinkWithTarget:_frameUpdater selector:@selector(onDisplayLink:)];
        _displayLink.frameInterval = 1;
        [_displayLink addToRunLoop:[NSRunLoop currentRunLoop] forMode:NSRunLoopCommonModes];
        _displayLink.paused = YES;
        
        _vid = vid;
    }
    return [NSNumber numberWithLongLong:_vid];
}


- (BOOL)isPlayable:(int)state {
    return state == started || state == paused || state == completed ||
           state == prepared;
}

- (void)onStateChangedWithNew:(int)newState andOld:(int)oldState {
    FijkPlugin *plugin = [FijkPlugin singleInstance];
    
    if (plugin == nil)
        return;
    
    if (newState == started && oldState != started) {
        [plugin onPlayingChange:1];
        if ([[_hostOption getIntValue:FIJK_HOST_OPTION_REQUEST_SCREENON
                               defalt:@(0)] intValue] == 1) {
            [plugin setScreenOn:YES];
        }
    } else if (newState != started && oldState == started) {
        [plugin onPlayingChange:-1];
        if ([[_hostOption getIntValue:FIJK_HOST_OPTION_REQUEST_SCREENON
                               defalt:@(0)] intValue] == 1) {
            [plugin setScreenOn:NO];
        }
    }

    if ([self isPlayable:newState] && ![self isPlayable:oldState]) {
        [plugin onPlayableChange:1];
    } else if (![self isPlayable:newState] && [self isPlayable:oldState]) {
        [plugin onPlayableChange:-1];
    }
}

- (void)handleEvent:(int)what
            andArg1:(int)arg1
            andArg2:(int)arg2
           andExtra:(void *)extra {
    switch (what) {
    case IJKMPET_PREPARED:
        [_eventSink success:@{
            @"event" : @"prepared",
            @"duration" : @((NSInteger)[_playerController duration]),
        }];
        break;
    case IJKMPET_PLAYBACK_STATE_CHANGED:
        _state = arg1;
        [_eventSink success:@{
            @"event" : @"state_change",
            @"new" : @(arg1),
            @"old" : @(arg2),
        }];
        [self onStateChangedWithNew:arg1 andOld:arg2];
        break;
    case IJKMPET_VIDEO_RENDERING_START:
    case IJKMPET_AUDIO_RENDERING_START:
        [_eventSink success:@{
            @"event" : @"rendering_start",
            @"type" : what == IJKMPET_VIDEO_RENDERING_START ? @"video"
                                                            : @"audio",
        }];
        break;
    case IJKMPET_BUFFERING_START:
    case IJKMPET_BUFFERING_END:
        [_eventSink success:@{
            @"event" : @"freeze",
            @"value" :
                [NSNumber numberWithBool:what == IJKMPET_BUFFERING_START],
        }];
        break;
    case IJKMPET_BUFFERING_UPDATE:
        [_eventSink success:@{
            @"event" : @"buffering",
            @"head" : @(arg1),
            @"percent" : @(arg2),
        }];
        break;
    case IJKMPET_CURRENT_POSITION_UPDATE:
        [_eventSink success:@{
            @"event" : @"pos",
            @"pos" : @(arg1),
        }];
        break;
    case IJKMPET_VIDEO_ROTATION_CHANGED:
        [_eventSink success:@{@"event" : @"rotate", @"degree" : @(arg1)}];
        _rotate = arg1;
        if (_height > 0 && _width > 0) {
            [self handleEvent:IJKMPET_VIDEO_SIZE_CHANGED
                      andArg1:_width
                      andArg2:_height
                     andExtra:nil];
        }
        break;
    case IJKMPET_VIDEO_SIZE_CHANGED:
        if (_rotate == 0 || _rotate == 180) {
            [_eventSink success:@{
                @"event" : @"size_changed",
                @"width" : @(arg1),
                @"height" : @(arg2),
            }];
        } else if (_rotate == 90 || _rotate == 270) {
            [_eventSink success:@{
                @"event" : @"size_changed",
                @"width" : @(arg2),
                @"height" : @(arg1),
            }];
        }
        _width = arg1;
        _height = arg2;
        break;
    case 600:
        [_eventSink success:@{
            @"event" : @"seek_complete",
            @"pos" : @(arg1),
            @"err" : @(arg2),
        }];
        break;
    case IJKMPET_ERROR:
        [_eventSink error:[NSString stringWithFormat:@"%d", arg1]
                  message:extra ? [NSString stringWithUTF8String:extra] : nil
                  details:@(arg2)];
        break;
    default:
        break;
    }
}

- (void)setOptions:(NSDictionary *)options {
    for (id cat in options) {
        NSDictionary *option = [options objectForKey:cat];
        for (NSString *key in option) {
            id optValue = [option objectForKey:key];
            if ([optValue isKindOfClass:[NSNumber class]]) {
                if ([cat intValue] == 0) {
                    [_hostOption setIntValue:optValue forKey:key];
                } else {
                    [_playerController
                        setOptionIntValue:[optValue longLongValue]
                                   forKey:key
                               ofCategory:(IJKFFOptionCategory)[cat intValue]];
                }
            } else if ([optValue isKindOfClass:[NSString class]]) {
                if ([cat intValue] == 0) {
                    [_hostOption setStrValue:optValue forKey:key];
                } else {
                    [_playerController
                        setOptionValue:optValue
                                forKey:key
                            ofCategory:(IJKFFOptionCategory)[cat intValue]];
                }
            }
        }
    }
}


- (void)handleMethodCall:(FlutterMethodCall *)call
                  result:(FlutterResult)result {

    NSDictionary *argsMap = call.arguments;
    
    if ([@"setupSurface" isEqualToString:call.method]) {
        result([self setupSurface]);
    } else if ([@"setOption" isEqualToString:call.method]) {
        int category = [argsMap[@"cat"] intValue];
        NSString *key = argsMap[@"key"];
        if (argsMap[@"long"] != nil) {
            int64_t value = [argsMap[@"long"] longLongValue];
            if (category == 0) {
                [_hostOption setIntValue:argsMap[@"long"] forKey:key];
            } else {
                [_playerController
                    setOptionIntValue:value
                               forKey:key
                           ofCategory:(IJKFFOptionCategory)category];
            }
        } else if (argsMap[@"str"] != nil) {
            NSString *value = argsMap[@"str"];
            if (category == 0) {
                [_hostOption setStrValue:value forKey:key];
            } else {
                [_playerController setOptionValue:value
                                         forKey:key
                                     ofCategory:(IJKFFOptionCategory)category];
            }
        } else {
            NSLog(@"FIJKPLAYER: error arguments for setOptions");
        }
        result(nil);
    } else if ([@"applyOptions" isEqualToString:call.method]) {
        [self setOptions:argsMap];
        result(nil);
    } else if ([@"setDataSource" isEqualToString:call.method]) {
        NSString *url = argsMap[@"url"];
        NSURL *aUrl =
            [NSURL URLWithString:[url stringByAddingPercentEscapesUsingEncoding:
                                          NSUTF8StringEncoding]];
        bool file404 = false;
        if ([@"asset" isEqualToString:aUrl.scheme]) {
            NSString *host = aUrl.host;
            NSString *asset = [host length] == 0
                                  ? [_registrar lookupKeyForAsset:aUrl.path]
                                  : [_registrar lookupKeyForAsset:aUrl.path
                                                      fromPackage:host];
            if ([asset length] > 0) {
                NSString *path = [[NSBundle mainBundle] pathForResource:asset
                                                                 ofType:nil];
                if ([path length] > 0)
                    url = path;
            }
            if ([url isEqualToString:argsMap[@"url"]]) {
                file404 = true;
            }
        } else if ([@"file" isEqualToString:aUrl.scheme] ||
                   [aUrl.scheme length] == 0) {
            NSFileManager *fileManager = [[NSFileManager alloc] init];
            if (![fileManager fileExistsAtPath:aUrl.path]) {
                file404 = true;
            }
        }
        if (file404) {
            result([FlutterError errorWithCode:@"-875574348"
                                       message:[@"Local File not found:"
                                                   stringByAppendingString:url]
                                       details:nil]);
        } else {
            [self initPlayer:url];
            [self handleEvent:IJKMPET_PLAYBACK_STATE_CHANGED andArg1:initialized andArg2:-1 andExtra:nil];
            result(nil);
        }
    } else if ([@"prepareAsync" isEqualToString:call.method]) {
        [self setup];
        [_playerController prepareToPlay];
        _displayLink.paused = NO;
        [self handleEvent:IJKMPET_PLAYBACK_STATE_CHANGED
                  andArg1:asyncPreparing
                  andArg2:-1
                 andExtra:nil];
        result(nil);
    } else if ([@"start" isEqualToString:call.method]) {
        [_playerController play];
        _displayLink.paused = NO;
        result(nil);
    } else if ([@"pause" isEqualToString:call.method]) {
        [_playerController pause];
        _displayLink.paused = YES;
        result(nil);
    } else if ([@"stop" isEqualToString:call.method]) {
        [_playerController stop];
        _displayLink.paused = YES;
        [self handleEvent:IJKMPET_PLAYBACK_STATE_CHANGED
                  andArg1:stopped
                  andArg2:-1
                 andExtra:nil];
        result(nil);
    } else if ([@"reset" isEqualToString:call.method]) {
//        [_playerController reset];
//        [self handleEvent:IJKMPET_PLAYBACK_STATE_CHANGED
//                  andArg1:idle
//                  andArg2:-1
//                 andExtra:nil];
        result(nil);
    } else if ([@"getCurrentPosition" isEqualToString:call.method]) {
//        long pos = [_playerController position];
//        result(@(pos));
    } else if ([@"setVolume" isEqualToString:call.method]) {
        double volume = [argsMap[@"volume"] doubleValue];
        [_playerController setPlaybackVolume:(float)volume];
        result(nil);
    } else if ([@"seekTo" isEqualToString:call.method]) {
        long pos = [argsMap[@"msec"] longValue];
        if (_state == completed)
            [self handleEvent:IJKMPET_PLAYBACK_STATE_CHANGED
                      andArg1:paused
                      andArg2:-1
                     andExtra:nil];
        [_playerController setCurrentPlaybackTime:pos];
        result(nil);
    } else if ([@"setSpeed" isEqualToString:call.method]) {
        float speed = [argsMap[@"speed"] doubleValue];
//        [_playerController sp:speed];
//        result(nil);
    } else {
        result(FlutterMethodNotImplemented);
    }
}

@end
