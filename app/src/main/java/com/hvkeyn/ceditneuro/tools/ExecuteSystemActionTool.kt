package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.shizuku.ShizukuCommandRunner
import kotlinx.serialization.json.JsonObject

/**
 * Taps one point on the screen. Coordinates come from the model as integers.
 * Needs the same shell access as [ShizukuExecTool].
 */
class ExecuteSystemActionTool(
    private val runner: ShizukuCommandRunner,
    private val prepare: suspend () -> String?,
) : Tool {
    override val name = "execute_system_action"
    override val description =
        "Tap one point on the screen. paramX and paramY are the tap=X,Y center from " +
            "fetch_system_layout, not a corner of the bounds. Requires Shizuku or root, same as shizuku_exec. " +
            "Use it only for the UI automation the user asked for."
    override val parameters = objectSchema(
        properties = mapOf(
            "paramX" to intProp("Horizontal pixel coordinate of the tap."),
            "paramY" to intProp("Vertical pixel coordinate of the tap."),
        ),
        required = listOf("paramX", "paramY"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val paramX = args.intArg("paramX")
            ?: return ToolResult.error("Missing integer 'paramX'.")
        val paramY = args.intArg("paramY")
            ?: return ToolResult.error("Missing integer 'paramY'.")
        if (paramX !in 0..MAX_PIXEL || paramY !in 0..MAX_PIXEL) {
            return ToolResult.error("paramX and paramY must be between 0 and $MAX_PIXEL.")
        }

        val blocked = prepare()
        if (blocked != null) return ToolResult.error(blocked)

        val command = "input tap $paramX $paramY"
        val raw = runCatching { runner.executeShizukuCommand(command, timeoutSeconds = 15) }
            .getOrElse { error -> return ToolResult.error(error.message ?: "Tap failed.") }
        if (shellFailed(raw)) return ToolResult.error(raw)
        return ToolResult.ok("Action completed: $command")
    }

    private companion object {
        const val MAX_PIXEL = 20_000
    }
}
