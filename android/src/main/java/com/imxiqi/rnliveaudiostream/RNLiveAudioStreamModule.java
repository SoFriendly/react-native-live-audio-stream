package com.imxiqi.rnliveaudiostream;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder.AudioSource;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.Math;
import java.util.Locale;

public class RNLiveAudioStreamModule extends ReactContextBaseJavaModule {

    private final String TAG = "RNLiveAudioStream";
    private final ReactApplicationContext reactContext;
    private DeviceEventManagerModule.RCTDeviceEventEmitter eventEmitter;

    private int sampleRateInHz;
    private int channelConfig;
    private int audioFormat;

    private AudioRecord recorder;
    private int bufferSize;
    private boolean isRecording = false;
    private boolean isRecordingPause = false;

    private String tmpFile;
    private String outFile;
    private Promise stopRecordingPromise;

    private Thread recordingThread;
    private long systemTime = 0L;
    private long pausedRecordTime = 0L;
    private long totalPausedRecordTime = 0L;

    public RNLiveAudioStreamModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
    }

    @Override
    public String getName() {
        return "RNLiveAudioStream";
    }

    @ReactMethod
    public void init(ReadableMap options) {
        sampleRateInHz = 44100;
        if (options.hasKey("sampleRate")) {
            sampleRateInHz = options.getInt("sampleRate");
        }

        channelConfig = AudioFormat.CHANNEL_IN_MONO;
        if (options.hasKey("channels")) {
            if (options.getInt("channels") == 2) {
                channelConfig = AudioFormat.CHANNEL_IN_STEREO;
            }
        }

        audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        if (options.hasKey("bitsPerSample")) {
            if (options.getInt("bitsPerSample") == 8) {
                audioFormat = AudioFormat.ENCODING_PCM_8BIT;
            }
        }

        int audioSource = AudioSource.VOICE_RECOGNITION;
        if (options.hasKey("audioSource")) {
            audioSource = options.getInt("audioSource");
        }

        String documentDirectoryPath = getReactApplicationContext().getFilesDir().getAbsolutePath();
        outFile = documentDirectoryPath + "/" + "audio.wav";
        tmpFile = documentDirectoryPath + "/" + "temp.pcm";
        if (options.hasKey("wavFile")) {
            String fileName = options.getString("wavFile");
            outFile = documentDirectoryPath + "/" + fileName;
        }

        eventEmitter = reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class);
        bufferSize = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat);
        if (options.hasKey("bufferSize")) {
            bufferSize = Math.max(bufferSize, options.getInt("bufferSize"));
        }
        int recordingBufferSize = bufferSize * 3;
        recorder = new AudioRecord(audioSource, sampleRateInHz, channelConfig, audioFormat, recordingBufferSize);
    }

    @ReactMethod
    public void start() {
        Log.d(TAG, "started recording");
        if(!isRecording && recorder != null) {
            isRecording = true;
            systemTime = SystemClock.elapsedRealtime();
            totalPausedRecordTime = 0L;
            recorder.startRecording();
            initThread();
            recordingThread.start();
        } else {
            Log.e(TAG, "started recording");
        }
    }

    @ReactMethod
    public void stop(Promise promise) {
        Log.d(TAG, "stopped recording");
        if(isRecording) {
            isRecording = false;
            isRecordingPause = false;
            stopRecordingPromise = promise;
        } else {
            Log.e(TAG, "stopped recording");
        }
    }

    @ReactMethod
    public void pause() {
        Log.d(TAG, "paused recording");
        if(!isRecordingPause && recorder != null) {
            isRecordingPause = true;
            pausedRecordTime = SystemClock.elapsedRealtime();
            recordingThread.interrupt();
        } else {
            Log.e(TAG, "paused recording");
        }
    }

    @ReactMethod
    public void resume() {
        Log.d(TAG, "resumed recording");
        if(isRecordingPause && recorder != null) {
            isRecordingPause = false;
            totalPausedRecordTime += SystemClock.elapsedRealtime() - pausedRecordTime;
            initThread();
            recordingThread.start();
        } else {
            Log.e(TAG, "resumed recording");
        }
    }

    private void initThread() {
        recordingThread = new Thread(new Runnable() {
            public void run() {
                saveBuffer(systemTime);
            }
        });
    }

    public static String convertTommss(long milliseconds) {
        long seconds = milliseconds / 1000;
        // long l = milliseconds % 1000;
        long s = seconds % 60;
        long m = (seconds / 60) % 60;
        // long h = (seconds / (60 * 60)) % 24;
        return String.format(Locale.getDefault(), "%02d:%02d", m, s);
    }

    private void returnStop(long systemTime) {
        Log.d(TAG, "returnStop");
        WritableMap obj = Arguments.createMap();
        obj.putString("file", outFile);
        try {
            MediaMetadataRetriever metaRetriever = new MediaMetadataRetriever();
            metaRetriever.setDataSource(outFile);
            long duration =
                    Long.parseLong(metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            metaRetriever.release();
            obj.putDouble("duration", duration);
            obj.putString("durationText", convertTommss(duration));
            Log.d(TAG, "Data recording finish " + obj);
            stopRecordingPromise.resolve(obj);
        } catch (Exception e) {
            Log.e(TAG, "returnStop " + e);
            long duration = SystemClock.elapsedRealtime() - systemTime - totalPausedRecordTime;
            obj.putDouble("duration", duration);
            obj.putString("durationText", convertTommss(duration));
            Log.d(TAG, "Data recording finish " + obj);
            stopRecordingPromise.resolve(obj);
        }
    }

    private void saveBuffer(long systemTime) {
        Log.d(TAG, "saveBuffer");
        try {
            int bytesRead;
            int count = 0;
            String base64Data;
            byte[] buffer = new byte[bufferSize];
            FileOutputStream os = new FileOutputStream(tmpFile);

            while (isRecording && !isRecordingPause) {
                bytesRead = recorder.read(buffer, 0, buffer.length);

                // skip first 2 buffers to eliminate "click sound"
                if (bytesRead > 0 && ++count > 2) {
                    base64Data = Base64.encodeToString(buffer, Base64.NO_WRAP);
                    long duration = SystemClock.elapsedRealtime() - systemTime - totalPausedRecordTime;
                    WritableMap obj = Arguments.createMap();
                    obj.putDouble("duration", duration);
                    obj.putString("durationText", convertTommss(duration));
                    obj.putString("data", base64Data);
                    Log.d(TAG, "Data recording " + obj);
                    eventEmitter.emit("data", obj);
                    os.write(buffer, 0, bytesRead);
                }
            }
            if(!isRecording && !isRecordingPause) {
                recorder.stop();
                os.close();
                saveAsWav();
                returnStop(systemTime);
            }
        } catch (Exception e) {
            Log.e(TAG, "saveBuffer " + e);
        }
    }

    private void saveAsWav() {
        Log.d(TAG, "saveAsWav");
        try {
            FileInputStream in = new FileInputStream(tmpFile);
            FileOutputStream out = new FileOutputStream(outFile);
            long totalAudioLen = in.getChannel().size();;
            long totalDataLen = totalAudioLen + 36;

            addWavHeader(out, totalAudioLen, totalDataLen);

            byte[] data = new byte[bufferSize];
            int bytesRead;
            while ((bytesRead = in.read(data)) != -1) {
                out.write(data, 0, bytesRead);
            }
            Log.d(TAG, "file path:" + outFile);
            Log.d(TAG, "file size:" + out.getChannel().size());

            in.close();
            out.close();
            deleteTempFile();
        } catch (Exception e) {
            Log.e(TAG, "saveAsWav " + e);
        }
    }

    private void addWavHeader(FileOutputStream out, long totalAudioLen, long totalDataLen) throws Exception {
        long sampleRate = sampleRateInHz;
        int channels = channelConfig == AudioFormat.CHANNEL_IN_MONO ? 1 : 2;
        int bitsPerSample = audioFormat == AudioFormat.ENCODING_PCM_8BIT ? 8 : 16;
        long byteRate =  sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;

        byte[] header = new byte[44];

        header[0] = 'R';                                    // RIFF chunk
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff);           // how big is the rest of this file
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W';                                    // WAVE chunk
        header[9] = 'A';
        header[10] = 'V';
        header[11] = 'E';
        header[12] = 'f';                                   // 'fmt ' chunk
        header[13] = 'm';
        header[14] = 't';
        header[15] = ' ';
        header[16] = 16;                                    // 4 bytes: size of 'fmt ' chunk
        header[17] = 0;
        header[18] = 0;
        header[19] = 0;
        header[20] = 1;                                     // format = 1 for PCM
        header[21] = 0;
        header[22] = (byte) channels;                       // mono or stereo
        header[23] = 0;
        header[24] = (byte) (sampleRate & 0xff);            // samples per second
        header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff);
        header[27] = (byte) ((sampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);              // bytes per second
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = (byte) blockAlign;                     // bytes in one sample, for all channels
        header[33] = 0;
        header[34] = (byte) bitsPerSample;                  // bits in a sample
        header[35] = 0;
        header[36] = 'd';                                   // beginning of the data chunk
        header[37] = 'a';
        header[38] = 't';
        header[39] = 'a';
        header[40] = (byte) (totalAudioLen & 0xff);         // how big is this data chunk
        header[41] = (byte) ((totalAudioLen >> 8) & 0xff);
        header[42] = (byte) ((totalAudioLen >> 16) & 0xff);
        header[43] = (byte) ((totalAudioLen >> 24) & 0xff);

        out.write(header, 0, 44);
    }

    private void deleteTempFile() {
        File file = new File(tmpFile);
        file.delete();
    }
}