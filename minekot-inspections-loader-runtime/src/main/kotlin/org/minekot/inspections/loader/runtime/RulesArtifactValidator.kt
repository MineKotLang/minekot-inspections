package org.minekot.inspections.loader.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Validates artifact digest, size, ZIP structure, and classloader boundary before loading. */
public object RulesArtifactValidator {
    private val forbiddenPrefixes: List<String> = listOf(
        "com/intellij/",
        "dev/detekt/",
        "kotlin/",
        "org/jetbrains/kotlin/",
        "org/minekot/inspections/",
        "org/minekot/inspections/loader/runtime/",
    )

    /** Fails when bytes do not exactly match signed manifest constraints. */
    public fun validate(
        bytes: ByteArray,
        expectedSize: Long,
        expectedSha256: String,
        expectedCatalogProvider: String,
    ) {
        require(bytes.size.toLong() == expectedSize) { "Rules JAR size does not match manifest." }
        require(bytes.sha256() == expectedSha256) { "Rules JAR SHA-256 does not match manifest." }
        require(
            bytes.size >= MINIMUM_ZIP_HEADER_BYTES &&
                bytes[ZIP_MAGIC_FIRST_INDEX] == 'P'.code.toByte() &&
                bytes[ZIP_MAGIC_SECOND_INDEX] == 'K'.code.toByte(),
        ) {
            "Rules artifact is not a ZIP archive."
        }
        val names = mutableSetOf<String>()
        var serviceCount = 0
        var uncompressedBytes = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            generateSequence(input::getNextEntry).forEach { entry ->
                val name = entry.name
                require(isSafeEntryName(name)) {
                    "Unsafe rules JAR entry ${name}."
                }
                require(names.add(name)) { "Duplicate rules JAR entry ${name}." }
                require(!name.endsWith(".jar", ignoreCase = true)) { "Nested JAR is forbidden: ${name}." }
                if (name.endsWith(".class")) {
                    require(forbiddenPrefixes.none(name::startsWith)) { "Forbidden bundled class ${name}." }
                }
                val content = readBoundedEntry(input) { count ->
                    uncompressedBytes += count
                    require(uncompressedBytes <= MAXIMUM_UNCOMPRESSED_BYTES) {
                        "Rules JAR exceeds uncompressed byte limit."
                    }
                }
                if (name == CATALOG_SERVICE) {
                    serviceCount++
                    val providers = content.decodeToString()
                        .lineSequence()
                        .map(String::trim)
                        .filter { line -> line.isNotEmpty() && !line.startsWith('#') }
                        .toList()
                    require(providers == listOf(expectedCatalogProvider)) {
                        "Rules JAR catalog service does not match signed manifest."
                    }
                }
            }
        }
        require(serviceCount == 1) { "Rules JAR must contain exactly one catalog service entry." }
    }

    private fun isSafeEntryName(name: String): Boolean {
        if (name.isBlank() || name.startsWith('/') || name.contains('\\')) return false
        val segments = name.split('/')
        return segments.none { segment -> segment == "." || segment == ".." }
    }

    private fun readBoundedEntry(input: ZipInputStream, onRead: (Long) -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            onRead(count.toLong())
            if (output.size() + count <= MAXIMUM_SERVICE_BYTES) output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private const val CATALOG_SERVICE =
        "META-INF/services/org.minekot.inspections.core.MineKotInspectionCatalog"
    private const val MAXIMUM_SERVICE_BYTES: Int = 64 * 1024
    private const val MAXIMUM_UNCOMPRESSED_BYTES: Long = 64L * 1024L * 1024L
    private const val MINIMUM_ZIP_HEADER_BYTES: Int = 4
    private const val ZIP_MAGIC_FIRST_INDEX: Int = 0
    private const val ZIP_MAGIC_SECOND_INDEX: Int = 1
}

internal fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }
