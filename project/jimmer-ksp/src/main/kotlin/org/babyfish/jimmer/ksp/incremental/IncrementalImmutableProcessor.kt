package org.babyfish.jimmer.ksp.incremental

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.isPrivate
import com.google.devtools.ksp.isProtected
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import org.babyfish.jimmer.Immutable
import org.babyfish.jimmer.ksp.*
import org.babyfish.jimmer.ksp.immutable.generator.DraftGenerator
import org.babyfish.jimmer.ksp.immutable.generator.FetcherGenerator
import org.babyfish.jimmer.ksp.immutable.generator.JimmerModuleGenerator
import org.babyfish.jimmer.ksp.immutable.generator.PropsGenerator
import org.babyfish.jimmer.sql.Embeddable
import org.babyfish.jimmer.sql.Entity
import org.babyfish.jimmer.sql.MappedSuperclass
import kotlin.math.min

class IncrementalImmutableProcessor(
    private val ctx: Context,
    private val state: ProcessorState,
    private val dependencyTracker: DependencyTracker,
    private val typeResolver: TypeResolver,
    private val isModuleRequired: Boolean,
    private val excludeUserAnnotationPrefixes: List<String>
) {
    private val previousProcessedTypes: Set<String>
        get() = state.getProcessedEntityTypes()
    
    fun process(): Set<KSClassDeclaration> {
        val newDeclarations = findNewDeclarations()
        if (newDeclarations.isEmpty()) {
            val affected = findAffectedDeclarations()
            return if (affected.isEmpty()) emptySet() else processAffectedDeclarations(affected)
        }
        
        val affectedDeclarations = findAffectedDeclarations(newDeclarations)
        return processDeclarations(affectedDeclarations)
    }
    
    private fun findNewDeclarations(): List<KSClassDeclaration> {
        val newFiles = ctx.resolver.getNewFiles().toList()
        if (newFiles.isEmpty()) return emptyList()
        
        val newDecls = mutableListOf<KSClassDeclaration>()
        for (file in newFiles) {
            for (classDeclaration in file.declarations.filterIsInstance<KSClassDeclaration>()) {
                if (ctx.include(classDeclaration)) {
                    val annotation = ctx.typeAnnotationOf(classDeclaration)
                    if (classDeclaration.qualifiedName !== null && annotation != null) {
                        if (classDeclaration.classKind != ClassKind.INTERFACE) {
                            throw GeneratorException(
                                "The immutable interface '${classDeclaration.fullName}' must be interface"
                            )
                        }
                        if (classDeclaration.typeParameters.isNotEmpty()) {
                            throw GeneratorException(
                                "The immutable interface '${classDeclaration.fullName}' cannot have type parameters"
                            )
                        }
                        if (classDeclaration.isPrivate() || classDeclaration.isProtected()) {
                            throw GeneratorException(
                                "The immutable interface '${classDeclaration.fullName}' cannot be private or protected"
                            )
                        }
                        newDecls.add(classDeclaration)
                    }
                }
            }
        }
        return newDecls
    }
    
    private fun findAffectedDeclarations(): Set<KSClassDeclaration> {
        val newTypes = dependencyTracker.getNewTypes()
        if (newTypes.isEmpty()) return emptySet()
        
        val affectedNames = dependencyTracker.findAffectedFiles(newTypes.mapNotNull { ctx.resolver.getClassDeclarationByName(it)?.qualifiedName?.asString() }.toSet())
            .mapNotNull { it.qualifiedName?.asString() }
            .toSet()
        
        val affected = mutableSetOf<KSClassDeclaration>()
        for (file in ctx.resolver.getAllFiles()) {
            for (decl in file.declarations.filterIsInstance<KSClassDeclaration>()) {
                val name = decl.qualifiedName?.asString()
                if (name != null && name in affectedNames) {
                    val annotation = ctx.typeAnnotationOf(decl)
                    if (annotation != null) {
                        affected.add(decl)
                    }
                }
            }
        }
        return affected
    }
    
    private fun findAffectedDeclarations(newDeclarations: Collection<KSClassDeclaration>): Set<KSClassDeclaration> {
        val affected = mutableSetOf<KSClassDeclaration>()
        
        for (decl in newDeclarations) {
            affected.add(decl)
        }
        
        val newTypeNames = newDeclarations.mapNotNull { it.qualifiedName?.asString() }.toSet()
        val dependentFiles = dependencyTracker.findAffectedFiles(newTypeNames)
        
        for (file in dependentFiles) {
            for (decl in file.declarations.filterIsInstance<KSClassDeclaration>()) {
                if (decl !in affected) {
                    val annotation = ctx.typeAnnotationOf(decl)
                    if (annotation != null) {
                        affected.add(decl)
                    }
                }
            }
        }
        
        return affected
    }
    
    private fun processDeclarations(declarations: Collection<KSClassDeclaration>): Set<KSClassDeclaration> {
        val modelMap = declarations.groupBy { it.containingFile ?: return@groupBy null }
            .filterKeys { it != null }
            .mapValues { it.value }
        
        for (declaration in declarations) {
            ctx.typeOf(declaration)
        }
        ctx.resolve()
        
        generateJimmerTypes(modelMap)
        
        val allProcessed = state.getProcessedEntityTypes().toMutableSet()
        declarations.forEach { 
            it.qualifiedName?.asString()?.let { name -> allProcessed.add(name) }
        }
        state.setProcessedEntityTypes(allProcessed)
        
        return declarations.toSet()
    }
    
    private fun processAffectedDeclarations(declarations: Collection<KSClassDeclaration>): Set<KSClassDeclaration> {
        val modelMap = declarations.groupBy { it.containingFile ?: return@groupBy null }
            .filterKeys { it != null }
            .mapValues { it.value }
        
        for (declaration in declarations) {
            ctx.typeOf(declaration)
        }
        ctx.resolve()
        
        generateJimmerTypes(modelMap)
        return declarations.toSet()
    }
    
    private fun generateJimmerTypes(
        classDeclarationMultiMap: Map<KSFile?, List<KSClassDeclaration>>
    ) {
        val allFiles = ctx.resolver.getAllFiles().toList()
        val affectedFiles = classDeclarationMultiMap.keys.filterNotNull().toSet()
        
        for ((file, classDeclarations) in classDeclarationMultiMap) {
            if (file == null) continue
            
            DraftGenerator(
                ctx.environment.codeGenerator, 
                ctx, 
                file, 
                classDeclarations, 
                excludeUserAnnotationPrefixes
            ).generate(allFiles, affectedFiles)
            
            if (classDeclarations.size > 1) {
                throw GeneratorException(
                    "The $file declares several types decorated by " +
                        "@${Immutable::class.qualifiedName}, " +
                        "@${Entity::class.qualifiedName}, " +
                        "@${MappedSuperclass::class.qualifiedName} " +
                        "or ${Embeddable::class.qualifiedName}: " +
                        classDeclarations.joinToString { it.fullName }
                )
            }
            
            val sqlClassDeclarations = classDeclarations.filter {
                it.annotation(Entity::class) !== null ||
                    it.annotation(MappedSuperclass::class) !== null ||
                    it.annotation(Embeddable::class) != null
            }
            
            if (sqlClassDeclarations.isNotEmpty()) {
                val sqlClassDeclaration = sqlClassDeclarations[0]
                PropsGenerator(
                    ctx.environment.codeGenerator, 
                    ctx, 
                    file, 
                    sqlClassDeclaration
                ).generate(allFiles, affectedFiles)
                
                if (sqlClassDeclaration.annotation(Entity::class) !== null || 
                    sqlClassDeclaration.annotation(Embeddable::class) !== null) {
                    FetcherGenerator(
                        ctx.environment.codeGenerator, 
                        ctx, 
                        file, 
                        sqlClassDeclaration
                    ).generate(allFiles, affectedFiles)
                }
            }
        }
        
        val packageCollector = PackageCollector()
        for ((file, classDeclarations) in classDeclarationMultiMap) {
            if (file == null) continue
            for (classDeclaration in classDeclarations) {
                if (ctx.include(classDeclaration) && classDeclaration.annotation(Entity::class) !== null) {
                    packageCollector.accept(classDeclaration)
                }
            }
        }
        
        if (!ctx.isBuddyIgnoreResourceGeneration) {
            JimmerModuleGenerator(
                ctx.environment.codeGenerator,
                packageCollector.toString(),
                packageCollector.declarations,
                isModuleRequired
            ).generate(allFiles, affectedFiles)
        }
    }
    
    private class PackageCollector {
        private var paths: MutableList<String>? = null
        private var str: String? = null
        private val _declarations: MutableList<KSClassDeclaration> = ArrayList()
        
        fun accept(declaration: KSClassDeclaration) {
            _declarations.add(declaration)
            if (paths != null && paths!!.isEmpty()) {
                return
            }
            str = null
            var newPaths = DOT_PATTERN.split(declaration.packageName.asString()).toMutableList()
            if (paths == null) {
                paths = newPaths
            } else {
                val len = min(paths!!.size, newPaths.size)
                var index = 0
                while (index < len) {
                    if (paths!![index] != newPaths[index]) {
                        break
                    }
                    index++
                }
                if (index < paths!!.size) {
                    paths!!.subList(index, paths!!.size).clear()
                }
            }
        }
        
        val declarations: List<KSClassDeclaration>
            get() = _declarations
        
        override fun toString(): String {
            var s = str
            if (s == null) {
                val ps = paths
                s = if (ps.isNullOrEmpty()) "" else java.lang.String.join(".", ps)
                str = s
            }
            return s!!
        }
        
        companion object {
            private val DOT_PATTERN = Regex("\\.")
        }
    }
}
