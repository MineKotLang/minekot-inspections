@file:Suppress("ResolvedApiPreference")

package org.minekot.inspections.loader.runtime

import org.minekot.inspections.loader.*
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.*

/** Process-safe content-addressed rules cache.
 * @property root cache root containing immutable content-addressed data.
 */
public class ContentAddressedRulesCache(public val root: Path) {
    private val normalizedRoot: Path = root.toAbsolutePath().normalize()

    /** Returns verified blob when receipt exists and digest still matches. */
    public fun leaseVerified(jarSha256: String, manifestSha256: String): RulesCacheLease? {
        val jar = blobPath(jarSha256)
        val receipt = receiptPath(manifestSha256)
        if (!jar.isRegularFile() || !receipt.isRegularFile()) return null
        val decodedReceipt = runCatching { receipt.readText().decodeReceipt() }.getOrNull()
        if (decodedReceipt == null ||
            decodedReceipt.manifestSha256 != manifestSha256 ||
            decodedReceipt.jarSha256 != jarSha256
        ) {
            quarantine(receipt)
            return null
        }
        if (jar.readBytes().sha256() != jarSha256) {
            quarantine(jar)
            return null
        }
        liveLeases.computeIfAbsent(leaseKey(jarSha256)) { AtomicInteger() }.incrementAndGet()
        Files.setLastModifiedTime(jar, FileTime.from(Instant.now()))
        return lease(jarSha256, jar)
    }

    /** Loads exact cached manifest and receipt when both remain present. */
    public fun loadVerifiedMetadata(manifestSha256: String): Pair<ByteArray, RulesVerificationReceipt>? {
        val manifest = manifestPath(manifestSha256)
        val receipt = receiptPath(manifestSha256)
        if (!manifest.isRegularFile() || !receipt.isRegularFile()) return null
        val manifestBytes = manifest.readBytes()
        if (manifestBytes.sha256() != manifestSha256) {
            quarantine(manifest)
            return null
        }
        val decodedReceipt = runCatching { receipt.readText().decodeReceipt() }.getOrNull()
        if (decodedReceipt?.manifestSha256 != manifestSha256) {
            quarantine(receipt)
            return null
        }
        return manifestBytes to decodedReceipt
    }

    /** Returns strict manifests whose bytes and receipts remain verified in local cache. */
    public fun installedManifests(): List<RulesManifest> =
        root.resolve("manifests").takeIf(Path::isDirectory)?.listDirectoryEntries("*.json").orEmpty()
            .mapNotNull { path ->
                val digest = path.fileName.toString().removeSuffix(".json")
                loadVerifiedMetadata(digest)?.first?.let { bytes ->
                    runCatching { RulesManifestCodec.decode(bytes) }.getOrNull()
                }
            }
            .sortedBy(RulesManifest::rulesVersion)

    /** Quarantines one verified generation after classloading or catalog activation fails. */
    public fun quarantineGeneration(jarSha256: String, manifestSha256: String): Boolean {
        require(jarSha256.matches(SHA256_PATTERN) && manifestSha256.matches(SHA256_PATTERN)) {
            "Quarantined generation digest is invalid."
        }
        if ((liveLeases[leaseKey(jarSha256)]?.get() ?: 0) > 0) return false
        var quarantined = false
        withCacheLock {
            listOf(blobPath(jarSha256), manifestPath(manifestSha256), receiptPath(manifestSha256))
                .filter(Path::isRegularFile)
                .forEach { path ->
                    quarantine(path)
                    quarantined = true
                }
        }
        return quarantined
    }

    /** Atomically promotes verified bytes and receipt, returning active lease. */
    public fun promote(
        jarBytes: ByteArray,
        manifestBytes: ByteArray,
        receipt: RulesVerificationReceipt,
    ): RulesCacheLease {
        root.createDirectories()
        withCacheLock {
            val jar = blobPath(receipt.jarSha256)
            val manifest = manifestPath(receipt.manifestSha256)
            writeContentAddressed(jar, jarBytes, receipt.jarSha256)
            writeContentAddressed(manifest, manifestBytes, receipt.manifestSha256)
            writeAtomically(receiptPath(receipt.manifestSha256), receipt.encode().encodeToByteArray())
        }
        liveLeases.computeIfAbsent(leaseKey(receipt.jarSha256)) { AtomicInteger() }.incrementAndGet()
        return lease(receipt.jarSha256, blobPath(receipt.jarSha256))
    }

    /** Records successful activation and preserves replaced generation as last known good. */
    public fun recordActivation(scope: String, generation: RulesCachedGeneration): RulesActivationRecord {
        require(scope.isNotBlank() && '\n' !in scope) { "Rules activation scope is invalid." }
        require(generation.manifestSha256.matches(SHA256_PATTERN)) { "Activation manifest digest is invalid." }
        require(generation.jarSha256.matches(SHA256_PATTERN)) { "Activation JAR digest is invalid." }
        require(blobPath(generation.jarSha256).isRegularFile()) { "Activated rules JAR is absent from cache." }
        val previous = loadActivation(scope)
        val record = RulesActivationRecord(
            scope,
            generation,
            previous?.active?.takeUnless { active -> active == generation } ?: previous?.lastKnownGood,
        )
        withCacheLock {
            writeReplacingAtomically(activationPath(scope), record.encode().encodeToByteArray())
        }
        return record
    }

