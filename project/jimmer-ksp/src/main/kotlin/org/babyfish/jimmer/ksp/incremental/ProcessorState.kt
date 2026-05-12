package org.babyfish.jimmer.ksp.incremental

import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

class ProcessorState(
    private val environment: SymbolProcessorEnvironment
) {
    private val stateFileName = ".jimmer_ksp_state"
    
    data class State(
        val explicitClientApi: Boolean = false,
        val processedEntityTypes: Set<String> = emptySet(),
        val processedDtoTypes: Set<String> = emptySet(),
        val processedErrorTypes: Set<String> = emptySet(),
        val processedTxTypes: Set<String> = emptySet(),
        val processedTupleTypes: Set<String> = emptySet(),
        val dtoFileHashes: Map<String, Long> = emptyMap(),
        val schemaHash: Long = 0
    ) {
        fun toJson(): String = buildString {
            append("{")
            append("\"explicitClientApi\":$explicitClientApi,")
            append("\"processedEntityTypes\":[${processedEntityTypes.joinToString(",") { "\"$it\"" }}],")
            append("\"processedDtoTypes\":[${processedDtoTypes.joinToString(",") { "\"$it\"" }}],")
            append("\"processedErrorTypes\":[${processedErrorTypes.joinToString(",") { "\"$it\"" }}],")
            append("\"processedTxTypes\":[${processedTxTypes.joinToString(",") { "\"$it\"" }}],")
            append("\"processedTupleTypes\":[${processedTupleTypes.joinToString(",") { "\"$it\"" }}],")
            append("\"dtoFileHashes\":{")
            append(dtoFileHashes.entries.joinToString(",") { "\"${it.key}\":${it.value}" })
            append("},")
            append("\"schemaHash\":$schemaHash")
            append("}")
        }
        
        companion object {
            fun fromJson(json: String): State? {
                if (json.isBlank()) return null
                return try {
                    val map = parseJson(json)
                    State(
                        explicitClientApi = map["explicitClientApi"] as? Boolean ?: false,
                        processedEntityTypes = (map["processedEntityTypes"] as? List<*>)?.mapNotNull { it as? String }?.toSet() ?: emptySet(),
                        processedDtoTypes = (map["processedDtoTypes"] as? List<*>)?.mapNotNull { it as? String }?.toSet() ?: emptySet(),
                        processedErrorTypes = (map["processedErrorTypes"] as? List<*>)?.mapNotNull { it as? String }?.toSet() ?: emptySet(),
                        processedTxTypes = (map["processedTxTypes"] as? List<*>)?.mapNotNull { it as? String }?.toSet() ?: emptySet(),
                        processedTupleTypes = (map["processedTupleTypes"] as? List<*>)?.mapNotNull { it as? String }?.toSet() ?: emptySet(),
                        dtoFileHashes = (map["dtoFileHashes"] as? Map<*, *>)?.mapNotNull { (k, v) -> 
                            (k as? String)?.let { key -> (v as? Number)?.toLong()?.let { value -> key to value } }
                        }?.toMap() ?: emptyMap(),
                        schemaHash = (map["schemaHash"] as? Number)?.toLong() ?: 0
                    )
                } catch (e: Exception) {
                    null
                }
            }
            
            private fun parseJson(json: String): Map<String, Any?> {
                val result = mutableMapOf<String, Any?>()
                var i = 0
                val chars = json.trim()
                if (chars.first() != '{' || chars.last() != '}') return result
                i++ // skip '{'
                
                while (i < chars.length - 1) {
                    skipWhitespace(chars, i).let { i = it }
                    if (chars[i] == '}') break
                    
                    val key = parseString(chars, i)
                    i = key.second
                    skipWhitespace(chars, i)
                    if (chars[i] != ':') break
                    i++
                    
                    val value = parseValue(chars, i)
                    result[key.first] = value.first
                    i = value.second
                    
                    skipWhitespace(chars, i)
                    if (chars[i] == ',') i++
                }
                return result
            }
            
            private fun skipWhitespace(chars: String, i: Int): Int {
                var idx = i
                while (idx < chars.length && chars[idx] in " \t\n\r") idx++
                return idx
            }
            
            private fun parseString(chars: String, i: Int): Pair<String, Int> {
                var idx = i
                if (chars[idx] != '"') return "" to idx
                idx++
                val sb = StringBuilder()
                while (idx < chars.length && chars[idx] != '"') {
                    if (chars[idx] == '\\' && idx + 1 < chars.length) {
                        idx++
                        sb.append(chars[idx])
                    } else {
                        sb.append(chars[idx])
                    }
                    idx++
                }
                return sb.toString() to (idx + 1)
            }
            
            private fun parseValue(chars: String, i: Int): Pair<Any?, Int> {
                var idx = i
                idx = skipWhitespace(chars, idx)
                when {
                    chars[idx] == '"' -> {
                        val (str, end) = parseString(chars, idx)
                        return str to end
                    }
                    chars[idx] == '[' -> {
                        val list = mutableListOf<Any?>()
                        idx++
                        while (idx < chars.length && chars[idx] != ']') {
                            val (v, end) = parseValue(chars, idx)
                            list.add(v)
                            idx = end
                            idx = skipWhitespace(chars, idx)
                            if (chars[idx] == ',') idx++
                        }
                        return list to (idx + 1)
                    }
                    chars[idx] == '{' -> {
                        val map = mutableMapOf<String, Any?>()
                        idx++
                        while (idx < chars.length && chars[idx] != '}') {
                            idx = skipWhitespace(chars, idx)
                            if (chars[idx] == '}') break
                            val (k, end) = parseString(chars, idx)
                            idx = end
                            idx = skipWhitespace(chars, idx)
                            if (chars[idx] == ':') idx++
                            val (v, vend) = parseValue(chars, idx)
                            map[k] = v
                            idx = vend
                            idx = skipWhitespace(chars, idx)
                            if (chars[idx] == ',') idx++
                        }
                        return map to (idx + 1)
                    }
                    chars[idx] == 't' && chars.substring(idx).startsWith("true") -> {
                        return true to (idx + 4)
                    }
                    chars[idx] == 'f' && chars.substring(idx).startsWith("false") -> {
                        return false to (idx + 5)
                    }
                    chars[idx] == 'n' && chars.substring(idx).startsWith("null") -> {
                        return null to (idx + 4)
                    }
                    else -> {
                        val start = idx
                        while (idx < chars.length && chars[idx] in "0123456789.-") idx++
                        val numStr = chars.substring(start, idx)
                        return if (numStr.contains('.')) {
                            numStr.toDoubleOrNull()
                        } else {
                            numStr.toLongOrNull()
                        } to idx
                    }
                }
            }
        }
    }
    
    private var currentState: State = State()
    private var initialized = false
    private var saved = false
    
    fun initialize(): State {
        if (initialized) return currentState
        initialized = true
        
        currentState = loadState() ?: State()
        return currentState
    }
    
    private fun loadState(): State? {
        return try {
            environment.options["jimmer.ksp.state"]?.let {
                State.fromJson(it)
            }
        } catch (e: Exception) {
            environment.logger.warn("Failed to load processor state: ${e.message}")
            null
        }
    }
    
    fun update(transform: (State) -> State) {
        currentState = transform(currentState)
    }
    
    fun save() {
        if (saved) return
        saved = true
        
        val json = currentState.toJson()
        environment.codeGenerator.createNewFile(
            Dependencies(false),
            "",
            stateFileName,
            "json"
        ).use { output ->
            OutputStreamWriter(output, StandardCharsets.UTF_8).use { writer ->
                writer.write(json)
                writer.flush()
            }
        }
    }
    
    fun getState(): State = currentState
    
    fun getProcessedEntityTypes(): Set<String> = currentState.processedEntityTypes
    fun getProcessedDtoTypes(): Set<String> = currentState.processedDtoTypes
    fun getProcessedErrorTypes(): Set<String> = currentState.processedErrorTypes
    fun getProcessedTxTypes(): Set<String> = currentState.processedTxTypes
    fun getProcessedTupleTypes(): Set<String> = currentState.processedTupleTypes
    fun getDtoFileHashes(): Map<String, Long> = currentState.dtoFileHashes
    fun getExplicitClientApi(): Boolean = currentState.explicitClientApi
    
    fun setExplicitClientApi(value: Boolean) {
        currentState = currentState.copy(explicitClientApi = value)
    }
    
    fun setProcessedEntityTypes(types: Set<String>) {
        currentState = currentState.copy(processedEntityTypes = types)
    }
    
    fun setProcessedDtoTypes(types: Set<String>) {
        currentState = currentState.copy(processedDtoTypes = types)
    }
    
    fun setProcessedErrorTypes(types: Set<String>) {
        currentState = currentState.copy(processedErrorTypes = types)
    }
    
    fun setProcessedTxTypes(types: Set<String>) {
        currentState = currentState.copy(processedTxTypes = types)
    }
    
    fun setProcessedTupleTypes(types: Set<String>) {
        currentState = currentState.copy(processedTupleTypes = types)
    }
    
    fun setDtoFileHashes(hashes: Map<String, Long>) {
        currentState = currentState.copy(dtoFileHashes = hashes)
    }
    
    fun setSchemaHash(hash: Long) {
        currentState = currentState.copy(schemaHash = hash)
    }
}
