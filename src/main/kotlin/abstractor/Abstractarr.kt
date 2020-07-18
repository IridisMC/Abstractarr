package abstractor

import codegeneration.ClassAccess
import codegeneration.ClassVariant
import codegeneration.Public
import codegeneration.Visibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import metautils.api.*
import metautils.codegeneration.asm.toAsmAccess
import metautils.signature.ClassGenericType
import metautils.signature.fromNameAndTypeArgs
import metautils.signature.toClassfileName
import metautils.signature.toTypeArgumentsOfNames
import metautils.util.*
import java.nio.file.Path

interface IAbstractionType {
    val isAbstracted: Boolean
    val addInInterface: Boolean
    val addInBaseclass: Boolean
}

enum class MemberAbstractionType : IAbstractionType {
    None,
    Interface,
    Baseclass,
    BaseclassAndInterface;

    override val isAbstracted get() = this != None
    override val addInInterface get() = this == Interface || this == BaseclassAndInterface
    override val addInBaseclass get() = this == Baseclass || this == BaseclassAndInterface
}

enum class ClassAbstractionType : IAbstractionType {
    None,
    Interface,
    BaseclassAndInterface;

    override val isAbstracted get() = this != None
    override val addInInterface get() = this == Interface || this == BaseclassAndInterface
    override val addInBaseclass get() = this == BaseclassAndInterface
}

data class TargetSelector(
    val classes: (ClassApi) -> ClassAbstractionType,
    val methods: (ClassApi, ClassApi.Method) -> MemberAbstractionType,
    val fields: (ClassApi, ClassApi.Field) -> MemberAbstractionType
) {
    companion object {
        val All = TargetSelector({
            // Non-static inner class baseclasses are not supported yet
            if (it.isInnerClass && !it.isStatic) ClassAbstractionType.Interface
            else ClassAbstractionType.BaseclassAndInterface
        },
            { _, _ -> MemberAbstractionType.BaseclassAndInterface },
            { _, _ -> MemberAbstractionType.BaseclassAndInterface }
        )
    }
}

data class AbstractionMetadata(
    // A string prefix for api packages
    val versionPackage: VersionPackage,
    // Libraries used in the abstracted jar
    val classPath: List<Path>,
    // Whether to produce an api that won't be usable for runtime, but is rather suited for compiling against in dev
    val fitToPublicApi: Boolean,
    val writeRawAsm: Boolean,
    // Classes/methods/fields that will be abstracted
    val selector: TargetSelector,
    val javadocs: JavaDocs
)
/** A list used in testing and production to know what interfaces/classes to attach to minecraft interface */
typealias AbstractionManifest = Map<String, AbstractedClassInfo>

val AbstractionManifestSerializer = MapSerializer(String.serializer(), AbstractedClassInfo.serializer())

@Serializable
data class AbstractedClassInfo(val apiClassName: String, /*val isThrowable: Boolean,*/ val newSignature: String)

class Abstractor /*private*/ constructor(
//    private val classes: Collection<ClassApi>,
    // This includes the minimal classes
    private val abstractedClasses : Set<ClassApi>,
    private val classNamesToClasses: Map<QualifiedName, ClassApi>,
//    private val classRanks: Map<QualifiedName, Int>,
    private val index: ClasspathIndex

) {

    companion object {
        inline fun parse(
            mcJar: Path,
            metadata: AbstractionMetadata,
            crossinline usage: (Abstractor) -> Unit
        ): AbstractionManifest {
            val classes = ClassApi.readFromJar(mcJar) { path ->
                path.toString().let { it.startsWith("/net/minecraft/") || it.startsWith("/com/mojang/blaze3d") }
            }
            val classNamesToClasses = classes.flatMap { outerClass ->
                outerClass.allInnerClassesAndThis().map { it.name to it }
            }.toMap()

            // We need to add the access of api interfaces, base classes, and base api interfaces.
            // For other things in ClassEntry we just pass empty list in assumption they won't be needed.
            val additionalEntries = listAllGeneratedClasses(classes, metadata)


            return ClasspathIndex.index(metadata.classPath + listOf(mcJar), additionalEntries) { index ->
                val referencedClasses = getReferencedClasses(classNamesToClasses.values, metadata.selector)
                // Also add the outer classes, to prevent cases where only an inner class is abstracted and not the outer one.
                val allReferencedClasses = referencedClasses.flatMap { it.thisToOuterClasses() }.toSet()
                val abstractedClasses = classNamesToClasses.values.filter {                // Minimal classes are classes not chosen to be abstracted, but are
                    // This also adds "minimal" classes:
                    // Minimal classes are classes not chosen to be abstracted, but are referenced by classes that are.
                    // Those classes are abstracted, but only contain methods that have abstracted classes in their
                    // signature. This is done to maximize the amount of exposed methods while minimizing the amount
                    // of exposed classes.
                    it.isPublicApi && (metadata.selector.classes(it).isAbstracted || it.name in allReferencedClasses)
                }

                usage(
                    Abstractor(
                        abstractedClasses = abstractedClasses.toSet(),
                        classNamesToClasses = classNamesToClasses,
                        index = index
                    )
                )


                buildAbstractionManifest(abstractedClasses, metadata.versionPackage)
            }
        }
    }

    fun abstract(destDir: Path, metadata: AbstractionMetadata) {
        require(destDir.parent.exists()) { "The chosen destination path '$destDir' is not in any existing directory." }
        require(destDir.parent.isDirectory()) { "The parent of the chosen destination path '$destDir' is not a directory." }

        destDir.deleteRecursively()
        destDir.createDirectory()

        runBlocking {
            coroutineScope {
                for (classApi in abstractedClasses.filter { !it.isInnerClass }) {
//                    if (!classApi.isPublicApiAsOutermostMember) continue
                    launch(Dispatchers.IO) {
                        ClassAbstractor(metadata, index, classApi, classNamesToClasses,abstractedClasses)
                            .abstractClass(destPath = destDir)
                    }
                }
            }
        }

    }
}

