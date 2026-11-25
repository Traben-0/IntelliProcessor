package org.polyfrost.intelliprocessor.utils

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.io.await
import kotlinx.coroutines.withTimeoutOrNull
import org.polyfrost.intelliprocessor.utils.PreprocessorVersion.Companion.preprocessorVersion
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.milliseconds

data class SourceSetFile(
    val sourceSetName: String,
    val language: String,
    val classPath: Path,
    val subVersion: String?, // If null, this is the mainVersion in project/src/
    private val mainVersion: String,
    private val rootDirectory: Path,
) {
    // Refers to the path of a possible, non-generated, overriding source file in versions/<subVersion>/src/
    private val overridePath: Path? = subVersion?.let {
        Path.of("versions", it, "src", sourceSetName, language).resolve(classPath)
    }

    val isSrc = subVersion == null

    val isNonGenerated = isSrc || overridePath?.let { rootDirectory.resolve(it).toFile().exists() } == true

    private val relativePath: Path =
        if (isSrc) {
            Path.of("src", sourceSetName, language).resolve(classPath)
        } else if (isNonGenerated) {
            overridePath!!
        } else {
            Path.of("versions", subVersion, "build", "preprocessed", sourceSetName, language).resolve(classPath)
        }

    val absolutePath: Path = rootDirectory.resolve(relativePath)

    val exists = absolutePath.exists()

    var metOpeningCondition = true

    val displayVersion = subVersion ?: mainVersion

    val version = displayVersion.preprocessorVersion ?: PreprocessorVersion.NULL

    private val string = run {
        val displayFile = classPath.last()
        val srcMark = if (isSrc) "(main src)"
        else if (isNonGenerated) "(override src)"
        else ""

        // e.g. [12102fabric] | 1.21.2-fabric | MyClass.java (main)
        "[${version.mc}${version.loader}] | $displayVersion | $displayFile $srcMark"
    }

    override fun toString(): String = string

    @Volatile
    private var foundMixinErrors = false
    fun hasMixinErrors() = foundMixinErrors

    /**
     * Mixin inspections from the MCDev plugin aren't issues that will fail a build.
     * So lets find and highlight them in the file jump list, saves crawling through each version looking for them
     */
    suspend fun findMixinErrors(project: Project, disposable: Disposable) {
        val problems: List<ProblemDescriptor>? = withTimeoutOrNull(5000.milliseconds) {
            val start = System.currentTimeMillis()
            ReadAction.nonBlocking<List<ProblemDescriptor>?> {
                val virtualFile = VfsUtil.findFile(absolutePath, false)
                    ?: return@nonBlocking null

                val psiFile = PsiManager.getInstance(project)
                    .findFile(virtualFile)
                    ?: return@nonBlocking null

                if (!psiFile.isMixinClass()) {
                    return@nonBlocking null
                }

                val mixinInspections = InspectionProjectProfileManager
                    .getInstance(project)
                    .currentProfile
                    .getInspectionTools(psiFile)
                    .filter { it.groupPath.contains("Mixin") }

                val manager = InspectionManager.getInstance(project)

                mixinInspections.flatMap { wrapper ->
                    val tool = wrapper.tool as? LocalInspectionTool
                        ?: return@flatMap emptyList()

                    tool.processFile(psiFile, manager)
                }
            }
                .expireWith(disposable)
                // Probably redundant with the withTimeoutOrNull() above, but better to be safe
                .expireWhen { System.currentTimeMillis() - start > 5000 }
                .submit(AppExecutorUtil.getAppExecutorService())
                .await()
        }

        foundMixinErrors = problems?.any {
            val description = it.descriptionTemplate
            description.contains("resolve", ignoreCase = true) ||
                    description.contains("missing", ignoreCase = true) ||
                    description.contains("invalid", ignoreCase = true) ||
                    description.contains("no possible", ignoreCase = true) ||

                    // Cant just match "does not match" for these 2 as it would also catch the harmless "local / param name does not match"
                    description.contains("@Local does not match", ignoreCase = true) ||
                    description.contains("signature does not match", ignoreCase = true)
        } ?: false
    }
}

private fun PsiFile.isMixinClass(): Boolean {
    if (this !is PsiJavaFile && fileType.name != "JAVA") {
        return false
    }

    return PsiTreeUtil.collectElementsOfType(this, PsiClass::class.java)
        .any {
            it.annotations.any { annotation ->
                annotation.qualifiedName == "org.spongepowered.asm.mixin.Mixin"
                        || annotation.nameReferenceElement?.referenceName == "Mixin"
            }
        }
}
