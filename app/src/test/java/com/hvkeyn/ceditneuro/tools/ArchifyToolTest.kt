package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.workspace.Workspace
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ArchifyToolTest {
    private fun args(spec: String) = buildJsonObject { put("spec", spec) }

    @Test
    fun writesAnInteractiveArchitecturePage() = runBlocking {
        val dir = Files.createTempDirectory("archify").toFile()
        try {
            val out = ArchifyTool(Workspace(dir)).execute(args("""
                {"type":"architecture","title":"Upload pipeline","subtitle":"File to review",
                 "nodes":[
                   {"id":"ui","label":"Mobile UI","kind":"frontend","group":"client","detail":"Picks a file."},
                   {"id":"api","label":"Upload API","kind":"backend","group":"core","detail":"Checks a type."},
                   {"id":"store","label":"Object store","kind":"database","group":"core","detail":"Stores bytes."}],
                 "edges":[{"from":"ui","to":"api","label":"PUT"},{"from":"api","to":"store","label":"write"}],
                 "groups":[{"id":"client","label":"Client"},{"id":"core","label":"Server"}]}
            """.trimIndent()))
            assertTrue(out.content, !out.isError)
            val html = dir.resolve("archify/upload-pipeline.html").readText()
            assertTrue(html.contains("<svg"))
            assertTrue(html.contains("Upload pipeline"))
            assertTrue(html.contains("data-id=\"api\""))
            assertTrue(html.contains("id=\"theme\""))
            assertTrue(html.contains("Object store"))
            assertTrue(html.contains("Mobile UI"))
            assertTrue(html.contains("Client"))
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun refusesAnEdgeToAMissingNode() = runBlocking {
        val dir = Files.createTempDirectory("archify").toFile()
        try {
            val out = ArchifyTool(Workspace(dir)).execute(args("""{"nodes":[{"id":"a","label":"A"}],"edges":[{"from":"a","to":"ghost"}]}"""))
            assertTrue(out.isError)
            assertTrue(out.content.contains("missing node"))
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun refusesTooManyMainNodes() = runBlocking {
        val nodes = (1..17).joinToString(",") { """{"id":"n$it","label":"N$it"}""" }
        val out = ArchifyTool(Workspace(Files.createTempDirectory("archify").toFile())).execute(args("""{"nodes":[$nodes]}"""))
        assertTrue(out.isError)
        assertTrue(out.content.contains("16"))
    }
}
