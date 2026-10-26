package com.forge.android.tools

object DevicePackages {
    private val valid=Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    fun parse(output: String): List<String> = output.lineSequence()
        .map { it.trim().removePrefix("package:") }
        .filter { it.matches(valid) }.distinct().toList()
}
