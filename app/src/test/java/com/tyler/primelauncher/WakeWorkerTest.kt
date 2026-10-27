package com.tyler.primelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 0.1.12 regression: the libvosk SIGSEGV came from two "prime-wake" listeners overlapping on a quick stop/start. */
class WakeWorkerTest {
    private fun listenerBody(active: AtomicInteger, maxSeen: AtomicInteger, runs: AtomicInteger, cleanupMs: Long): (SingleWorker.Token) -> Unit = { tok ->
        val now = active.incrementAndGet(); maxSeen.accumulateAndGet(now, ::maxOf); runs.incrementAndGet()
        try { while (!tok.stopped) try { Thread.sleep(5) } catch (_: InterruptedException) {} }
        finally { Thread.sleep(cleanupMs); active.decrementAndGet() } // like closing the recognizer + model
    }

    @Test fun quickStopStartNeverOverlaps() {
        val w = SingleWorker("prime-wake-test"); val active = AtomicInteger(); val max = AtomicInteger(); val runs = AtomicInteger()
        assertTrue(w.start(listenerBody(active, max, runs, 150))); Thread.sleep(30)
        assertFalse("second start while live is a no-op", w.start(listenerBody(active, max, runs, 150)))
        repeat(20) { w.stop(); assertTrue(w.start(listenerBody(active, max, runs, 40))) } // hammer stop/start
        Thread.sleep(300); w.stop(); val deadline = System.currentTimeMillis() + 5000
        while (active.get() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals("never two listeners at once", 1, max.get()); assertEquals(0, active.get())
        assertTrue("first and last worker ran; stopped-before-start ones skipped", runs.get() in 2..21); assertFalse(w.live)
    }

    @Test fun newWorkerWaitsForPreviousCleanup() {
        val w = SingleWorker("prime-wake-test"); val order = Collections.synchronizedList(mutableListOf<String>()); val started = CountDownLatch(1); val done = CountDownLatch(1)
        w.start { tok -> started.countDown(); try { while (!tok.stopped) Thread.sleep(5) } catch (_: InterruptedException) {} finally { Thread.sleep(200); order += "old-closed" } }
        assertTrue(started.await(2, TimeUnit.SECONDS)); w.stop()
        w.start { order += "new-opened"; done.countDown() }
        assertTrue(done.await(3, TimeUnit.SECONDS)); assertEquals(listOf("old-closed", "new-opened"), order)
    }

    @Test fun modelSwapIsAtomicAndPerVersion() {
        val base = Files.createTempDirectory("pl-model").toFile(); File(base, "vosk-model").apply { mkdirs(); File(this, "old").writeText("x") } // pre-0.1.12 folder
        val final12 = File(base, "vosk-model-v12"); val fills = AtomicInteger(); val sawFinalDuringCopy = AtomicInteger()
        val fill: (File) -> Unit = { out -> fills.incrementAndGet(); for (i in 0 until 5) { if (final12.exists()) sawFinalDuringCopy.incrementAndGet(); File(out, "part$i").writeText("p$i"); Thread.sleep(20) } }
        val threads = (0 until 4).map { Thread { val d = ModelInstaller.install(base, 12, fill); assertTrue(File(d, "part4").exists() && File(d, ".installed").exists()) } }
        threads.forEach { it.start() }; threads.forEach { it.join(5000) }
        assertEquals("copied once", 1, fills.get()); assertEquals("final folder appears only when complete", 0, sawFinalDuringCopy.get())
        assertTrue(final12.isDirectory); assertFalse("legacy folder pruned", File(base, "vosk-model").exists())
        assertTrue("no temp left", base.listFiles()!!.none { it.name.contains(".tmp-") })
        // a crash mid-copy leaves no half model in place; next version installs fresh and prunes v12
        runCatching { ModelInstaller.install(base, 13) { out -> File(out, "half").writeText("h"); error("killed") } }
        assertFalse(File(base, "vosk-model-v13").exists()); assertTrue(final12.isDirectory)
        val d13 = ModelInstaller.install(base, 13, fill); assertTrue(File(d13, "part4").exists()); assertFalse(final12.exists())
        assertEquals(listOf("vosk-model-v13"), base.list()!!.toList()); base.deleteRecursively()
    }
}
