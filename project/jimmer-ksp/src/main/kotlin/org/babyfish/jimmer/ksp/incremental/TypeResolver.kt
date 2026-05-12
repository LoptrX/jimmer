package org.babyfish.jimmer.ksp.incremental

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSTypeReference
import org.babyfish.jimmer.ksp.Context
import org.babyfish.jimmer.ksp.immutable.meta.ImmutableProp
import org.babyfish.jimmer.ksp.immutable.meta.ImmutableType
import org.babyfish.jimmer.ksp.util.fastResolve

class TypeResolver(private val ctx: Context) {
    
    private val resolvedTypes = mutableMapOf<String, ImmutableType>()
    private val pendingDeclarations = mutableListOf<KSClassDeclaration>()
    private val allFiles = ctx.resolver.getAllFiles().toList()
    private var initialized = false
    
    fun initialize() {
        if (initialized) return
        initialized = true
        
        for (file in allFiles) {
            for (decl in file.declarations.filterIsInstance<KSClassDeclaration>()) {
                if (isJimmerType(decl)) {
                    pendingDeclarations.add(decl)
                }
            }
        }
    }
    
    fun resolveDeclarations(declarations: Collection<KSClassDeclaration>): Collection<ImmutableType> {
        val toResolve = declarations.filter { 
            val name = it.qualifiedName?.asString()
            name != null && !resolvedTypes.containsKey(name)
        }
        
        pendingDeclarations.addAll(toResolve)
        resolvePending()
        
        return declarations.mapNotNull { 
            it.qualifiedName?.asString()?.let { name -> resolvedTypes[name] }
        }
    }
    
    private fun resolvePending() {
        while (pendingDeclarations.isNotEmpty()) {
            val batch = pendingDeclarations.toList()
            pendingDeclarations.clear()
            
            for (decl in batch) {
                val name = decl.qualifiedName?.asString() ?: continue
                if (!resolvedTypes.containsKey(name)) {
                    try {
                        val type = ImmutableType(ctx, decl)
                        resolvedTypes[name] = type
                        collectDependencies(type)
                    } catch (e: Exception) {
                        ctx.environment.logger.warn("Failed to resolve type ${decl.qualifiedName}: ${e.message}")
                    }
                }
            }
        }
    }
    
    private fun collectDependencies(type: ImmutableType) {
        for (superType in type.superTypes) {
            val superDecl = superType.classDeclaration
            val superName = superDecl.qualifiedName?.asString() ?: continue
            if (!resolvedTypes.containsKey(superName) && 
                !pendingDeclarations.contains(superDecl) &&
                isJimmerType(superDecl)) {
                pendingDeclarations.add(superDecl)
            }
        }
        
        for (prop in type.properties.values) {
            collectPropDependencies(prop)
        }
    }
    
    private fun collectPropDependencies(prop: ImmutableProp) {
        try {
            val targetType = prop.targetType
            if (targetType != null) {
                val targetName = targetType.qualifiedName ?: return
                if (!resolvedTypes.containsKey(targetName)) {
                    val targetDecl = ctx.resolver.getClassDeclarationByName(targetName)
                    if (targetDecl != null && !pendingDeclarations.contains(targetDecl) && isJimmerType(targetDecl)) {
                        pendingDeclarations.add(targetDecl)
                    }
                }
            }
            
            for (dep in prop.formulaDependencies) {
                val depDecl = ctx.resolver.getClassDeclarationByName(dep)
                if (depDecl != null && !pendingDeclarations.contains(depDecl) && isJimmerType(depDecl)) {
                    pendingDeclarations.add(depDecl)
                }
            }
        } catch (e: Exception) {
            // Ignore unresolved properties
        }
    }
    
    private fun isJimmerType(decl: KSClassDeclaration): Boolean {
        val annotation = ctx.typeAnnotationOf(decl)
        return annotation != null
    }
    
    fun getResolvedTypes(): Collection<ImmutableType> = resolvedTypes.values
    
    fun getType(name: String): ImmutableType? = resolvedTypes[name]
    
    fun getDependencyGraph(): Map<String, Set<String>> {
        val graph = mutableMapOf<String, MutableSet<String>>()
        
        for ((name, type) in resolvedTypes) {
            val deps = mutableSetOf<String>()
            deps.addAll(type.superTypes.map { it.qualifiedName })
            for (prop in type.properties.values) {
                prop.targetType?.qualifiedName?.let { deps.add(it) }
            }
            graph[name] = deps
        }
        
        return graph
    }
    
    fun findAffectedTypes(modifiedTypes: Set<String>): Set<String> {
        val affected = mutableSetOf<String>()
        val queue = ArrayDeque(modifiedTypes)
        val visited = modifiedTypes.toMutableSet()
        
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            
            for ((depType, deps) in getDependencyGraph()) {
                if (current in deps && depType !in visited) {
                    visited.add(depType)
                    queue.add(depType)
                    affected.add(depType)
                }
            }
        }
        
        return affected
    }
    
    fun getFilesForTypes(typeNames: Set<String>): Set<KSFile> {
        val files = mutableSetOf<KSFile>()
        for (file in allFiles) {
            for (decl in file.declarations.filterIsInstance<KSClassDeclaration>()) {
                if (decl.qualifiedName?.asString() in typeNames) {
                    files.add(file)
                    break
                }
            }
        }
        return files
    }
}
