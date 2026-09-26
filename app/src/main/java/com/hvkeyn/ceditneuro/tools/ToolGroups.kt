package com.hvkeyn.ceditneuro.tools

/**
 * Tools that ride on every request, and groups the model loads when a task needs them.
 * The core set stays in the cached prefix. A group is added only after [ToolSession.load].
 */
object ToolGroups {
    val core: List<String> = listOf(
        "list_dir",
        "read_file",
        "write_file",
        "edit_file",
        "grep",
        "glob",
        "mkdir",
        "delete_path",
        "move_path",
        "git_status",
        "git_diff",
        "run_command",
        "zip_paths",
        "http_request",
        "set_timer",
        "load_tools",
        "reader_note",
        "reader_sketch",
        "list_skills",
        "read_skill",
        "save_skill",
        "append_skill",
        "delete_skill",
        "remember",
        "search_sessions",
    )

    val groups: Map<String, List<String>> = linkedMapOf(
        "build" to listOf(
            "install_jdk",
            "install_android_sdk",
            "install_runtime",
            "install_program",
            "install_module",
        ),
        "device" to listOf(
            "install_apk",
            "uninstall_apk",
            "net_info",
            "shizuku_exec",
            "fetch_system_layout",
            "execute_system_action",
        ),
        "remote" to listOf(
            "remote_connect",
            "remote_list",
            "remote_read",
            "remote_write",
            "remote_put",
            "remote_get",
            "space_sync",
            "ssh_exec",
            "browse_page",
            "remote_mkdir",
            "remote_delete",
            "remote_rename",
        ),
        "desk" to listOf(
            "notifications",
            "notification_reply",
            "notification_dismiss",
            "mail_list",
            "mail_read",
            "mail_send",
        ),
    )

    fun groupOf(toolName: String): String? =
        groups.entries.firstOrNull { toolName in it.value }?.key

    fun visibleNames(loaded: Set<String>): Set<String> {
        val names = LinkedHashSet(core)
        for ((name, members) in groups) {
            if (name in loaded) names += members
        }
        return names
    }

    fun forFocus(workFocus: String): Set<String> = when (workFocus) {
        "build" -> setOf("build")
        "remote" -> setOf("remote")
        else -> emptySet()
    }
}

/** Which optional groups are in the request for this run. */
class ToolSession(initial: Set<String>) {
    private val loaded = initial.toMutableSet()

    fun visible(registry: ToolRegistry): List<Tool> {
        val names = ToolGroups.visibleNames(loaded)
        return registry.all.filter { it.name in names }
    }

    fun load(group: String): String {
        val members = ToolGroups.groups[group]
            ?: return "Unknown group '$group'. Groups: ${ToolGroups.groups.keys.joinToString(", ")}."
        if (!loaded.add(group)) return "Group $group is already loaded: ${members.joinToString(", ")}."
        return "Loaded $group: ${members.joinToString(", ")}. They can be called on the next step."
    }

    /** The model named a real tool whose schema was not in this request. */
    fun holdUntilLoaded(toolName: String): String? {
        val group = ToolGroups.groupOf(toolName) ?: return null
        if (group in loaded) return null
        loaded += group
        return "'$toolName' is in the $group group. Its schema is loaded now. Call it again."
    }
}
