package abitool

import org.jetbrains.kotlin.abi.tools.AbiFilters
import org.jetbrains.kotlin.abi.tools.AbiTools
import org.jetbrains.kotlin.abi.tools.KlibTarget
import java.io.File
import kotlin.system.exitProcess

private const val TARGETS_PREFIX = "// Targets: ["

/** The targets a committed klib dump covers, from its `// Targets: [a, b]` header; empty when there is no dump. */
internal fun baselineTargets(klibDump: File): Set<String> {
    if (!klibDump.exists()) return emptySet()
    val header = klibDump.useLines { lines -> lines.firstOrNull { it.startsWith(TARGETS_PREFIX) } } ?: return emptySet()
    return header.removePrefix(TARGETS_PREFIX).substringBefore(']').split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}

// Usage: abitool <dump|check> <buildDir> <apiDir> <module>
// dump writes <apiDir>/<module>.api and <module>.klib.api; check compares and exits 1 on drift.
fun main(args: Array<String>) {
    require(args.size == 4) { "usage: abitool <dump|check> <buildDir> <apiDir> <module>" }
    val (mode, buildDirArg, apiDirArg, module) = args
    val tasks = File(buildDirArg, "tasks")
    val apiDir = File(apiDirArg)
    val tools = AbiTools.getInstance()

    val jar = File(tasks, "_${module}_jarJvm/$module-jvm.jar")
    val jvmText = StringBuilder().also { tools.printJvmDump(it, listOf(jar), AbiFilters.EMPTY) }.toString()

    val prefix = "_${module}_compile"
    val klibs = tasks.listFiles()!!.filter { it.name.startsWith(prefix) && !it.name.contains("Test") && !it.name.endsWith("Release") }
        .mapNotNull { dir -> File(dir, "$module.klib").takeIf { it.exists() }?.let { dir.name.removePrefix(prefix).removeSuffix("Debug") to it } }
        .filter { (name, _) -> name != "Jvm" && name != "Android" && !name.startsWith("Metadata") }
        .sortedBy { it.first }
    val merged = tools.createKlibDump()
    val built = klibs.map { (name, _) -> name.replaceFirstChar { it.lowercase() } }
    for ((index, klib) in klibs.map { it.second }.withIndex()) {
        merged.merge(tools.extractKlibAbi(klib, KlibTarget(built[index])))
    }
    val klibText = merged.print(StringBuilder()).toString()

    val jvmFile = File(apiDir, "$module.api")
    val klibFile = File(apiDir, "$module.klib.api")

    // The Toolchain builds Apple targets only on a Mac and skips them silently elsewhere, so a build that lacks a
    // target the baseline has would dump or diff as if that target's API were removed.
    val missing = baselineTargets(klibFile) - built.toSet()
    if (missing.isNotEmpty()) {
        System.err.println(
            "abitool: the build has no klib for ${missing.sorted().joinToString()}, which ${klibFile.name} covers. " +
                "Run ./kotlin build on macOS first; to drop a target on purpose, delete ${klibFile.name} and dump again.",
        )
        exitProcess(1)
    }
    when (mode) {
        "dump" -> {
            apiDir.mkdirs(); jvmFile.writeText(jvmText); klibFile.writeText(klibText)
            println("wrote ${jvmFile} and ${klibFile} (${klibs.size} klib targets)")
        }
        "check" -> {
            val tmp = File.createTempFile("abi", "").also { it.delete(); it.mkdirs() }
            File(tmp, jvmFile.name).writeText(jvmText); File(tmp, klibFile.name).writeText(klibText)
            val diffs = listOf(jvmFile, klibFile).mapNotNull { tools.filesDiff(it, File(tmp, it.name)) }
            if (diffs.isEmpty()) println("ABI check passed (${klibs.size} klib targets)")
            else { diffs.forEach(::println); exitProcess(1) }
        }
        else -> error("unknown mode $mode")
    }
}
