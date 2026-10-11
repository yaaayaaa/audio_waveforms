package com.simform.audio_waveforms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Android フレームワーク不要の纯ロジックだけを落とす。 */
class ExtractionLifecycleTest {

    private val claimThreads = 8
    private val claimRounds = 400

    @Test
    fun `stopOnce は最初の1回だけ true を返す`() {
        val life = ExtractionLifecycle()
        assertTrue(life.stopOnce())
        assertFalse(life.stopOnce())
        assertFalse(life.stopOnce())
        assertTrue(life.isStopped)
    }

    @Test
    fun `claimReply は最初の1回だけ true を返す`() {
        val life = ExtractionLifecycle()
        assertTrue(life.claimReply())
        assertFalse(life.claimReply())
        assertFalse(life.claimReply())
    }

    // ---- 変更 4（stop は打ち切りを返す）で増えた送信者同士の競合 ----
    //
    // 返信の送信者は3本になった: 完了 (replySuccess)、失敗 (reportFailure)、
    // 打ち切り (stop の replyStopped)。それぞれ別のスレッドから届く。
    // 順序のガードではなく claim の CAS だけに依存していることを、このテストで
    // 固定する（上流の `if (!isReplySubmitted) { isReplySubmitted = true; ... }` なら
    // ここが赤になる）。

    @Test
    fun `完了・失敗・打ち切りが同時に来ても返信は1回だけ`() {
        val rounds = 500
        repeat(rounds) {
            val life = ExtractionLifecycle()
            val replied = AtomicInteger(0)
            val gate = CountDownLatch(1)
            val senders = List(3) {
                Thread {
                    gate.await()
                    if (life.claimReply()) replied.incrementAndGet()
                    Unit
                }.also { it.start() }
            }
            gate.countDown()
            senders.forEach { it.join() }
            assertEquals("round $it で返信が ${replied.get()} 回送られた", 1, replied.get())
        }
    }

    @Test
    fun `失敗は stop より先に claim を取るので理由が stopped に化けない`() {
        // reportFailure は `claim → stop → error` の順に呼ぶ。stop の中に
        // replyStopped があるので、順序を逆にすると失敗理由が打ち切りに上書きされる。
        val life = ExtractionLifecycle()
        val failureClaim = life.claimReply()
        life.stopOnce()
        val stoppedWouldReply = life.claimReply()
        assertTrue(failureClaim)
        assertFalse(stoppedWouldReply)
    }

    @Test
    fun `完了済みの抽出を stop しても返信は空振り`() {
        val life = ExtractionLifecycle()
        assertTrue(life.claimReply()) // replySuccess
        life.stopOnce()
        assertFalse(life.claimReply()) // replyStopped
    }

    @Test
    fun `shouldFeedInput は停止・EOF・負 index で false になる`() {
        val life = ExtractionLifecycle()
        assertTrue(life.shouldFeedInput(inputEof = false, index = 0))
        assertFalse(life.shouldFeedInput(inputEof = false, index = -1))
        assertFalse(life.shouldFeedInput(inputEof = true, index = 0))
        life.stopOnce()
        assertFalse(life.shouldFeedInput(inputEof = false, index = 0))
    }

    @Test
    fun `サンプルが容量を超えたときだけ SKIP になる`() {
        assertEquals(InputFeed.READ, inputFeedFor(sampleSize = 1024, capacity = 2048))
        assertEquals(InputFeed.READ, inputFeedFor(sampleSize = 2048, capacity = 2048))
        // 超えたときだけ readSampleData が IllegalArgumentException を投げる
        assertEquals(InputFeed.SKIP, inputFeedFor(sampleSize = 2049, capacity = 2048))
        // 0 以下は EOF 側で処理するので read のままでよい
        assertEquals(InputFeed.READ, inputFeedFor(sampleSize = -1, capacity = 2048))
    }

    // ---- 同時起動（start gate）----
    //
    // 上の 2 つのテストは逐次的に呼んだときの順番だけを見ている。AtomicBoolean の
    // compareAndSet を素の Boolean の check-then-set に戻しても上のテストは通ってしまうので、
    // volatile フラグのスピン待ちで全スレッドを同じ瞬間に走らせて
    // 「true を返すのがちょうど 1 人」であることを検証する。

    @Test
    fun `stopOnce は同時に呼ばれてもちょうど 1 回だけ true を返す`() {
        // threads=2 は実状況（codec コールバック vs プラットフォームスレッド）そのもの。
        // 多いスレッド数でも同じ不変条件を確認する。
        listOf(2, claimThreads).forEach { threads ->
            assertExactlyOneWinner(threads = threads, rounds = claimRounds) { it.stopOnce() }
        }
    }

    @Test
    fun `claimReply は同時に呼ばれてもちょうど 1 回だけ true を返す`() {
        listOf(2, claimThreads).forEach { threads ->
            assertExactlyOneWinner(threads = threads, rounds = claimRounds) { it.claimReply() }
        }
    }

