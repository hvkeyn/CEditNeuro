package com.hvkeyn.ceditneuro.agent

import com.hvkeyn.ceditneuro.agent.deepseek.ModelStall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class ModelStallTest {
    @Test
    fun aSilentReadIsAStallAndALoginIsNot() {
        val silent = SocketTimeoutException("timeout")
        assertTrue(ModelStall.isStall(silent))
        assertEquals(ModelStall.MESSAGE, ModelStall.explain(IOException(silent)).message)

        assertTrue(ModelStall.isStall(IOException("DeepSeek web error: The server is busy. Please try again later.")))
        assertFalse(ModelStall.isStall(IOException("DeepSeek web error: login_required. Sign in again.")))
        assertFalse(ModelStall.isStall(IOException("failed to connect to chat.deepseek.com")))
        assertEquals(
            "DeepSeek web error: DeepSeek stopped this reply (FAILED).",
            ModelStall.userMessage("DeepSeek stopped this reply (FAILED)."),
        )
    }
}
