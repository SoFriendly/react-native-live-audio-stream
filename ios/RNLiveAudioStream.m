#import "RNLiveAudioStream.h"

@implementation RNLiveAudioStream

RCT_EXPORT_MODULE();

RCT_EXPORT_METHOD(init:(NSDictionary *) options) {
    RCTLogInfo(@"[RNLiveAudioStream] init");
    _recordState.mDataFormat.mSampleRate        = options[@"sampleRate"] == nil ? 44100 : [options[@"sampleRate"] doubleValue];
    _recordState.mDataFormat.mBitsPerChannel    = options[@"bitsPerSample"] == nil ? 16 : [options[@"bitsPerSample"] unsignedIntValue];
    _recordState.mDataFormat.mChannelsPerFrame  = options[@"channels"] == nil ? 1 : [options[@"channels"] unsignedIntValue];
    _recordState.mDataFormat.mBytesPerPacket    = (_recordState.mDataFormat.mBitsPerChannel / 8) * _recordState.mDataFormat.mChannelsPerFrame;
    _recordState.mDataFormat.mBytesPerFrame     = _recordState.mDataFormat.mBytesPerPacket;
    _recordState.mDataFormat.mFramesPerPacket   = 1;
    _recordState.mDataFormat.mReserved          = 0;
    _recordState.mDataFormat.mFormatID          = kAudioFormatLinearPCM;
    _recordState.mDataFormat.mFormatFlags       = _recordState.mDataFormat.mBitsPerChannel == 8 ? kLinearPCMFormatFlagIsPacked : (kLinearPCMFormatFlagIsSignedInteger | kLinearPCMFormatFlagIsPacked);
    
    _recordState.bufferByteSize                 = options[@"bufferSize"] == nil ? 2048 : [options[@"bufferSize"] unsignedIntValue];
    _recordState.mSelf = self;
    
    NSString *fileName = options[@"wavFile"] == nil ? @"audio.wav" : options[@"wavFile"];
    NSString *docDir = [NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES) firstObject];
    _filePath = [NSString stringWithFormat:@"%@/%@", docDir, fileName];
}


RCT_EXPORT_METHOD(start) {
    RCTLogInfo(@"[RNLiveAudioStream] start");
    AVAudioSession *audioSession = [AVAudioSession sharedInstance];
    NSError *error = nil;
    BOOL success;
    // Apple recommended:
    // Instead of setting your category and mode properties independently, set them at the same time
    if (@available(iOS 10.0, *)) {
        success = [audioSession setCategory: AVAudioSessionCategoryPlayAndRecord
                                       mode: AVAudioSessionModeVoiceChat
                                    options: AVAudioSessionCategoryOptionDuckOthers |
                   AVAudioSessionCategoryOptionAllowBluetooth |
                   AVAudioSessionCategoryOptionAllowBluetoothA2DP |
                   AVAudioSessionCategoryOptionAllowAirPlay
                                      error: &error];
    } else {
        success = [audioSession setCategory: AVAudioSessionCategoryPlayAndRecord withOptions: AVAudioSessionCategoryOptionDuckOthers error: &error];
        success = [audioSession setMode: AVAudioSessionModeVoiceChat error: &error] && success;
    }
    if (!success || error != nil) {
        RCTLog(@"[RNLiveAudioStream] Problem setting up AVAudioSession category and mode. Error: %@", error);
        return;
    }
    _recordState.mIsRunning = true;
    _recordState.mIsPaused = false;
    _recordState.mCurrentPacket = 0;
        
        CFURLRef url = CFURLCreateWithString(kCFAllocatorDefault, (CFStringRef)_filePath, NULL);
        AudioFileCreateWithURL(url, kAudioFileWAVEType, &_recordState.mDataFormat, kAudioFileFlags_EraseFile, &_recordState.mAudioFile);
        CFRelease(url);
    
    _seconds = 0;
    _durationText = @"00:00";
    dispatch_async(dispatch_get_main_queue(), ^{
        _timer=[NSTimer scheduledTimerWithTimeInterval:1.0 target:self selector:@selector(timerFired:) userInfo:nil repeats:YES];
    });
    

    OSStatus status = AudioQueueNewInput(&_recordState.mDataFormat, HandleInputBuffer, &_recordState, NULL, NULL, 0, &_recordState.mQueue);
    if (status != 0) {
        RCTLog(@"[RNLiveAudioStream] Record Failed. Cannot initialize AudioQueueNewInput. status: %i", (int) status);
        return;
    }
    
    for (int i = 0; i < kNumberBuffers; i++) {
        AudioQueueAllocateBuffer(_recordState.mQueue, _recordState.bufferByteSize, &_recordState.mBuffers[i]);
        AudioQueueEnqueueBuffer(_recordState.mQueue, _recordState.mBuffers[i], 0, NULL);
    }
    
    AudioQueueStart(_recordState.mQueue, NULL);

}

