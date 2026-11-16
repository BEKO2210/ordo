/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.llm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Needs a small GGUF (e.g. Qwen3.5-0.8B) pushed to the test app's external files dir as test.gguf;
 * skipped otherwise. Checks the native bridge, not answer quality (utils/command-bench does that).
 */
@RunWith(AndroidJUnit4::class)
class LlamaEngineTest {
    @Test
    fun prefixSnapshotGrammarAndRepeat() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(context.getExternalFilesDir(null), "test.gguf")
        assumeTrue("no test model", model.exists())
        assertTrue(LlamaEngine.isSupported)
        val engine = LlamaEngine.load(model.path, 4, 1024)
        assertNotNull(engine)
        engine!!.use {
            assertTrue(it.prepare("<|im_start|>system\nAnswer with the animal as JSON.<|im_end|>\n<|im_start|>user\n"))
            val grammar = "root ::= \"{\\\"animal\\\":\\\"\" (\"cat\" | \"dog\") \"\\\"}\""
            val suffix = "Which pet says woof?<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
            val started = System.nanoTime()
            val first = it.generate(suffix, grammar, 40)
            val millis = (System.nanoTime() - started) / 1_000_000
            android.util.Log.i("LlamaEngineTest", "answer=$first in $millis ms")
            assertTrue(first, first == "{\"animal\":\"dog\"}" || first == "{\"animal\":\"cat\"}")
            // A second run starts again from the saved prefix, so it must give the same answer.
            assertEquals(first, it.generate(suffix, grammar, 40))
        }
    }

    // The prompt-state cache: a saved state answers exactly like a freshly read prompt; a damaged file is refused.
    @Test
    fun savedStateRestoresTheSameAnswer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(context.getExternalFilesDir(null), "test.gguf")
        assumeTrue("no test model", model.exists())
        val prefix = "<|im_start|>system\nAnswer with the animal as JSON.<|im_end|>\n<|im_start|>user\n"
        val grammar = "root ::= \"{\\\"animal\\\":\\\"\" (\"cat\" | \"dog\") \"\\\"}\""
        val suffix = "Which pet says woof?<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
        val state = File(context.noBackupFilesDir, "test.state")
        val first = LlamaEngine.load(model.path, 4, 1024)!!.use {
            var started = System.nanoTime()
            assertTrue(it.prepare(prefix))
            val readMillis = (System.nanoTime() - started) / 1_000_000
            started = System.nanoTime()
            assertTrue(it.saveState(state.path))
            android.util.Log.i("LlamaEngineTest", "state ${state.length()} bytes (snapshot ${it.stateSize}), " +
                "prompt read $readMillis ms, saved in ${(System.nanoTime() - started) / 1_000_000} ms")
            it.generate(suffix, grammar, 40)
        }
        LlamaEngine.load(model.path, 4, 1024)!!.use {
            val started = System.nanoTime()
            assertTrue(it.restoreState(state.path))
            android.util.Log.i("LlamaEngineTest", "restored in ${(System.nanoTime() - started) / 1_000_000} ms")
            assertEquals(first, it.generate(suffix, grammar, 40))
            // Cut short: refused, and the engine is left without a state instead of with a broken one.
            java.io.RandomAccessFile(state, "rw").use { f -> f.setLength(f.length() / 2) }
            assertFalse(it.restoreState(state.path))
            assertEquals(0L, it.stateSize)
            assertFalse(it.restoreState(File(state.path + ".missing").path))
        }
        state.delete()
    }
}
