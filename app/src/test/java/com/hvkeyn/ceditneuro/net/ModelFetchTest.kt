package com.hvkeyn.ceditneuro.net

import com.hvkeyn.ceditneuro.data.AgentSettings
import com.hvkeyn.ceditneuro.data.CatalogModel
import com.hvkeyn.ceditneuro.data.ModelCatalog
import com.hvkeyn.ceditneuro.tools.ListModelsTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFetchTest {
    @Test
    fun flashIdStaysAndTheLabelNamesTheBuild() {
        val flash = ModelCatalog.deepSeek().models.first { it.name == ModelCatalog.FLASH_MODEL }
        assertEquals("deepseek-flash", flash.name)
        assertTrue(flash.displayName.contains("1017"))
        assertTrue(ModelCatalog.deepSeek().models.any { it.name == "deepseek-v4-flash" })
    }

    @Test
    fun adoptKeepsTheKnownFlashAndAddsANewId() {
        val previous = ModelCatalog.deepSeek().models + CatalogModel(name = "hand", displayName = "Hand")
        val adopted = ModelCatalog.adopt(listOf("deepseek-flash", "qwen3:8b", "hand"), previous)
        assertEquals("DeepSeek V4.1 Flash 1017", adopted.first { it.name == "deepseek-flash" }.displayName)
        val added = adopted.first { it.name == "qwen3:8b" }
        assertEquals("qwen3:8b", added.displayName)
        assertEquals(128_000, added.maxContextTokens)
        assertTrue(adopted.any { it.name == "hand" })
        assertFalse(adopted.any { it.name == "deepseek-v4-flash" })
    }

    @Test
    fun aServerContextIsKeptAndOtherFieldsAreIgnored() {
        val listed = ProviderBalance.listedModels(
            """{"data":[{"id":"remote-a","context_length":163840,"owned_by":"host"},{"id":"remote-b"}]}""",
        )
        assertEquals(listOf("remote-a", "remote-b"), listed!!.map { it.name })
        assertEquals(163840, listed.first().contextTokens)
        val adopted = ModelCatalog.adopt(
            listed.map { it.name },
            emptyList(),
            listed.mapNotNull { model -> model.contextTokens?.let { tokens -> model.name to tokens } }.toMap(),
        )
        assertEquals(163840, adopted.first().maxContextTokens)
        assertEquals(128_000, adopted.last().maxContextTokens)
    }

    @Test
    fun readsOpenAiAndLocalModelLists() {
        assertEquals(
            listOf("deepseek-flash", "deepseek-v4-pro"),
            ProviderBalance.modelNames("""{"data":[{"id":"deepseek-flash"},{"id":"deepseek-v4-pro"}]}"""),
        )
        assertEquals(
            listOf("qwen3:8b"),
            ProviderBalance.modelNames("""{"models":[{"name":"qwen3:8b","model":"qwen3:8b"}]}"""),
        )
        assertEquals(null, ProviderBalance.modelNames("""{"ok":true}"""))
    }

    @Test
    fun aListedNameIsNotTheKey() {
        val names = ProviderBalance.modelNames("""{"data":[{"id":"deepseek-flash"}],"secret":"sk-secret-value"}""")
        assertEquals(listOf("deepseek-flash"), names)
        assertFalse(names!!.any { it.contains("sk-secret") })
    }

    @Test
    fun aHiddenNameIsAcceptedOnlyWhenTheHostKnowsIt() {
        assertEquals("missing", ProviderBalance.modelVerdict(404, """{"error":{"message":"The model does not exist"}}"""))
        assertEquals("present", ProviderBalance.modelVerdict(200, """{"choices":[{"message":{"content":"ok"}}]}"""))
        assertEquals("unknown", ProviderBalance.modelVerdict(200, """{"ok":true}"""))
        assertEquals("unknown", ProviderBalance.modelVerdict(400, """{"error":{"message":"max_tokens is too small"}}"""))
        assertEquals("rejected", ProviderBalance.modelVerdict(401, "no"))
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            ProviderBalance.probeUrl("https://api.deepseek.com/v1"),
        )
    }

    @Test
    fun oneModelsAddressForARemoteHostAndALocalServer() {
        assertEquals("https://api.deepseek.com/v1/models", ProviderBalance.modelUrl("https://api.deepseek.com/v1"))
        assertEquals("http://127.0.0.1:11434/v1/models", ProviderBalance.modelUrl("http://127.0.0.1:11434"))
        assertEquals("http://127.0.0.1:11434/v1/models", ProviderBalance.modelUrl("http://127.0.0.1:11434/v1"))
    }

    @Test
    fun theToolSavesNamesAndDoesNotEchoTheKey() = runBlocking {
        val secret = "sk-secret-value"
        val settings = AgentSettings(
            providers = listOf(
                ModelCatalog.deepSeek().copy(apiKey = secret),
            ),
        )
        var saved: AgentSettings? = null
        val tool = ListModelsTool(
            current = { settings },
            replace = { saved = it },
            fetch = { _, _ -> ProviderBalance.FetchModels(listOf("deepseek-flash", "deepseek-v4-pro")) },
        )
        val result = tool.execute(buildJsonObject { put("provider", "DeepSeek") })
        assertFalse(result.isError)
        assertFalse(result.content.contains(secret))
        assertTrue(result.content.contains("deepseek-v4-pro"))
        assertEquals(
            listOf("deepseek-flash", "deepseek-v4-pro"),
            saved!!.providers.single().models.map { it.name },
        )
        assertEquals("DeepSeek V4.1 Flash 1017", saved!!.providers.single().models.first().displayName)
    }

    @Test
    fun aLocalUrlIsSavedWithoutAKey() = runBlocking {
        var saved: AgentSettings? = null
        val tool = ListModelsTool(
            current = { AgentSettings() },
            replace = { saved = it },
            fetch = { _, key ->
                assertEquals("", key)
                ProviderBalance.FetchModels(listOf("qwen3:8b"))
            },
        )
        val result = tool.execute(buildJsonObject { put("url", "http://127.0.0.1:11434/v1") })
        assertFalse(result.isError)
        val added = saved!!.providers.first { it.apiUrl.contains("11434") }
        assertTrue(added.keyless)
        assertEquals("", added.apiKey)
        assertEquals("qwen3:8b", added.models.single().name)
    }

    @Test
    fun aRemoteUrlIsSavedTheSameWay() = runBlocking {
        var saved: AgentSettings? = null
        val tool = ListModelsTool(
            current = { AgentSettings() },
            replace = { saved = it },
            fetch = { url, key ->
                assertEquals("https://example.test/v1", url)
                assertEquals("", key)
                ProviderBalance.FetchModels(listOf("remote-a"), contextTokens = mapOf("remote-a" to 163840))
            },
        )
        val result = tool.execute(buildJsonObject { put("url", "https://example.test/v1") })
        assertFalse(result.isError)
        val added = saved!!.providers.first { it.apiUrl == "https://example.test/v1" }
        assertTrue(added.keyless)
        assertEquals(163840, added.models.single().maxContextTokens)
    }

    @Test
    fun aUrlWithAPasswordIsNotRepeated() = runBlocking {
        val tool = ListModelsTool(
            current = { AgentSettings(networkEnabled = false) },
            replace = { },
            fetch = { _, _ -> ProviderBalance.FetchModels(note = "should not be called") },
        )
        val result = tool.execute(buildJsonObject { put("url", "https://user:s3cret@example.test/v1") })
        assertTrue(result.isError)
        assertFalse(result.content.contains("s3cret"))
    }
}
