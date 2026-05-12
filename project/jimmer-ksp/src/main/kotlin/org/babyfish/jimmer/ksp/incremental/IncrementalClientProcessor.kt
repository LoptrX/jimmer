package org.babyfish.jimmer.ksp.incremental

import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Variance
import com.squareup.kotlinpoet.ksp.toTypeName
import org.babyfish.jimmer.client.Api
import org.babyfish.jimmer.client.ApiIgnore
import org.babyfish.jimmer.client.FetchBy
import org.babyfish.jimmer.client.meta.Annotation
import org.babyfish.jimmer.client.meta.ApiOperation
import org.babyfish.jimmer.client.meta.TypeDefinition
import org.babyfish.jimmer.client.meta.TypeName
import org.babyfish.jimmer.client.meta.impl.ApiOperationImpl
import org.babyfish.jimmer.client.meta.impl.ApiParameterImpl
import org.babyfish.jimmer.client.meta.impl.ApiServiceImpl
import org.babyfish.jimmer.client.meta.impl.SchemaBuilder
import org.babyfish.jimmer.client.meta.impl.SchemaImpl
import org.babyfish.jimmer.client.meta.impl.TypeDefinitionImpl
import org.babyfish.jimmer.client.meta.impl.TypeRefImpl
import org.babyfish.jimmer.impl.util.StringUtil
import org.babyfish.jimmer.ksp.Context
import org.babyfish.jimmer.ksp.MetaException
import org.babyfish.jimmer.ksp.annotation
import org.babyfish.jimmer.ksp.client.ClientExceptionMetadata
import org.babyfish.jimmer.ksp.client.DocMetadata
import org.babyfish.jimmer.ksp.fullName
import org.babyfish.jimmer.ksp.util.fastResolve
import org.babyfish.jimmer.sql.Embeddable
import org.babyfish.jimmer.sql.Entity
import org.babyfish.jimmer.sql.MappedSuperclass
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

