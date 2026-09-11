package org.minekot.inspections.loader.runtime

import org.minekot.inspections.loader.ResolvedRulesChannel
import org.minekot.inspections.loader.RulesIndexVerificationReceipt
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import kotlin.io.path.*

/** Durable cache for the latest verified signed channel index.
 * @property root shared rules cache root.
 */
public class RulesChannelIndexCache(public val root: Path) {
    /** Loads verified cached channel bytes and receipt, or null when absent or corrupt. */
    public fun load(): CachedRulesChannel? = runCatching(::loadValid).getOrNull()

    private fun loadValid(): CachedRulesChannel? {
        val indexPath = root.resolve(INDEX_PATH)
        val receiptPath = root.resolve(RECEIPT_PATH)
        if (!indexPath.isRegularFile() || !receiptPath.isRegularFile()) return null
        val indexBytes = indexPath.readBytes()
        val lines = receiptPath.readLines()
        require(lines.size == RECEIPT_FIELDS) { "Invalid channel verification receipt." }
        val receipt = RulesIndexVerificationReceipt(
            indexSha256 = lines[INDEX_DIGEST],
            verifiedAt = java.time.Instant.parse(lines[VERIFIED_AT]),
            signerIdentity = lines[SIGNER_IDENTITY],
            issuer = lines[ISSUER],
            trustedRootVersion = lines[ROOT_VERSION],
        )
        if (indexBytes.sha256() != receipt.indexSha256) return null
        val etagPath = root.resolve(ETAG_PATH)
        val etag = etagPath.takeIf { path -> path.isRegularFile() }?.readText()?.ifBlank { null }
        return CachedRulesChannel(
            indexBytes,
            ResolvedRulesChannel(
                RulesChannelIndexCodec.decode(indexBytes),
                receipt,
                etag,
            ),
        )
    }

    /** Atomically promotes a newly verified channel snapshot under the shared cache lock. */
    public fun promote(indexBytes: ByteArray, resolved: ResolvedRulesChannel) {
        RulesCacheLocks.withLock(root) {
            writeAtomically(root.resolve(INDEX_PATH), indexBytes)
            writeAtomically(root.resolve(RECEIPT_PATH), resolved.receipt.encode().encodeToByteArray())
            writeAtomically(root.resolve(ETAG_PATH), resolved.etag.orEmpty().encodeToByteArray())
        }
    }

    private fun writeAtomically(target: Path, bytes: ByteArray) {
        target.parent.createDirectories()
        val temporary = createTempFile(target.parent, ".channel-", ".tmp")
        try {
            temporary.writeBytes(bytes)
            atomicReplace(temporary, target)
        } finally {
            temporary.deleteIfExists()
        }
    }

    private companion object {
        const val INDEX_PATH = "channel/index.json"
        const val RECEIPT_PATH = "channel/index.receipt"
        const val ETAG_PATH = "channel/index.etag"
        const val RECEIPT_FIELDS = 5
        const val INDEX_DIGEST = 0
        const val VERIFIED_AT = 1
        const val SIGNER_IDENTITY = 2
        const val ISSUER = 3
        const val ROOT_VERSION = 4
    }
}

/** Cached raw bytes and decoded verified channel snapshot.
 * @property indexBytes exact verified index bytes.
 * @property resolved decoded channel and receipt.
 */
public data class CachedRulesChannel(
    public val indexBytes: ByteArray,
    public val resolved: ResolvedRulesChannel,
)

private fun RulesIndexVerificationReceipt.encode(): String =
    listOf(indexSha256, verifiedAt.toString(), signerIdentity, issuer, trustedRootVersion).joinToString("\n")

@Suppress("ResolvedApiPreference")
private fun atomicReplace(source: Path, target: Path): Path = Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
