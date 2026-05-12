package org.babyfish.jimmer.ksp.incremental

import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import java.io.File
import java.security.MessageDigest

class DtoFileWatcher(
    private val environment: SymbolProcessorEnvironment,
    private val dtoDirs: Collection<String>
) {
    data class DtoChange(
        val path: String,
        val hash: Long,
        val isNew: Boolean,
        val isDeleted: Boolean = false
    )
    
    private var previousHashes: Map<String, Long> = emptyMap()
    private var initialized = false
    
    fun initialize(previousHashes: Map<String, Long>) {
        this.previousHashes = previousHashes
        this.initialized = true
    }
    
    fun detectChanges(): Collection<DtoChange> {
        if (!initialized) {
            initialized = true
        }
        
        val currentHashes = computeCurrentHashes()
        val changes = mutableListOf<DtoChange>()
        
        for ((path, hash) in currentHashes) {
            when {
                !previousHashes.containsKey(path) -> {
                    changes.add(DtoChange(path, hash, isNew = true))
                }
                previousHashes[path] != hash -> {
                    changes.add(DtoChange(path, hash, isNew = false))
                }
            }
        }
        
        for ((path, _) in previousHashes) {
            if (!currentHashes.containsKey(path)) {
                changes.add(DtoChange(path, 0, isNew = false, isDeleted = true))
            }
        }
        
        return changes
    }
    
    fun getCurrentHashes(): Map<String, Long> = computeCurrentHashes()
    
    private fun computeCurrentHashes(): Map<String, Long> {
        val hashes = mutableMapOf<String, Long>()
        for (dir in dtoDirs) {
            val baseDir = findBaseDir(dir)
            if (!baseDir.exists()) continue
            
            baseDir.walkTopDown()
                .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
                .forEach { file ->
                    try {
                        hashes[file.absolutePath] = computeFileHash(file)
                    } catch (e: Exception) {
                        environment.logger.warn("Failed to compute hash for ${file.absolutePath}: ${e.message}")
                    }
                }
        }
        return hashes
    }
    
    private fun findBaseDir(path: String): File {
        val parts = path.split("/")
        for (i in parts.indices.reversed()) {
            val dir = File(parts.subList(0, i + 1).joinToString("/"))
            if (dir.exists()) return dir
        }
        return File(path)
    }
    
    private fun computeFileHash(file: File): Long {
        val content = file.readBytes()
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(content)
        
        var hash = 0L
        for (i in 0 until minOf(8, digest.size)) {
            hash = hash or ((digest[i].toLong() and 0xFF) shl (i * 8))
        }
        return hash
    }
    
    fun hasChanges(): Boolean = detectChanges().isNotEmpty()
    
    fun getAllDtoFiles(): List<File> {
        val files = mutableListOf<File>()
        for (dir in dtoDirs) {
            val baseDir = findBaseDir(dir)
            if (!baseDir.exists()) continue
            
            baseDir.walkTopDown()
                .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
                .forEach { files.add(it) }
        }
        return files
    }
}
