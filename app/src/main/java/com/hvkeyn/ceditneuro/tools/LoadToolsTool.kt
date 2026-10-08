package com.hvkeyn.ceditneuro.tools

import kotlinx.serialization.json.JsonObject

/** Puts one optional tool group into the next request. The core tools stay loaded. */
class LoadToolsTool(private val session: ToolSession) : Tool {
    override val name = "load_tools"
    override val description =
        "Load the schema for a tool group before calling those tools. " +
            "build: install_jdk, install_android_sdk, install_runtime, install_program, install_module. " +
            "device: install_apk, web_to_app, uninstall_apk, net_info, shizuku_exec, fetch_system_layout, execute_system_action, " +
            "device_status, list_apps, open_settings, clipboard, open_file. " +
            "remote: remote_connect, remote_list, remote_read, remote_write, remote_put, remote_get, ssh_exec, browse_page, second_monitor. " +
            "desk: notifications, mail_list, mail_read, mail_send. " +
            "study: research_log, research_report, research_figure, research_plot, research_run, video_brief, web_search, calculate, reference, capture_dump, read_dump, net_audit. " +
            "health: health_log, health_panel, health_trend, health_index. " +
            "skills: find_skills, review_skill. " +
            "design: design_system. " +
            "debug: debug_case. " +
            "video: render_video, sound_effect, make_music, voiceover. " +
            "learn: learn. " +
            "harness: harness_rrsi."
    override val parameters = objectSchema(
        properties = mapOf(
            "group" to stringProp("One of: build, device, remote, desk, study, health, skills, design, debug, video, learn, harness."),
        ),
        required = listOf("group"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val group = args.stringArg("group")?.trim()?.lowercase().orEmpty()
        if (group.isEmpty()) return ToolResult.error("Missing 'group'.")
        val text = session.load(group)
        return if (text.startsWith("Unknown")) ToolResult.error(text) else ToolResult.ok(text)
    }
}
