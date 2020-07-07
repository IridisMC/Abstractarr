package abstractor

import metautils.api.JavaType
import metautils.api.remap
import metautils.descriptor.*
import metautils.signature.*
import metautils.util.*
import metautils.signature.remap
import metautils.signature.toJvmType

class VersionPackage(private val versionPackage: String) {
    private fun PackageName?.toApiPackageName() = versionPackage.prependToQualified(this ?: PackageName.Empty)
    private fun ShortClassName.toApiShortClassName() =
        ShortClassName(("I" + outerMostClass()).prependTo(innerClasses()))

    fun QualifiedName.toApiClass(): QualifiedName = if (isMcClassName()) {
        QualifiedName(
            packageName = packageName.toApiPackageName(),
            shortName = shortName.toApiShortClassName()
        )
    } else this

    private fun ShortClassName.toBaseShortClassName() =
        ShortClassName(("Base" + outerMostClass()).prependTo(innerClasses()))

    fun QualifiedName.toBaseClass(): QualifiedName =
        QualifiedName(
            packageName = packageName.toApiPackageName(),
            shortName = shortName.toBaseShortClassName()
        )

    fun <T : ReturnDescriptor> T.remapToApiClass(): T = remap { it.toApiClass() }
    fun <T : GenericReturnType> JavaType<T>.remapToApiClass(): JavaType<T> = remap { it.toApiClass() }

    fun <T : GenericReturnType> T.remapToApiClass(): T = remap { it.toApiClass() }
    fun List<TypeArgumentDeclaration>.remapDeclToApiClasses() = map { typeArg ->
        typeArg.copy(
            classBound = typeArg.classBound?.remapToApiClass(),
            interfaceBounds = typeArg.interfaceBounds.map { it.remapToApiClass() })
    }


    fun <T : GenericReturnType> List<JavaType<T>>.remapToApiClasses(): List<JavaType<T>> =
        map { it.remapToApiClass() }
}

fun PackageName?.isMcPackage(): Boolean = if (this == null) false
else startsWith("net", "minecraft") || startsWith("com", "mojang", "blaze3d")

fun QualifiedName.isMcClassName(): Boolean = packageName.isMcPackage()
fun GenericReturnType.isMcClass(): Boolean = this is ArrayGenericType && componentType.isMcClass() ||
        this is ClassGenericType && packageName.isMcPackage()
        || this is TypeVariable && toJvmType().isMcClass()

private fun JvmType.isMcClass(): Boolean = this is ObjectType && fullClassName.isMcClassName()
        || this is ArrayType && componentType.isMcClass()

fun JavaType<*>.isMcClass(): Boolean = type.isMcClass()