    /**
     * 各ラウンド、[threads] スレッドを volatile フラグのスピン待ちに揃え、
     * 合図を一発倒して全員にほぼ同時に [claim] を呼ばせる。
     * 勝者（true を返したスレッド）は常に 1 でなければならない。
     *
     * 逐次実行ではなく 2 スレッド以上・複数ラウンドで回すのが要点。
     * check-then-set に退化させると、どこのラウンドかで必ず 2 人以上が true を返す。
     */
    private fun assertExactlyOneWinner(
        threads: Int,
        rounds: Int,
        claim: (ExtractionLifecycle) -> Boolean,
    ) {
        val timeoutSeconds = 10L
        repeat(rounds) { round ->
            val label = "threads=$threads round $round"
            val life = ExtractionLifecycle()
            val winners = AtomicInteger(0)
            val go = AtomicBoolean(false)
            val ready = CountDownLatch(threads)
            val done = CountDownLatch(threads)
            val workers = Array(threads) { index ->
                Thread {
                    ready.countDown()
                    // latch の起床よりスピン待ちのほうが全スレッドの到達時刻が揃うので、
                    // check と set の競合窓を本当に重ねられる。
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
                    while (!go.get() && System.nanoTime() < deadline) {
                        // yield せずにスピンする
                    }
                    if (claim(life)) winners.incrementAndGet()
                    done.countDown()
                }.apply { name = "claim-$threads-$round-$index"; isDaemon = true; start() }
            }
            assertTrue("$label: 全スレッドが start gate に揃わない", ready.await(timeoutSeconds, TimeUnit.SECONDS))
            go.set(true)
            workers.forEach { worker ->
                worker.join(timeoutSeconds * 1000)
                assertFalse("$label: ${worker.name} が終わらない", worker.isAlive)
            }
            assertEquals("$label: true を返したのは 1 人のはず", 1, winners.get())
        }
    }

    // ---- 入力コールバックの送信先 ----

    @Test
    fun `入力コールバックの送信先はどの分岐でも queue を省略しない`() {
        // 容量超過でサンプルを捨てるだけ: 空バッファを queue して継続しなければ
        // コーデックが空き待ちになり、以後コールバックが来ないので EOS も送れず
        // デコーダが完了しない（Dart の Future が待ち続ける）。
        assertEquals(
            InputAction.QUEUE_EMPTY,
            inputActionFor(
                sampleSize = 4096, capacity = 2048,
                readSize = 0, sampleTime = -1L, hasMoreSamples = true,
            ),
        )
        // 捨てたサンプルで入力が尽きていたら、その index で EOS を送る
        assertEquals(
            InputAction.QUEUE_END_OF_STREAM,
            inputActionFor(
                sampleSize = 4096, capacity = 2048,
                readSize = 0, sampleTime = -1L, hasMoreSamples = false,
            ),
        )
        // 正常な読み出し
        assertEquals(
            InputAction.QUEUE_SAMPLE,
            inputActionFor(
                sampleSize = 1024, capacity = 2048,
                readSize = 1024, sampleTime = 0L, hasMoreSamples = true,
            ),
        )
        // readSampleData が -1（EOF）を返した
        assertEquals(
            InputAction.QUEUE_END_OF_STREAM,
            inputActionFor(
                sampleSize = -1, capacity = 2048,
                readSize = -1, sampleTime = -1L, hasMoreSamples = false,
            ),
        )
        // sampleTime が負（不明）のサンプルは EOS 扱い
        assertEquals(
            InputAction.QUEUE_END_OF_STREAM,
            inputActionFor(
                sampleSize = 1024, capacity = 2048,
                readSize = 1024, sampleTime = -1L, hasMoreSamples = true,
            ),
        )
    }

    // ---- KEY_MAX_INPUT_SIZE を引き上げる方針 ----

    @Test
    fun `1MB 未満（未設定を含む）だけ KEY_MAX_INPUT_SIZE を引き上げる`() {
        // 未設定・型違いで読めない = コーデック既定（小さい）とみなして引き上げる
        assertTrue(shouldRaiseMaxInputSize(MAX_INPUT_SIZE_UNKNOWN))
        assertTrue(shouldRaiseMaxInputSize(1))
        assertTrue(shouldRaiseMaxInputSize(256 * 1024))
        assertTrue(shouldRaiseMaxInputSize(MIN_MAX_INPUT_SIZE - 1))
        // 境界（ちょうど 1MB）以上は据え置き
        assertFalse(shouldRaiseMaxInputSize(MIN_MAX_INPUT_SIZE))
        assertFalse(shouldRaiseMaxInputSize(2 * MIN_MAX_INPUT_SIZE))
        // 方針の下限値自体も固定しておく
        assertEquals(1024 * 1024, MIN_MAX_INPUT_SIZE)
    }
}
