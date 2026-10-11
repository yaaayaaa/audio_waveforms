package com.simform.audio_waveforms

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import io.flutter.plugin.common.MethodChannel
import java.nio.ByteBuffer
import kotlin.math.pow
import kotlin.math.sqrt
import androidx.core.net.toUri

/**
 * WaveformExtractor handles the process of extracting amplitude data from audio files
 * to generate waveform visualizations.
 * 
 * This class uses the Android MediaCodec API to decode audio files and extract
 * RMS (Root Mean Square) values at regular intervals, which represent the amplitude
 * of the audio signal. These values can then be used to draw waveform visualizations.
 * 
 * The extractor supports various audio formats and bit depths (8-bit, 16-bit, and 32-bit)
 * and handles both mono and stereo audio channels.
 */
class WaveformExtractor(
    /** Path to the audio file to analyze */
    private val path: String,
    /** Number of waveform data points to generate */
    private val expectedPoints: Int,
    /** Unique identifier for this extraction process */
    private val key: String,
    /** Method channel for sending progress updates to Flutter */
    private val methodChannel: MethodChannel,
    /** Result callback for sending the final result back to Flutter */
    private val result: MethodChannel.Result,
    /** Application context for accessing content URIs */
    private val context: Context,
) {
    /** MediaCodec for decoding audio data */
    private var decoder: MediaCodec? = null
    /** MediaExtractor for reading audio tracks from the file */
    private var extractor: MediaExtractor? = null
    /** Duration of the audio file in milliseconds */
    private var durationMillis = 0L
    /** Current extraction progress (0.0 to 1.0) */
    private var progress = 0F
    /** Number of processed chunks */
    private var currentProgress = 0F

    /** Flag indicating end of input data */
    private var inputEof = false
    /** Sample rate of the audio in Hz */
    private var sampleRate = 0
    /** Number of audio channels (1=mono, 2=stereo) */
    private var channels = 1
    /** Bit depth of the audio (8, 16, or 32) */
    private var pcmEncodingBit = 16
    /** Total number of audio samples */
    private var totalSamples = 0L
    /** Number of audio samples per waveform data point */
    private var perSamplePoints = 0L
    /** 停止と結果送信の判定を一箇所にまとめる（純ロジック側は unit test で落とす）。 */
    private val lifecycle = ExtractionLifecycle()

    /**
     * Retrieves the audio format from the given media file
     *
     * This method:
     * 1. Creates a MediaExtractor to read the file
     * 2. Finds the first audio track in the file
     * 3. Retrieves and selects that track
     * 4. Extracts the audio duration
     *
     * @param path Path to the audio file (content URI format)
     * @return MediaFormat of the audio track, or null if no audio track is found
     */
    private fun getFormat(path: String): MediaFormat? {
        if (path.isEmpty()) {
            return null
        }
        val mediaExtractor = MediaExtractor()
        this.extractor = mediaExtractor
        val uri = path.toUri()
        mediaExtractor.setDataSource(context, uri, null)
        val trackCount = mediaExtractor.trackCount
        repeat(trackCount) {
            val format = mediaExtractor.getTrackFormat(it)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.contains("audio")) {
                durationMillis = format.getLong(MediaFormat.KEY_DURATION) / 1000
                mediaExtractor.selectTrack(it)
                return format
            }
        }
        return null
    }

    /**
     * Starts the decoding and waveform extraction process
     * 
     * This method initializes the MediaCodec decoder with appropriate
     * callbacks to process audio frames. It handles:
     * 1. Setting up the decoder with the proper format
     * 2. Processing input buffers from the MediaExtractor
     * 3. Processing decoded PCM audio data in output buffers
     * 4. Calculating RMS values for waveform visualization
     * 5. Reporting progress via the callback interface and method channel
     */
    fun startDecode() {
        try {
            val format = getFormat(path) ?: error("No audio format found")
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("No MIME type found")
            // 入力バッファをコーデック既定に任せると、サンプルサイズが ByteBuffer の容量を
            // 超えた時点で readSampleData が IllegalArgumentException を投げて、ハンドラの
            // ない codec コールバックスレッドもろとも process が死ぬ
            // (Crashlytics 8206c761、2.7.16 Android: 4 events / 1 user: Xiaomi)。
            // 1MB あれば圧縮音声の 1 サンプルとしては十分。
            if (shouldRaiseMaxInputSize(maxInputSizeOf(format))) {
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MIN_MAX_INPUT_SIZE)
            }
            decoder = MediaCodec.createDecoderByType(mime).also {
                it.configure(format, null, null, 0)
                it.setCallback(object : MediaCodec.Callback() {
                    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                        // codec.stop() / release() の前後どちらにもコールバックは届く。
                        // getInputBuffer も queueInputBuffer も、解放済み codec では投げる。
                        // このスレッドにはハンドラが無いので、ここで throw すると process が
                        // 死ぬ（Crashlytics 8206c761 と同じ死亡パターン）。
                        // 一部ではなくコールバック本体をまるごと守る。
                        try {
                            feedInput(codec, index)
                        } catch (e: Throwable) {
                            failExtraction("input callback: ${e.javaClass.simpleName}")
                        }
                    }

                    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmEncodingBit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                when (format.getInteger(MediaFormat.KEY_PCM_ENCODING)) {
                                    AudioFormat.ENCODING_PCM_16BIT -> 16
                                    AudioFormat.ENCODING_PCM_8BIT -> 8
                                    AudioFormat.ENCODING_PCM_FLOAT -> 32
                                    else -> 16
                                }
                            } else {
                                16
                            }
                        } else {
                            16
                        }
                        totalSamples = (sampleRate.toLong() * durationMillis) / 1000
                        perSamplePoints = totalSamples / expectedPoints
                    }

                    override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                        // エラーで終わった抽出を生かしたままにしない（reportFailure が stop する）。
                        reportFailure(
                            "An error is thrown while decoding the audio file",
                            e.message,
                        )
                    }

                    override fun onOutputBufferAvailable(
                        codec: MediaCodec,
                        index: Int,
                        info: MediaCodec.BufferInfo
                    ) {
                        if (index < 0 || decoder == null || lifecycle.isStopped) return
                        
                        try {
                            if (info.size > 0) {
                                codec.getOutputBuffer(index)?.let { buf ->
                                    try {
                                        val size = info.size
                                        // Set both position and limit to ensure buffer is accessible
                                        buf.position(info.offset)
                                        buf.limit(info.offset + info.size)
                                        
                                        when (pcmEncodingBit) {
                                            8 -> {
                                                handle8bit(size, buf)
                                            }
                                            16 -> {
                                                handle16bit(size, buf)
                                            }
                                            32 -> {
                                                handle32bit(size, buf)
                                            }
                                            else -> {
                                                Log.e(Constants.LOG_TAG, "Unsupported PCM encoding bit: $pcmEncodingBit")
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e(Constants.LOG_TAG, "Error processing output buffer: ${e.message}")
                                    }
                                }
                            }
                        } finally {
                            // Always release the buffer, even if processing failed
                            try {
                                codec.releaseOutputBuffer(index, false)
                            } catch (e: IllegalStateException) {
                                Log.e(Constants.LOG_TAG, "Error releasing output buffer: ${e.message}")
                            }
                        }

                        if (info.isEof()) {
                            updateProgress()
                            val rms = sqrt(sampleSum / perSamplePoints).toFloat()
                            sendProgress(rms)
                            // 短すぎて progress が 1.0 に届かないファイルでもここで返す。
                            replySuccess()
                            stop()
                        }
                    }

                })
                it.start()
            }

        } catch (e: Exception) {
            if (lifecycle.claimReply()) {
                result.error(
                    Constants.LOG_TAG,
                    e.message,
                    "An error is thrown before decoding the audio file"
                )
            }
        }


    }

    /**
     * 入力コールバック 1 回ぶんを処理する（[WaveformExtractor] の本体ではなく
     * codec コールバックから呼ばれる）。
     *
     * **受け取った index は queueInputBuffer でコーデックに返さないと、以後
     * onInputBufferAvailable が二度と届かない**（コーデックが空きバッファ待ちになる）。
     * 入力を打ち切る分岐でも EOS をその index で queue するのはそのためで、
     * EOS が送れないとデコーダは完了せず Dart の Future が待ち続ける。
     */
    private fun feedInput(codec: MediaCodec, index: Int) {
        // codec.stop() の前後どちらにもコールバックは来る。停止を見ずに
        // 解放済み extractor を触るのが今回の process 落ちの実地点。
        if (!lifecycle.shouldFeedInput(inputEof = inputEof, index = index)) return
        val extractor = extractor ?: return
        // 解放済み codec では throw。外側の try で守る。
        val buf = codec.getInputBuffer(index) ?: return
        // 容量を超えるサンプルを readSampleData に渡すと IllegalArgumentException。
        // 読む前に測って捨てる。
        // getSampleSize() 自体が失敗する版／デバイスがある（long を返すようになって
        // 久しいが、振る舞いの差は潰せない）。ここで落すと外側の try しかない。
        // それでもこのコールバックスレッドを殺してはいけないので、測れないときは
        // 0（＝測定不能）に丸めて、下の read 側で守る。
        val sampleSize: Long = try {
            extractor.getSampleSize()
        } catch (e: Throwable) {
            Log.w(Constants.LOG_TAG, "getSampleSize failed: ${e.javaClass.simpleName}")
            0L
        }
        val hasMoreSamples: Boolean
        val readSize: Int
        val sampleTime: Long
        if (inputFeedFor(sampleSize, buf.capacity()) == InputFeed.SKIP) {
            // 1サンプルだけ捨てて継続する。波形は微妙に欠けるが落ちない。
            Log.w(
                Constants.LOG_TAG,
                "sample $sampleSize > input capacity ${buf.capacity()}; skipped",
            )
            // index を黙って return するとコーデックが空き待ちで止まるので、空バッファを
            // queue して index を返す（下の when の QUEUE_EMPTY / QUEUE_END_OF_STREAM）。
            hasMoreSamples = extractor.advance()
            readSize = 0
            sampleTime = -1L
        } else {
            // ★★ 元々 try の外だった（Crashlytics 8206c761 の実地点）★★ 外側の try で守る。
            readSize = extractor.readSampleData(buf, 0)
            sampleTime = extractor.sampleTime
            hasMoreSamples = true
        }
        // どの分岐も受け取った index を queueInputBuffer でコーデックに返す。
        when (inputActionFor(sampleSize, buf.capacity(), readSize, sampleTime, hasMoreSamples)) {
            InputAction.QUEUE_SAMPLE -> {
                codec.queueInputBuffer(index, 0, readSize, sampleTime, 0)
                // advance() が false でもここでは EOS を送らない。次回の readSampleData が
                // -1 を返すので次のコールバックで EOS を queue できる。ここで inputEof を
                // 立てると onInputBufferAvailable が早期 return して EOS を送れなくなる。
                extractor.advance()
            }
            // 空でも index を返さないとコーデックが空き待ちで止まる。
            InputAction.QUEUE_EMPTY -> codec.queueInputBuffer(index, 0, 0, 0L, 0)
            InputAction.QUEUE_END_OF_STREAM -> {
                codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                inputEof = true
            }
        }
    }

    /** Collected waveform amplitude data points */
    var sampleData = ArrayList<Float>()
    /** Count of samples processed for the current data point */
    private var sampleCount = 0L
    /** Sum of squared sample values for RMS calculation */
    private var sampleSum = 0.0

    /**
     * Processes each audio sample and accumulates data for RMS calculation
     *
     * This method:
     * 1. Accumulates squared sample values
     * 2. When enough samples are collected for a data point, calculates the RMS
     * 3. Updates progress and sends the new data point to Flutter
     * 
     * @param value The normalized audio sample value (-1.0 to 1.0)
     */
    private fun handleBufferDivision(value: Float) {
        if (sampleCount == perSamplePoints) {
            updateProgress()

            // Discard redundant values and release resources
            if (progress > 1.0F) {
                replySuccess()
                stop()
                return
            }
            val rms = sqrt(sampleSum / perSamplePoints).toFloat()
            sendProgress(rms)
        }

        sampleCount++
        sampleSum += value.toDouble().pow(2.0)
    }

    /**
     * Processes 8-bit PCM audio data
     *
     * Reads 8-bit samples from the buffer, normalizes them to the range [-1.0, 1.0],
     * and passes them to handleBufferDivision for RMS calculation.
     * 
     * @param size Size of the buffer in bytes
     * @param buf ByteBuffer containing the audio data
     */
    private fun handle8bit(size: Int, buf: ByteBuffer) {
        repeat(size / if (channels == 2) 2 else 1) {
            val result = buf.get().toInt() / Constants.EIGHT_BITS
            if (channels == 2) {
                buf.get()
            }
            handleBufferDivision(result)
        }
    }

    /**
     * Processes 16-bit PCM audio data
     *
     * Reads 16-bit samples from the buffer, normalizes them to the range [-1.0, 1.0],
     * and passes them to handleBufferDivision for RMS calculation.
     * 
     * @param size Size of the buffer in bytes
     * @param buf ByteBuffer containing the audio data
     */
    private fun handle16bit(size: Int, buf: ByteBuffer) {
        repeat(size / if (channels == 2) 4 else 2) {
            val first = buf.get().toInt()
            val second = buf.get().toInt() shl 8
            val value = (first or second) / Constants.SIXTEEN_BITS
            if (channels == 2) {
                buf.get()
                buf.get()
            }
            handleBufferDivision(value)
        }
    }

    /**
     * Processes 32-bit PCM audio data
     *
     * Reads 32-bit samples from the buffer, normalizes them to the range [-1.0, 1.0],
     * and passes them to handleBufferDivision for RMS calculation.
     * 
     * @param size Size of the buffer in bytes
     * @param buf ByteBuffer containing the audio data
     */
    private fun handle32bit(size: Int, buf: ByteBuffer) {
        repeat(size / if (channels == 2) 8 else 4) {
            val first = buf.get().toLong()
            val second = buf.get().toLong() shl 8
            val third = buf.get().toLong() shl 16
            val forth = buf.get().toLong() shl 24
            val value = (first or second or third or forth) / Constants.THIRTY_TWO_BITS
            if (channels == 2) {
                buf.get()
                buf.get()
                buf.get()
                buf.get()
            }
            handleBufferDivision(value)
        }
    }

    /**
     * Updates the extraction progress
     * 
     * Increments the progress counter and calculates the overall
     * extraction progress as a ratio of current to expected data points.
     */
    private fun updateProgress() {
        currentProgress++
        progress = currentProgress / expectedPoints
    }

    /**
     * Sends a new waveform data point and current progress to Flutter
     *
     * This method:
     * 1. Adds the new RMS value to the waveform data
     * 2. Reports the progress via the callback interface
     * 3. Resets the sample counters for the next data point
     * 4. Sends the current waveform data and progress to Flutter via the method channel
     * 
     * @param rms The calculated RMS value for this data point
     */
    private fun sendProgress(rms: Float) {
        sampleData.add(rms)
        sampleCount = 0
        sampleSum = 0.0

        val args: MutableMap<String, Any?> = HashMap()
        args[Constants.waveformData] = sampleData
        args[Constants.progress] = progress
        args[Constants.playerKey] = key
        methodChannel.invokeMethod(
            Constants.onCurrentExtractedWaveformData,
            args
        )
        // 予定データ点数に到達 = 完了。成功返信も失敗返信と同じ claim を通すので、
        // 抽出途中で error を返した後に success が二重送信されない。
        if (progress >= 1.0F) replySuccess()
    }

    /**
     * 成功返信（波形データ）を 1 回だけ送る。
     *
     * 上流は plugin 側の `onProgress(1.0F)` から `result.success` を直接呼んでいたため、
     * claim を通らずに失敗返信と競合して "Reply already submitted" になっていた
     * （VENDORED.md 変更 3）。
     */
    private fun replySuccess() {
        if (lifecycle.claimReply()) {
            result.success(sampleData)
        }
    }

    /**
     * Stops the extraction process and releases resources, **then reports the
     * cancellation** so the Dart `Future` resolves.
     *
     * voimero patch — kana_kit 流に言えば「取り込んで直した」4件目
     * (VENDORED.md 変更 4)。上流は stop() で何も返さないので、抽出中に stop されると
     * `extractWaveformData` の await が**一生解決しない**（実機で実測。上流からの
     * 既存動作なので修正しない選択もできたが、放置すると呼び出し側の continuation が
     * 永久に失われて `finally` の後始末も走らない）。
     *
     * 完了時は [replySuccess] が先に claim を取っているので、ここは空振りになる。
     * 失敗時は [reportFailure] が先に claim を取るのも同じ。
     */
    fun stop() {
        // ★ voimero patch (上流 PR #515 準拠) — 詳細は audio_waveforms/VENDORED.md
        //
        // stop() は 2 つのスレッドから来る: 抽出が自力で終わったときの
        // MediaCodec コールバックスレッドと、Flutter が stopExtraction を呼ぶ
        // (PlayerController.dispose がこれをする) プラットフォームスレッド。
        // 競り負けたほうは解放済みの codec に stop() をかけることになり、
        // IllegalStateException("codec is released already") で死ぬ。
        // コールバックスレッド側にはハンドラが無いのでプロセスごと落ちる。
        // 解放後にも codec コールバックは1回届く。入出力コールバック側はこの停止
        // フラグを見て、解放済み extractor を触らないようにする。
        if (!lifecycle.stopOnce()) return

        releaseCodecAndExtractor()
        replyStopped()
    }

    /** 打ち切りを 1 回だけ返す。 */
    private fun replyStopped() {
        if (lifecycle.claimReply()) {
            result.error(
                Constants.extractionStopped,
                "Waveform extraction was stopped before it completed",
                null,
            )
        }
    }

    private fun releaseCodecAndExtractor() {
        try {
            decoder?.stop()
        } catch (e: IllegalStateException) {
            Log.w(Constants.LOG_TAG, "Decoder already stopped: ${e.message}")
        }
        try {
            decoder?.release()
        } catch (e: IllegalStateException) {
            Log.w(Constants.LOG_TAG, "Decoder already released: ${e.message}")
        }
        decoder = null

        try {
            extractor?.release()
        } catch (e: IllegalStateException) {
            Log.w(Constants.LOG_TAG, "Extractor already released: ${e.message}")
        }
        extractor = null

    }

    /**
     * 結果を1回だけ返す。引数の並びは既存の result.error(code, message, details) と同じ。
     *
     * 先に stop() する。失敗した抽出を生かしたままにすると、その後の codec コールバックで
     * 入力を組み直したり別の送信をしたりして、送信順が error → success になってしまう。
     * stop() は冪等なので `failExtraction` 側で重複して呼んでも余計なコストはかからない。
     */
    private fun reportFailure(details: String, message: String?) {
        // claim は stop() より先に取る。stop() 側の打ち切り返信に取られると、
        // 失敗理由が "stopped" に化けて原因が分からなくなる。
        val claim = lifecycle.claimReply()
        stop()
        if (claim) {
            result.error(Constants.LOG_TAG, message ?: details, details)
        }
    }

    /** 抽出を打ち切る: reportFailure が stop() してからエラーを返す。 */
    private fun failExtraction(reason: String) {
        Log.w(Constants.LOG_TAG, "waveform extraction failed: $reason")
        reportFailure(reason, "Waveform extraction failed")
    }
}

/**
 * KEY_MAX_INPUT_SIZE の設定値を読む。未設定・型の不一致は [MAX_INPUT_SIZE_UNKNOWN]
 * （0）として返し、[shouldRaiseMaxInputSize] に判定させる。
 * getInteger は未設定キーに対して例外を投げるので containsKey で見る。
 */
private fun maxInputSizeOf(format: MediaFormat): Int =
    if (!format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) MAX_INPUT_SIZE_UNKNOWN
    else try {
        format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
    } catch (e: ClassCastException) {
        MAX_INPUT_SIZE_UNKNOWN
    }

/**
 * Extension function to check if a buffer contains the end-of-stream flag
 * 
 * @return true if this buffer marks the end of the stream, false otherwise
 */
fun MediaCodec.BufferInfo.isEof() = flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0

// voimero patch — `ExtractorCallBack` を削除した（VENDORED.md 変更 4）。
// 進捗は methodChannel の `onCurrentExtractedWaveformData` で Dart に届いていて、
// 完了返信も ExtractionLifecycle の claim を通すようになったので、このコールバックの
// 生産者がいなくなっていた。`override fun onProgress(value: Float) = Unit` という
// 「生えているが何もしない」API は、次の人が進捗をここで受け取ろうとして間違える。