    /** Loads durable active and last-known-good state for one scope. */
    public fun loadActivation(scope: String): RulesActivationRecord? =
        activationPath(scope).takeIf(Path::isRegularFile)?.let { path ->
            runCatching { path.readText().decodeActivation() }
                .getOrNull()
                ?.takeIf { record -> record.scope == scope }
        }

    /** Removes unprotected stale blobs while retaining active, LKG, pinned, recent, and newest generations. */
    public fun cleanup(
        pinnedJarSha256: Set<String> = emptySet(),
        now: Instant = Instant.now(),
        recentAge: Duration = Duration.ofDays(DEFAULT_RECENT_DAYS),
        newestCount: Int = DEFAULT_NEWEST_COUNT,
    ): Int {
        require(pinnedJarSha256.all { digest -> digest.matches(SHA256_PATTERN) }) { "Pinned JAR digest is invalid." }
        require(!recentAge.isNegative) { "Recent retention age must not be negative." }
        require(newestCount >= 0) { "Newest retention count must not be negative." }
        val blobs = root.resolve("blobs").listDirectoryEntries("*.jar")
            .filter(Path::isRegularFile)
            .sortedByDescending { path -> path.getLastModifiedTime().toInstant() }
        val durable = loadAllActivations().flatMap { record ->
            listOfNotNull(record.active.jarSha256, record.lastKnownGood?.jarSha256)
        }.toSet()
        val live = liveLeases.entries.asSequence()
            .filter { (key, count) -> key.root == normalizedRoot && count.get() > 0 }
            .map { (key, _) -> key.digest }
            .toSet()
        val recentThreshold = now.minus(recentAge)
        val protectedDigests = pinnedJarSha256 + durable + live +
            blobs.take(newestCount).mapNotNull(Path::jarDigest) +
            blobs.filter { path -> path.getLastModifiedTime().toInstant() >= recentThreshold }
                .mapNotNull(Path::jarDigest)
        return blobs.count { path ->
            val digest = path.jarDigest() ?: return@count false
            if (digest in protectedDigests) return@count false
            runCatching { path.deleteIfExists() }.getOrDefault(false)
        }
    }

    private fun lease(digest: String, jar: Path): RulesCacheLease =
        object : RulesCacheLease {
            private var closed: Boolean = false
            override val jarPath: Path = jar

            override fun close() {
                if (!closed) {
                    liveLeases[leaseKey(digest)]?.let { count ->
                        if (count.decrementAndGet() <= 0) liveLeases.remove(leaseKey(digest), count)
                    }
                    closed = true
                }
            }
        }

