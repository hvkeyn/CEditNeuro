package com.hvkeyn.ceditneuro.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderBalanceTest {
    @Test
    fun readsDeepSeekCurrencies() {
        val text = ProviderBalance.parse(
            """{"is_available":false,"balance_infos":[{"currency":"USD","total_balance":"1.20"},{"currency":"CNY","total_balance":"0.00"}]}""",
        )
        assertEquals("USD 1.20 · CNY 0.00 — top up", text)
    }

    @Test
    fun readsOpenRouterAndMoonshot() {
        assertEquals(
            "USD 8.50 left",
            ProviderBalance.parse("""{"data":{"total_credits":10,"total_usage":1.5}}"""),
        )
        assertEquals(
            "CNY 49.59 left",
            ProviderBalance.parse("""{"data":{"available_balance":49.588,"cash_balance":49.588,"voucher_balance":0}}"""),
        )
    }

    @Test
    fun unknownJsonStaysUnknown() {
        assertNull(ProviderBalance.parse("""{"ok":true}"""))
    }

    @Test
    fun deepSeekUsesItsOwnBalancePath() {
        val urls = ProviderBalance.candidateUrls("https://api.deepseek.com", "https://api.deepseek.com/v1")
        assertEquals(listOf("https://api.deepseek.com/user/balance"), urls)
    }

    @Test
    fun otherHostsTryTheSharedBalancePaths() {
        val urls = ProviderBalance.candidateUrls("https://example.test", "https://example.test/v1")
        assertTrue(urls.any { it.endsWith("/user/balance") })
        assertTrue(urls.any { it.endsWith("/credits") })
    }
}