-(void)timerFired:(NSTimer*)timer
{
    _seconds +=1 ;
    _durationText = [NSString stringWithFormat:@"%02d:%02d",_seconds/60, _seconds % 60];

}

RCT_EXPORT_METHOD(pause) {
    if (_recordState.mIsRunning) {
        if (!_recordState.mIsPaused) {
            OSStatus status = AudioQueuePause(_recordState.mQueue);
            if (status == noErr) {
                [_timer invalidate];
                _recordState.mIsPaused = true;
            }
        }
    }
}

RCT_EXPORT_METHOD(resume) {
    if (_recordState.mIsRunning) {
        if (_recordState.mIsPaused) {
            OSStatus status = AudioQueueStart(_recordState.mQueue, NULL);
            if (status == noErr) {
                _recordState.mIsPaused = false;
                dispatch_async(dispatch_get_main_queue(), ^{
                    _timer=[NSTimer scheduledTimerWithTimeInterval:1.0 target:self selector:@selector(timerFired:) userInfo:nil repeats:YES];
                });
            }
        }
    }
}


RCT_EXPORT_METHOD(stop:(RCTPromiseResolveBlock)resolve
                  rejecter:(__unused RCTPromiseRejectBlock)reject) {
    RCTLogInfo(@"[RNLiveAudioStream] stop");
    if (_recordState.mIsRunning) {
        _recordState.mIsRunning = false;
        _recordState.mIsPaused = false;
        AudioQueueStop(_recordState.mQueue, true);
        AudioQueueDispose(_recordState.mQueue, true);
        AudioFileClose(_recordState.mAudioFile);
        for (int i = 0; i < kNumberBuffers; i++) {
            AudioQueueFreeBuffer(_recordState.mQueue, _recordState.mBuffers[i]);
        }
    }
    NSNumber *number = [[NSNumber alloc]initWithInteger:_seconds];
    resolve(@{@"filePath": _filePath, @"duration":number, @"durationText":_durationText});
    [_timer invalidate];
    _seconds = 0;
    _durationText = @"00:00";
        unsigned long long fileSize = [[[NSFileManager defaultManager] attributesOfItemAtPath:_filePath error:nil] fileSize];
        RCTLogInfo(@"file path %@", _filePath);
        RCTLogInfo(@"file size %llu", fileSize);
}

void HandleInputBuffer(void *inUserData,
                       AudioQueueRef inAQ,
                       AudioQueueBufferRef inBuffer,
                       const AudioTimeStamp *inStartTime,
                       UInt32 inNumPackets,
                       const AudioStreamPacketDescription *inPacketDesc) {
    AQRecordState* pRecordState = (AQRecordState *)inUserData;
    
    if (!pRecordState->mIsRunning) {
        return;
    }
    RNLiveAudioStream *local = pRecordState->mSelf;

    if (AudioFileWritePackets(pRecordState->mAudioFile,
                              false,
                              inBuffer->mAudioDataByteSize,
                              inPacketDesc,
                              pRecordState->mCurrentPacket,
                              &inNumPackets,
                              inBuffer->mAudioData
                              ) == noErr) {
        pRecordState->mCurrentPacket += inNumPackets;
    }
    short *samples = (short *) inBuffer->mAudioData;
    long nsamples = inBuffer->mAudioDataByteSize;
    NSData *data = [NSData dataWithBytes:samples length:nsamples];
    NSString *str = [data base64EncodedStringWithOptions:0];
    NSNumber *number = [[NSNumber alloc]initWithInteger:local.seconds];
    [pRecordState->mSelf sendEventWithName:@"data" body:@{ @"duration" : number, @"durationText": local.durationText, @"data": str }];
    AudioQueueEnqueueBuffer(pRecordState->mQueue, inBuffer, 0, NULL);
}

- (NSArray<NSString *> *)supportedEvents {
    return @[@"data"];
}

- (void)dealloc {
    RCTLogInfo(@"[RNLiveAudioStream] dealloc");
    AudioQueueDispose(_recordState.mQueue, true);
    [_timer invalidate];
}

@end
