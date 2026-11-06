package dev.effect.intellij.schema

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType

/** Upstream ships a complete TSConfig schema, including ordinary compiler options and other plugins.
 * A user-schema provider takes precedence over WebStorm's embedded TSConfig provider; registering
 * another embedded provider would instead produce conflicting schema associations.
 */
class EffectJsonSchemaProviderFactory : JsonSchemaProviderFactory {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> {
        project.getService(EffectSchemaService::class.java).watchSchemaAssociations()
        val scope = GlobalSearchScope.projectScope(project)
        val files = (FilenameIndex.getVirtualFilesByName("tsconfig.json", scope) +
            FilenameIndex.getVirtualFilesByName("jsconfig.json", scope) +
            listOfNotNull(project.guessProjectDir()?.findChild("tsconfig.json"), project.guessProjectDir()?.findChild("jsconfig.json")))
            .distinct()
        return files.map { config ->
            object : JsonSchemaFileProvider {
                override fun isAvailable(file: VirtualFile): Boolean = file == config
                override fun getName(): String = "Effect tsgo (${config.presentableUrl})"
                override fun getSchemaFile(): VirtualFile? = EffectSchemaFiles.nearest(config.parent)
                    ?: JsonSchemaProviderFactory.getResourceFile(EffectJsonSchemaProviderFactory::class.java, EffectOptionSchema.RESOURCE)
                override fun getSchemaType(): SchemaType = SchemaType.userSchema
            }
        }
    }
}
