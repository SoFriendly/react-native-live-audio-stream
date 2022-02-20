declare module "react-native-live-audio-stream" {
  export interface IAudioRecord {
    init: (options: Options) => void
    start: () => void
    pause: () => void
    resume: () => void
    stop: () => Promise<{filePath: string, duration: number, durationText: string}>
    on: (event: "data", callback: (data: string, duration: number, durationText: string ) => void) => void
  }

  export interface Options {
    sampleRate: number
    /**
     * - `1 | 2`
     */
    channels: number
    /**
     * - `8 | 16`
     */
    bitsPerSample: number
    /**
     * - `6`
     */
    audioSource?: number
    wavFile: string
    bufferSize?: number
  }

  const AudioRecord: IAudioRecord

  export default AudioRecord;
}