@PublishedApi
internal fun getReferencedClasses(
    allClasses: Collection<ClassApi>,
    selected: TargetSelector
): Set<QualifiedName> {
    return allClasses.filter { selected.classes(it).isAbstracted }
        .flatMap { it.getAllReferencedClasses(selected) }.toSet()
}

@PublishedApi
internal fun buildAbstractionManifest(
    classesWithApiInterfaces: Collection<ClassApi>,
    version: VersionPackage
): AbstractionManifest = with(version) {
    classesWithApiInterfaces.map { mcClass ->
        val mcClassName = mcClass.name.toSlashQualifiedString()
        val apiClass = mcClass.name.toApiClass()
        val oldSignature = mcClass.getSignature()
        val insertedApiClass = ClassGenericType.fromNameAndTypeArgs(
            name = apiClass,
            typeArgs = allApiInterfaceTypeArguments(mcClass).toTypeArgumentsOfNames()
        )

        val newSignature = oldSignature.copy(superInterfaces = oldSignature.superInterfaces + insertedApiClass)
        mcClassName to AbstractedClassInfo(
            apiClassName = apiClass.toSlashQualifiedString(),
            newSignature = newSignature.toClassfileName()
        )
    }.toMap()
}

@PublishedApi
internal fun listAllGeneratedClasses(
    classes: Collection<ClassApi>,
    metadata: AbstractionMetadata
): Map<QualifiedName, ClassEntry> = with(metadata.versionPackage) {
    classes.flatMap { outerClass ->
        // This also includes package private and private stuff, because mojang sometimes exposes private classes
        // in public apis... thank you java
        outerClass.allInnerClassesAndThis().flatMap { mcClass ->
            val baseclass = mcClass.name.toBaseClass() to entryJustForAccess(
                baseClassAccess(origIsInterface = mcClass.isInterface),
                isStatic = mcClass.isStatic,
                visibility = mcClass.visibility
            )

            val apiInterface = mcClass.name.toApiClass() to entryJustForAccess(
                apiInterfaceAccess(metadata),
                isStatic = mcClass.isInnerClass,
                visibility = Visibility.Public
            )

            listOf(apiInterface, baseclass)
        }
    }.toMap()
}

private fun entryJustForAccess(access: ClassAccess, visibility: Visibility, isStatic: Boolean): ClassEntry {
    return ClassEntry(
        methods = mapOf(), superInterfaces = listOf(), superClass = null,
        access = access.toAsmAccess(visibility, isStatic),
        name = QualifiedName.Empty
    )
}


internal fun apiInterfaceAccess(metadata: AbstractionMetadata) = ClassAccess(
    isFinal = metadata.fitToPublicApi,
    variant = ClassVariant.Interface
)


internal fun baseClassAccess(origIsInterface: Boolean) = ClassAccess(
    isFinal = false,
    variant = if (origIsInterface) ClassVariant.Interface else ClassVariant.AbstractClass
)