class IncrementalClientProcessor(
    private val ctx: Context,
    private val state: ProcessorState
) {
    private val clientExceptionContext = ClientExceptionContext()
    private val docMetadata = DocMetadata(ctx)
    private val jsonValueTypeNameStack = mutableSetOf<TypeName>()
    
    private var previousSchemaHash: Long = 0
    private var currentSchemaHash: Long = 0
    
    fun shouldProcess(): Boolean {
        previousSchemaHash = state.getSchemaHash()
        
        val newDeclarations = findNewOrModifiedDeclarations()
        if (newDeclarations.isEmpty()) {
            return false
        }
        
        return true
    }
    
    fun process(explicitClientApi: Boolean) {
        val allDeclarations = findAllApiDeclarations(explicitClientApi)
        currentSchemaHash = computeSchemaHash(allDeclarations)
        
        if (currentSchemaHash == previousSchemaHash && previousSchemaHash != 0L) {
            ctx.environment.logger.info("Client schema unchanged, skipping generation")
            return
        }
        
        generateClientSchema(allDeclarations)
        state.setSchemaHash(currentSchemaHash)
    }
    
    private fun findNewOrModifiedDeclarations(): List<KSClassDeclaration> {
        val newFiles = ctx.resolver.getNewFiles().toList()
        if (newFiles.isEmpty()) return emptyList()
        
        return newFiles.flatMap { file ->
            file.declarations.filterIsInstance<KSClassDeclaration>()
        }
    }
    
    private fun findAllApiDeclarations(explicitClientApi: Boolean): List<KSClassDeclaration> {
        return ctx.resolver.getAllFiles().flatMap { file ->
            file.declarations.filterIsInstance<KSClassDeclaration>()
                .filter { isApiService(it, explicitClientApi) }
        }
    }
    
    private fun computeSchemaHash(declarations: List<KSClassDeclaration>): Long {
        val sb = StringBuilder()
        for (decl in declarations.sortedBy { it.qualifiedName?.asString() }) {
            sb.append(decl.qualifiedName)
            for (func in decl.getDeclaredFunctions().sortedBy { it.simpleName.asString() }) {
                sb.append(func.simpleName).append(func.returnType?.toString())
            }
            for (prop in decl.getDeclaredProperties().sortedBy { it.name }) {
                sb.append(prop.name).append(prop.type.toString())
            }
        }
        return sb.toString().hashCode().toLong()
    }
    
    private fun generateClientSchema(declarations: List<KSClassDeclaration>) {
        val builder = object : SchemaBuilder<KSDeclaration>(null) {
            override fun loadSource(typeName: String): KSClassDeclaration? =
                ctx.resolver.getClassDeclarationByName(typeName)
            
            override fun throwException(source: KSDeclaration, message: String) {
                throw MetaException(source as? KSClassDeclaration, null, message)
            }
            
            override fun fillDefinition(source: KSDeclaration?) {
                if (source is KSClassDeclaration) {
                    fillDefinitionInternal(source)
                }
            }
        }
        
        for (declaration in declarations) {
            builder.handleService(declaration)
        }
        
        val schema = builder.build()
        
        val sourceFiles = declarations.mapNotNull { it.containingFile }.distinct().toTypedArray()
        ctx.environment.codeGenerator.createNewFile(
            Dependencies(false, *sourceFiles),
            "META-INF.jimmer",
            "client",
            ""
        ).use { output ->
            Schemas.writeTo(schema, OutputStreamWriter(output, StandardCharsets.UTF_8))
        }
    }
    
    private fun SchemaBuilder<KSDeclaration>.handleService(declaration: KSClassDeclaration) {
        if (!isApiService(declaration, true)) return
        
        if (declaration.modifiers.contains(Modifier.INNER)) {
            throw MetaException(declaration, null, "Client API service type cannot be inner type")
        }
        
        if (declaration.typeParameters.isNotEmpty()) {
            throw MetaException(declaration.typeParameters[0], null, "Client API service cannot declare type parameters")
        }
        
        val schema = current<SchemaImpl<KSDeclaration>>()
        api(declaration, declaration.toTypeName()) { service ->
            declaration.annotation(Api::class)?.get<List<String>>("value")?.takeIf { it.isNotEmpty() }?.let { groups ->
                service.groups = groups
            }
            service.doc = docMetadata.getDoc(declaration)
            
            for (func in declaration.getDeclaredFunctions()) {
                if (isApiOperation(func)) {
                    handleOperation(func)
                }
            }
            schema.addApiService(service)
        }
    }
    
    private fun SchemaBuilder<KSDeclaration>.handleOperation(func: com.google.devtools.ksp.symbol.KSFunctionDeclaration) {
        val service = current<ApiServiceImpl<KSDeclaration>>()
        
        if (func.typeParameters.isNotEmpty()) {
            throw MetaException(func.typeParameters[0], null, "Client API function cannot declare type parameters")
        }
        
        val api = func.annotation(Api::class)
        if (api == null && ApiOperation.AUTO_OPERATION_ANNOTATIONS.all { func.annotation(it) == null }) {
            return
        }
        
        operation(func, func.simpleName.asString()) { operation ->
            api?.get<List<String>>("value")?.takeIf { it.isNotEmpty() }?.let { groups ->
                service.groups?.let { parentGroups ->
                    val illegalGroups = parentGroups.toMutableSet().apply { removeAll(groups) }
                    if (illegalGroups.isNotEmpty()) {
                        throw MetaException(operation.source, "Illegal groups: $illegalGroups")
                    }
                }
                operation.groups = groups
            }
            
            operation.doc = docMetadata.getDoc(func)
            var index = 0
            for (param in func.parameters) {
                parameter(null, param.name!!.asString()) { parameter ->
                    parameter.originalIndex = index++
                    if (param.annotation(ApiIgnore::class) !== null) {
                        operation.addIgnoredParameter(parameter)
                    } else {
                        typeRef { type ->
                            fillType(param.type)
                            parameter.setType(type)
                        }
                        operation.addParameter(parameter)
                    }
                }
            }
            
            func.returnType?.let { unresolvedType ->
                val qualifiedName = unresolvedType.realDeclaration.qualifiedName?.asString()
                if (qualifiedName != "kotlin.Unit" && qualifiedName != "kotlin.Nothing") {
                    typeRef { type ->
                        fillType(unresolvedType)
                        operation.setReturnType(type)
                    }
                }
            }
            service.addOperation(operation)
        }
    }
    
    private fun SchemaBuilder<KSDeclaration>.fillType(type: KSTypeReference) {
        val typeRef = current<TypeRefImpl<KSDeclaration>>()
        try {
            val resolvedType = type.fastResolve()
            determineNullity(resolvedType)
            determineFetchBy(type)
            determineTypeNameAndArguments(resolvedType)
            typeRef.removeOptional()
        } catch (ex: JsonValueTypeChangeException) {
            typeRef.replaceBy(ex.typeRef, typeRef.isNullable || ex.typeRef.isNullable)
        }
    }
    
    private fun SchemaBuilder<KSDeclaration>.determineNullity(type: KSType) {
        val typeRef = current<TypeRefImpl<KSDeclaration>>()
        typeRef.isNullable = type.isMarkedNullable
    }
    
    private fun SchemaBuilder<KSDeclaration>.determineFetchBy(typeReference: KSTypeReference) {
        // Simplified version - full implementation would be more complex
    }
    
    private fun SchemaBuilder<KSDeclaration>.determineTypeNameAndArguments(type: KSType) {
        val typeRef = current<TypeRefImpl<KSDeclaration>>()
        (type.declaration as? com.google.devtools.ksp.symbol.KSTypeParameter)?.let {
            typeRef.typeName = it.parentDeclaration!!.toTypeName().typeVariable(it.simpleName.asString())
            return
        }
        
        typeRef.typeName = type.realDeclaration.toTypeName()
        
        if (typeRef.typeName == TypeName.OBJECT) {
            return
        }
        
        for (arg in type.arguments) {
            when (arg.variance) {
                Variance.STAR, Variance.CONTRAVARIANT -> { }
                else -> typeRef { innerType ->
                    fillType(arg.type!!)
                    typeRef.addArgument(innerType)
                }
            }
        }
    }
    
    private fun SchemaBuilder<KSDeclaration>.fillDefinitionInternal(declaration: KSClassDeclaration) {
        val definition = current<TypeDefinitionImpl<KSDeclaration>>()
        definition.isApiIgnore = declaration.annotation(ApiIgnore::class) !== null
        definition.doc = docMetadata.getDoc(declaration)
        
        if (declaration.classKind == ClassKind.ENUM_CLASS) {
            fillEnumDefinition(declaration)
            return
        }
        
        val immutable = declaration.annotation(org.babyfish.jimmer.Immutable::class) !== null ||
                declaration.annotation(Entity::class) !== null ||
                declaration.annotation(MappedSuperclass::class) !== null ||
                declaration.annotation(Embeddable::class) !== null
        
        definition.kind = if (immutable) TypeDefinition.Kind.IMMUTABLE else TypeDefinition.Kind.OBJECT
        
        if (!immutable || declaration.classKind == ClassKind.INTERFACE) {
            for (propDeclaration in declaration.getDeclaredProperties()) {
                if (!propDeclaration.isPublic() ||
                    propDeclaration.annotation(ApiIgnore::class) != null) {
                    continue
                }
                
                prop(propDeclaration, propDeclaration.name) { prop ->
                    typeRef { type ->
                        fillType(propDeclaration.type)
                        prop.setType(type)
                    }
                    prop.doc = docMetadata.getDoc(propDeclaration)
                    definition.addProp(prop)
                }
            }
            
            for (funcDeclaration in declaration.getDeclaredFunctions()) {
                if (!funcDeclaration.isConstructor() &&
                    funcDeclaration.isPublic() &&
                    funcDeclaration.parameters.isEmpty()) {
                    val returnTypeReference = funcDeclaration.returnType ?: continue
                    val returnTypeName = returnTypeReference.realDeclaration.qualifiedName?.asString() ?: continue
                    if (returnTypeName == "kotlin.Unit" || returnTypeName == "kotlin.Nothing") continue
                    
                    val name = StringUtil.propName(funcDeclaration.simpleName.asString(), returnTypeName == "kotlin.Boolean") ?: continue
                    prop(funcDeclaration, name) { prop ->
                        typeRef { type ->
                            fillType(returnTypeReference)
                            prop.setType(type)
                        }
                        definition.addProp(prop)
                    }
                }
            }
        }
    }
    
    private fun SchemaBuilder<KSDeclaration>.fillEnumDefinition(declaration: KSClassDeclaration) {
        val definition = current<TypeDefinitionImpl<KSDeclaration>>()
        definition.kind = TypeDefinition.Kind.ENUM
        
        for (child in declaration.declarations) {
            if (child is KSClassDeclaration && child.classKind == ClassKind.ENUM_ENTRY) {
                constant(child, child.simpleName.asString()) {
                    it.doc = docMetadata.getDoc(child)
                    definition.addEnumConstant(it)
                }
            }
        }
    }
    
    private fun isApiService(declaration: KSClassDeclaration, explicitClientApi: Boolean): Boolean {
        if (!ctx.include(declaration)) return false
        if (declaration.annotation(ApiIgnore::class) !== null) return false
        if (declaration.annotation(Api::class) !== null) return true
        if (!explicitClientApi) return false
        return declaration.annotation("org.springframework.web.bind.annotation.RestController") !== null
    }
    
    private fun isApiOperation(declaration: com.google.devtools.ksp.symbol.KSFunctionDeclaration): Boolean {
        if (!declaration.isPublic()) return false
        if (declaration.annotation(ApiIgnore::class) !== null) return false
        if (declaration.annotation(Api::class) !== null) return true
        return ApiOperation.AUTO_OPERATION_ANNOTATIONS.any { declaration.annotation(it) !== null }
    }
    
    private class JsonValueTypeChangeException(val typeRef: TypeRefImpl<KSDeclaration>) : RuntimeException()
    
    private fun TypeRefImpl<KSDeclaration>.removeOptional() {
        if (typeName == TypeName.OPTIONAL) {
            val target = arguments[0] as TypeRefImpl<KSDeclaration>
            replaceBy(target, null)
        }
    }
    
    private fun KSDeclaration.toTypeName(): TypeName {
        val simpleNames = mutableListOf<String>()
        var d: KSDeclaration? = this
        while (d is KSClassDeclaration) {
            simpleNames += d.simpleName.asString()
            d = d.parentDeclaration
        }
        simpleNames.reverse()
        return TypeName.of(packageName.asString(), simpleNames)
    }
    
    private val KSType.realDeclaration: KSDeclaration
        get() = declaration.let {
            if (it is KSTypeAlias) it.findActualType() else it
        }
    
    private val KSTypeReference.realDeclaration: KSDeclaration
        get() = resolve().declaration.let {
            if (it is KSTypeAlias) it.findActualType() else it
        }
}

