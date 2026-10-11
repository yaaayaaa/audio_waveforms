package com.simform.audio_waveforms

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 抽出の停止と「結果を 1 回だけ送る」判断、および入力コールバックの送信先・
 * 入力バッファサイズ引き上げの方針だけを集めた纯ロジック。
 *
 * Android の型を触らないので JVM unit test で落とせる
 * （WaveformExtractor 本体は MediaCodec / MethodChannel が要る）。
 */
internal class ExtractionLifecycle {
    private val stopped = AtomicBoolean(false)
    private val replyClaimed = AtomicBoolean(false)

    val isStopped: Boolean get() = stopped.get()

    /** 最初の stop() だけ true。2 スレッドから呼ばれたときの後続は false。 */
    fun stopOnce(): Boolean = stopped.compareAndSet(false, true)

    /**
     * result.* を最初に送信できたか。
     *
     * 上流は `private var isReplySubmitted = false` の check-then-set で、
     * 抽出スレッドと Flutter スレッドから同時に通ると 2 通して
     * "Reply already submitted" になる（Crashlytics の別 issue）。
     */
    fun claimReply(): Boolean = replyClaimed.compareAndSet(false, true)

    /** 入力コールバックが抽出を進めるか。 */
    fun shouldFeedInput(inputEof: Boolean, index: Int): Boolean =
        !isStopped && !inputEof && index >= 0
}

/** 1サンプルを codec の入力バッファへ流せるか。 */
internal enum class InputFeed { READ, SKIP }

/**
 * readSampleData は「サンプルサイズが ByteBuffer の容量を超えた」ときに
 * IllegalArgumentException を投げる（android.media.MediaExtractor の仕様）。
 * 読む前に比較する。
 *
 * sampleSize が負（EOF 到達で -1 が返る）のときは READ。0 以下は read 側の
 * `size > 0` チェックで EOF として扱うので、ここで弾くと正常終了できなくなる。
 */
internal fun inputFeedFor(sampleSize: Long, capacity: Int): InputFeed =
    if (sampleSize > capacity) InputFeed.SKIP else InputFeed.READ

/** 入力コールバックが、受け取った index に対して何を queue するか。 */
internal enum class InputAction {
    /** 読み出したサンプルを queue する。 */
    QUEUE_SAMPLE,

    /** 容量超過のサンプルを捨てた。空バッファを queue して index だけ返す。 */
    QUEUE_EMPTY,

    /** 入力を終える。その index で EOS を queue する。 */
    QUEUE_END_OF_STREAM,
}

/**
 * 入力コールバック 1 回ぶんの送信先を決める。
 *
 * onInputBufferAvailable で受け取った index は **どの分岐でも queueInputBuffer で
 * 返さないと以後コールバックが来ない**（コーデックが空き待ちになる）。だから
 * 「何もせず return」がこの返り値に存在してはいけないのが不変条件。
 * サンプルを捨てるときは空バッファ、入力を打ち切るときは EOS を、必ずその index で queue する。
 *
 * [hasMoreSamples] は容量超過のときに advance() を進めた結果。読み出し側は使わない。
 */
internal fun inputActionFor(
    sampleSize: Long,
    capacity: Int,
    readSize: Int,
    sampleTime: Long,
    hasMoreSamples: Boolean,
): InputAction = when {
    inputFeedFor(sampleSize, capacity) == InputFeed.SKIP ->
        if (hasMoreSamples) InputAction.QUEUE_EMPTY else InputAction.QUEUE_END_OF_STREAM
    readSize > 0 && sampleTime >= 0 -> InputAction.QUEUE_SAMPLE
    else -> InputAction.QUEUE_END_OF_STREAM
}

/** 入力で確保する最低バッファサイズ。コーデック既定はデバイス依存で小さい。 */
internal const val MIN_MAX_INPUT_SIZE = 1 * 1024 * 1024

/** KEY_MAX_INPUT_SIZE が未設定・型違いのときに [maxInputSizeOf] が返す値。 */
internal const val MAX_INPUT_SIZE_UNKNOWN = 0

/**
 * KEY_MAX_INPUT_SIZE を [MIN_MAX_INPUT_SIZE] まで引き上げるか。
 *
 * 1MB 未満のものだけを引き上げ、ちょうど 1MB は据え置く。
 * [MAX_INPUT_SIZE_UNKNOWN]（未設定／型違いで読めない）は「コーデック既定＝小さい」と
 * みなして引き上げる。呼び出し側は `maxInputSizeOf(format)` 1 つを渡すだけで、
 * 未設定チェックはここで 0 として扱うので `containsKey` を重ねて見る必要はない。
 */
internal fun shouldRaiseMaxInputSize(maxInputSize: Int): Boolean = maxInputSize < MIN_MAX_INPUT_SIZE
