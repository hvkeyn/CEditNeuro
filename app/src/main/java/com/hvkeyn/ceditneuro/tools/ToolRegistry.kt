package com.hvkeyn.ceditneuro.tools

class ToolRegistry(tools: List<Tool>) {

    private val byName: Map<String, Tool> = tools.associateBy { it.name }

    val all: List<Tool> = tools.toList()

    init {
        val duplicates = tools.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate tool names: $duplicates" }
    }

    fun find(name: String): Tool? = byName[name]
}
