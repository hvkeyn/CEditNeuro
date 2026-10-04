package com.hvkeyn.ceditneuro.agent

import com.hvkeyn.ceditneuro.tools.SecondMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class SecondMonitorTest {
    @Test
    fun linuxScriptRefusesADesktopThatCannotAddAMonitor() {
        val script = SecondMonitor.linuxScript(1080, 1920)
        assertTrue(script.contains("WIDTH = 1080"))
        assertTrue(script.contains("HEIGHT = 1920"))
        assertTrue(script.contains("Nothing was mirrored."))
        assertTrue(script.contains("grim is not installed"))
        assertFalse(Regex("""\bsudo\b""").containsMatchIn(script))
        assertFalse(Regex("""\bapt\b""").containsMatchIn(script))
        val dir = Files.createTempDirectory("second-monitor").toFile()
        val file = dir.resolve("second_monitor.py")
        file.writeText(script)
        val python = listOf("py", "python", "python3").firstOrNull { name ->
            runCatching {
                val probe = ProcessBuilder(if (name == "py") listOf("py", "-3", "-c", "import sys") else listOf(name, "-c", "import sys"))
                    .redirectErrorStream(true)
                    .start()
                probe.waitFor(15, TimeUnit.SECONDS) && probe.exitValue() == 0
            }.getOrDefault(false)
        }
        if (python == null) return
        val command = if (python == "py") listOf("py", "-3", file.absolutePath) else listOf(python, file.absolutePath)
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .apply {
                environment()["HOME"] = dir.absolutePath
                environment()["USERPROFILE"] = dir.absolutePath
                environment()["SWAYSOCK"] = ""
                environment()["HYPRLAND_INSTANCE_SIGNATURE"] = ""
                environment()["XDG_CURRENT_DESKTOP"] = "GNOME"
            }
            .start()
        val finished = process.waitFor(20, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(output, finished)
        assertEquals(output, 2, process.exitValue())
        assertTrue(output, output.contains("Nothing was mirrored."))
        assertFalse(dir.resolve(".ceditneuro-second-monitor.json").exists())
    }

    @Test
    fun windowsScriptUsesTheAdbTunnel() {
        val script = SecondMonitor.windowsScript()
        assertTrue(script.contains("http://127.0.0.1:8791/"))
        assertTrue(script.contains("Do not download a display program."))
        assertTrue(script.contains("Do not invent an address."))
        assertFalse(script.contains("Invoke-WebRequest"))
        assertFalse(script.contains("Expand-Archive"))
        assertFalse(script.contains("Invoke-Expression"))
        assertFalse(script.contains("-enc"))
        assertFalse(script.contains("¤"))
        val file = Files.createTempFile("second-monitor", ".ps1").toFile()
        file.writeText(script)
        val dollar = "${'$'}"
        val command = dollar + "e=" + dollar + "null; [void][System.Management.Automation.Language.Parser]::ParseFile('" +
            file.absolutePath.replace("'", "''") + "', [ref]" + dollar + "null, [ref]" + dollar +
            "e); if (" + dollar + "e) { " + dollar + "e | ForEach-Object { " + dollar + "_.ToString() }; exit 1 } else { exit 0 }"
        val process = ProcessBuilder(
            "powershell",
            "-NoProfile",
            "-Command",
            command,
        ).redirectErrorStream(true).start()
        val finished = process.waitFor(20, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(output, finished)
        assertEquals(output, 0, process.exitValue())
    }

    @Test
    fun openAcceptsOnlyARemotePage() {
        assertTrue(SecondMonitor.acceptPage("http://192.168.1.20:8791/m/abc/"))
        assertTrue(SecondMonitor.acceptPage("http://127.0.0.1:8791/"))
        assertTrue(SecondMonitor.acceptPage("http://localhost/m/abc/"))
        assertFalse(SecondMonitor.acceptPage("http://user:secret@192.168.1.20/"))
        assertFalse(SecondMonitor.acceptPage("javascript:alert(1)"))
        assertFalse(SecondMonitor.acceptPage("file:///sdcard/a.html"))
    }

    @Test
    fun theSkillNamesTheRealLimits() {
        val skill = StarterSkills.skills.getValue("second-monitor")
        assertTrue(skill.contains("extended display"))
        assertTrue(skill.contains("Nothing was mirrored.") || skill.contains("cannot add a virtual monitor"))
        assertTrue(skill.contains("Same Wi-Fi is not a shell"))
        assertTrue(skill.contains("Do not ask for a password"))
        assertTrue(skill.contains("Do not invent"))
        assertTrue(skill.contains("Do not change, repack, or sell"))
        assertTrue(skill.contains("second_monitor"))
        val audit = SkillAudit.check(skill, "second-monitor")
        assertFalse(audit.findings.joinToString { it.kind }, audit.blocked)
    }
}
