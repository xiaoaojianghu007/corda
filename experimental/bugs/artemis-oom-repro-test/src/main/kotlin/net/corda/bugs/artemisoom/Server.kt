package net.corda.bugs.artemisoom

import net.corda.node.main
import net.corda.nodeapi.internal.network.NetworkBootstrapper
import org.apache.commons.io.FileUtils
import java.io.File

fun main(args: Array<String>) {
    writeConfig()
    copyCordaJar()
    bootstrapNetwork()
    moveToTempFolder()
    runCorda()
}

private const val nodeName = "Foo"

private const val cordaJar = "node/capsule/build/libs/corda-3.0-SNAPSHOT.jar"

private val runtimeDirectory = File(System.getProperty("user.dir")).resolve("temp")

private fun copyCordaJar() {
    val target = runtimeDirectory.resolve("corda.jar")
    val jarFile1 = runtimeDirectory.resolve("../$cordaJar")
    val jarFile2 = runtimeDirectory.resolve("../../../../$cordaJar")
    if (!copyIfExists(jarFile1, target)) {
        if (!copyIfExists(jarFile2, target)) {
            throw Exception("Build Corda before proceeding")
        }
    }
}

private fun copyIfExists(src: File, dest: File): Boolean {
    if (src.exists()) {
        src.copyTo(dest)
        return true
    }
    return false
}

private fun writeConfig() {
    println("Generating config file ...")
    if (runtimeDirectory.exists()) {
        runtimeDirectory.deleteRecursively()
    }
    runtimeDirectory.mkdir()
    System.setProperty("user.dir", runtimeDirectory.absolutePath)
    val classloader = Thread.currentThread().contextClassLoader
    classloader.getResourceAsStream("node.conf").use {
        val config = it.bufferedReader().readText()
        runtimeDirectory.resolve("$nodeName.conf").printWriter().use {
            out -> out.println(config)
        }
    }
    println("Config file: $runtimeDirectory/node.conf")
}

private fun runCorda(vararg args: String) {
    FileUtils.forceDeleteOnExit(runtimeDirectory)
    main(arrayOf("--config-file", "$runtimeDirectory/node.conf", *args))
}

private fun bootstrapNetwork() {
    NetworkBootstrapper().bootstrap(runtimeDirectory.toPath())
}

private fun moveToTempFolder() {
    val nodeDirectory = runtimeDirectory.resolve(nodeName)
    FileUtils.copyDirectory(nodeDirectory, runtimeDirectory)
}
