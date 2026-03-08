package com.flyfishxu.kadb.cert

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.random.Random

class OkioFilePrivateKeyStore(
    private val privateKeyPath: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM
) : KadbPrivateKeyStore {

    override fun readPrivateKeyPem(): ByteArray? {
        return readOrNull(privateKeyPath)
    }

    override fun writePrivateKeyPemAtomic(privateKeyPem: ByteArray) {
        writeAtomic(privateKeyPath, privateKeyPem)
    }

    override fun clear() {
        deleteIfExists(privateKeyPath)
    }

    private fun readOrNull(path: Path): ByteArray? {
        if (!fileSystem.exists(path)) return null
        return fileSystem.read(path) { readByteArray() }
    }

    private fun writeAtomic(path: Path, payload: ByteArray) {
        val tempPath = stageWrite(path, payload)
        try {
            commitStagedWrite(tempPath, path)
        } finally {
            cleanupTemp(tempPath)
        }
    }

    private fun deleteIfExists(path: Path) {
        if (fileSystem.exists(path)) {
            fileSystem.delete(path)
        }
    }

    private fun stageWrite(path: Path, payload: ByteArray): Path {
        val parent = path.parent
        if (parent != null && !fileSystem.exists(parent)) {
            fileSystem.createDirectories(parent)
        }

        val tempName = "${path.name}.tmp-${Random.nextInt().toUInt().toString(16)}"
        val tempPath = parent?.resolve(tempName) ?: tempName.toPath()

        fileSystem.write(tempPath) {
            write(payload)
        }
        return tempPath
    }

    private fun commitStagedWrite(tempPath: Path, path: Path) {
        fileSystem.atomicMove(tempPath, path)
    }

    private fun cleanupTemp(path: Path) {
        if (fileSystem.exists(path)) {
            runCatching { fileSystem.delete(path) }
        }
    }
}