private fun SchemaBuilder<*>.current(): Any? = null
private fun SchemaBuilder<*>.api(declaration: KSClassDeclaration, typeName: TypeName, block: (ApiServiceImpl<KSDeclaration>) -> Unit): ApiServiceImpl<KSDeclaration> {
    val service = ApiServiceImpl<KSDeclaration>(null)
    service.typeName = typeName
    block(service)
    return service
}
private fun SchemaBuilder<*>.operation(declaration: com.google.devtools.ksp.symbol.KSFunctionDeclaration, name: String, block: (ApiOperationImpl<KSDeclaration>) -> Unit): ApiOperationImpl<KSDeclaration> {
    val operation = ApiOperationImpl<KSDeclaration>(null)
    operation.name = name
    block(operation)
    return operation
}
private fun SchemaBuilder<*>.parameter(source: KSDeclaration?, name: String, block: (ApiParameterImpl<KSDeclaration>) -> Unit): ApiParameterImpl<KSDeclaration> {
    val param = ApiParameterImpl<KSDeclaration>(source)
    param.name = name
    block(param)
    return param
}
private fun SchemaBuilder<*>.typeRef(block: TypeRefImpl<KSDeclaration>.() -> Unit): TypeRefImpl<KSDeclaration> {
    val ref = TypeRefImpl<KSDeclaration>(null)
    ref.block()
    return ref
}
private fun SchemaBuilder<*>.prop(source: KSDeclaration, name: String, block: (org.babyfish.jimmer.client.meta.impl.TypePropImpl<KSDeclaration>) -> Unit): org.babyfish.jimmer.client.meta.impl.TypePropImpl<KSDeclaration> {
    val prop = org.babyfish.jimmer.client.meta.impl.TypePropImpl<KSDeclaration>(source)
    prop.name = name
    block(prop)
    return prop
}
private fun SchemaBuilder<*>.constant(source: KSDeclaration, name: String, block: (org.babyfish.jimmer.client.meta.impl.EnumConstantImpl<KSDeclaration>) -> Unit): org.babyfish.jimmer.client.meta.impl.EnumConstantImpl<KSDeclaration> {
    val constant = org.babyfish.jimmer.client.meta.impl.EnumConstantImpl<KSDeclaration>(source)
    constant.name = name
    block(constant)
    return constant
}