    private fun writeAtomically(target: Path, bytes: ByteArray) {
        if (target.exists()) return
        target.parent.createDirectories()
        val temporary = createTempFile(target.parent, ".download-", ".tmp")
        try {
            FileChannel.open(
                temporary,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { channel ->
                channel.write(java.nio.ByteBuffer.wrap(bytes))
                channel.force(true)
            }
            val moved = runCatching { atomicPromote(temporary, target) }
                .recoverCatching { temporary.moveTo(target) }
            if (moved.isFailure && !target.isRegularFile()) moved.getOrThrow()
        } finally {
            temporary.deleteIfExists()
        }
    }

    private fun writeReplacingAtomically(target: Path, bytes: ByteArray) {
        target.parent.createDirectories()
        val temporary = createTempFile(target.parent, ".state-", ".tmp")
        try {
            FileChannel.open(
                temporary,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { channel ->
                channel.write(java.nio.ByteBuffer.wrap(bytes))
                channel.force(true)
            }
            runCatching { Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING) }
                .recoverCatching { Files.move(temporary, target, REPLACE_EXISTING) }
                .getOrElse { failure -> throw failure }
        } finally {
            temporary.deleteIfExists()
        }
    }

    private fun writeContentAddressed(target: Path, bytes: ByteArray, expectedSha256: String) {
        if (target.isRegularFile() && target.readBytes().sha256() != expectedSha256) quarantine(target)
        writeAtomically(target, bytes)
        require(target.readBytes().sha256() == expectedSha256) {
            "Content-addressed cache promotion produced an invalid digest."
        }
    }

    private fun quarantine(path: Path) {
        val quarantine = root.resolve("quarantine").resolve("${path.fileName}.${System.nanoTime()}.corrupt")
        quarantine.parent.createDirectories()
        path.moveTo(quarantine, overwrite = true)
    }

    private fun withCacheLock(action: () -> Unit) = RulesCacheLocks.withLock(root, action)

    private fun loadAllActivations(): List<RulesActivationRecord> =
        root.resolve("activations").takeIf(Path::isDirectory)?.listDirectoryEntries("*.state").orEmpty()
            .mapNotNull { path -> runCatching { path.readText().decodeActivation() }.getOrNull() }

    private fun leaseKey(digest: String): LeaseKey = LeaseKey(normalizedRoot, digest)
    private fun activationPath(scope: String): Path =
        root.resolve("activations").resolve("${scope.encodeToByteArray().sha256()}.state")

    private fun blobPath(sha256: String): Path = root.resolve("blobs").resolve("${sha256}.jar")
    private fun manifestPath(sha256: String): Path = root.resolve("manifests").resolve("${sha256}.json")
    private fun receiptPath(sha256: String): Path = root.resolve("receipts").resolve("${sha256}.receipt")

    private companion object {
        val SHA256_PATTERN: Regex = Regex("[0-9a-f]{64}")
        val liveLeases: ConcurrentHashMap<LeaseKey, AtomicInteger> = ConcurrentHashMap()
        private const val DEFAULT_RECENT_DAYS = 30L
        private const val DEFAULT_NEWEST_COUNT = 5
    }
}

private data class LeaseKey(val root: Path, val digest: String)

private fun Path.jarDigest(): String? = fileName.toString().removeSuffix(".jar").takeIf { digest ->
    digest.matches(Regex("[0-9a-f]{64}"))
}

private fun RulesActivationRecord.encode(): String = buildList {
    add(scope)
    add(active.encodeGeneration())
    add(lastKnownGood?.encodeGeneration().orEmpty())
}.joinToString("\n")

private fun RulesCachedGeneration.encodeGeneration(): String =
    listOf(version, manifestSha256, jarSha256, activatedAt.toString()).joinToString("|")

private fun String.decodeActivation(): RulesActivationRecord {
    val lines = lineSequence().toList()
    require(lines.size == ACTIVATION_FIELD_COUNT) { "Invalid rules activation record." }
    return RulesActivationRecord(
        lines[0],
        lines[1].decodeGeneration(),
        lines[2].takeIf(String::isNotEmpty)?.decodeGeneration(),
    )
}

private fun String.decodeGeneration(): RulesCachedGeneration {
    val fields = split('|')
    require(fields.size == GENERATION_FIELD_COUNT) { "Invalid cached rules generation." }
    require(
        fields[GENERATION_MANIFEST_INDEX].matches(Regex("[0-9a-f]{64}")) &&
            fields[GENERATION_JAR_INDEX].matches(Regex("[0-9a-f]{64}")),
    ) {
        "Invalid cached generation digest."
    }
    return RulesCachedGeneration(
        fields[GENERATION_VERSION_INDEX],
        fields[GENERATION_MANIFEST_INDEX],
        fields[GENERATION_JAR_INDEX],
        Instant.parse(fields[GENERATION_ACTIVATED_AT_INDEX]),
    )
}

@Suppress("ResolvedApiPreference")
private fun atomicPromote(source: Path, target: Path): Path = Files.move(source, target, ATOMIC_MOVE)

private fun RulesVerificationReceipt.encode(): String =
    listOf(
        this.manifestSha256,
        this.jarSha256,
        this.verifiedAt.toString(),
        this.signerIdentity,
        this.issuer,
        this.trustedRootVersion,
    ).joinToString("\n")

private fun String.decodeReceipt(): RulesVerificationReceipt {
    val lines = this.lines()
    require(lines.size == RECEIPT_FIELD_COUNT) { "Invalid rules verification receipt." }
    require(
        lines[MANIFEST_DIGEST_INDEX].matches(Regex("[0-9a-f]{64}")) &&
            lines[JAR_DIGEST_INDEX].matches(Regex("[0-9a-f]{64}")),
    ) {
        "Invalid digest in rules verification receipt."
    }
    require(lines.drop(IDENTITY_INDEX).all(String::isNotBlank)) { "Invalid identity in rules verification receipt." }
    return RulesVerificationReceipt(
        manifestSha256 = lines[MANIFEST_DIGEST_INDEX],
        jarSha256 = lines[JAR_DIGEST_INDEX],
        verifiedAt = java.time.Instant.parse(lines[VERIFIED_AT_INDEX]),
        signerIdentity = lines[IDENTITY_INDEX],
        issuer = lines[ISSUER_INDEX],
        trustedRootVersion = lines[ROOT_VERSION_INDEX],
    )
}

private const val RECEIPT_FIELD_COUNT = 6
private const val MANIFEST_DIGEST_INDEX = 0
private const val JAR_DIGEST_INDEX = 1
private const val VERIFIED_AT_INDEX = 2
private const val IDENTITY_INDEX = 3
private const val ISSUER_INDEX = 4
private const val ROOT_VERSION_INDEX = 5
private const val ACTIVATION_FIELD_COUNT = 3
private const val GENERATION_FIELD_COUNT = 4
private const val GENERATION_VERSION_INDEX = 0
private const val GENERATION_MANIFEST_INDEX = 1
private const val GENERATION_JAR_INDEX = 2
private const val GENERATION_ACTIVATED_AT_INDEX = 3
