package org.babyfish.jimmer.ksp.incremental

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.Origin
import org.babyfish.jimmer.ksp.Context
import org.babyfish.jimmer.ksp.util.fastResolve

class DependencyTracker(
    private val ctx: Context
) {
    private val fileTypes = mutableMapOf<KSFile, MutableSet<KSClassDeclaration>>()
    private val typeToFiles = mutableMapOf<String, MutableSet<KSFile>>()
    private val typeDependencies = mutableMapOf<String, MutableSet<String>>()
    private var analyzed = false
    
    fun analyze() {
        if (analyzed) return
        analyzed = true
        
        for (file in ctx.resolver.getAllFiles()) {
            val types = file.declarations.filterIsInstance<KSClassDeclaration>().toMutableSet()
            fileTypes[file] = types
            
            for (type in types) {
                val qualifiedName = type.qualifiedName?.asString() ?: continue
                typeToFiles.getOrPut(qualifiedName) { mutableSetOf() }.add(file)
                typeDependencies[qualifiedName] = collectDependencies(type)
            }
        }
    }
    
    private fun collectDependencies(type: KSClassDeclaration): MutableSet<String> {
        val deps = mutableSetOf<String>()
        
        for (prop in type.getDeclaredProperties()) {
            collectTypeDependencies(prop.type, deps)
        }
        
        for (superTypeRef in type.superTypes) {
            val resolved = superTypeRef.fastResolve()
            resolved.declaration.qualifiedName?.asString()?.let { deps.add(it) }
        }
        
        return deps
    }
    
    private fun collectTypeDependencies(typeRef: KSTypeReference?, deps: MutableSet<String>) {
        if (typeRef == null) return
        try {
            val resolved = typeRef.fastResolve()
            resolved.declaration.qualifiedName?.asString()?.let { deps.add(it) }
            
            for (arg in resolved.arguments) {
                arg.type?.let { collectTypeDependencies(it, deps) }
            }
        } catch (e: Exception) {
            // Ignore unresolved types
        }
    }
    
    fun getFilesContaining(typeName: String): Set<KSFile> = typeToFiles[typeName] ?: emptySet()
    
    fun getDependenciesOf(typeName: String): Set<String> = typeDependencies[typeName] ?: emptySet()
    
    fun findAffectedFiles(modifiedTypes: Set<String>): Set<KSFile> {
        val affected = mutableSetOf<KSFile>()
        val queue = ArrayDeque(modifiedTypes)
        val visited = modifiedTypes.toMutableSet()
        
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val dependentTypes = findDependentTypes(current)
            
            for (depType in dependentTypes) {
                if (depType !in visited) {
                    visited.add(depType)
                    queue.add(depType)
                    typeToFiles[depType]?.let { affected.addAll(it) }
                }
            }
        }
        
        return affected
    }
    
    private fun findDependentTypes(typeName: String): Set<String> {
        val dependents = mutableSetOf<String>()
        for ((depType, deps) in typeDependencies) {
            if (typeName in deps) {
                dependents.add(depType)
            }
        }
        return dependents
    }
    
    fun getDependencyGraph(): Map<String, Set<String>> = typeDependencies.toMap()
    
    fun isNewType(typeName: String): Boolean {
        for (file in ctx.resolver.getNewFiles()) {
            for (decl in file.declarations.filterIsInstance<KSClassDeclaration>()) {
                if (decl.qualifiedName?.asString() == typeName) {
                    return true
                }
            }
        }
        return false
    }
    
    fun getNewTypes(): Set<String> {
        val newTypes = mutableSetOf<String>()
        for (file in ctx.resolver.getNewFiles()) {
            for (decl in file.declarations.filterIsInstance<KSClassDeclaration>()) {
                decl.qualifiedName?.asString()?.let { newTypes.add(it) }
            }
        }
        return newTypes
    }
    
    fun getAllTypes(): Set<String> = typeDependencies.keys.toSet()
}
