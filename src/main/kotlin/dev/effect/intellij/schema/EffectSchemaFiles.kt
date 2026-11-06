package dev.effect.intellij.schema

import com.intellij.openapi.vfs.VirtualFile

/** VFS-only lookup: no refresh, disk reads, network access, or package-manager execution. */
object EffectSchemaFiles {
    fun nearest(directory: VirtualFile?): VirtualFile? {
        var ancestor = directory
        while (ancestor != null) {
            ancestor.findFileByRelativePath("node_modules/@effect/tsgo/schema.json")
                ?.takeUnless { it.isDirectory }?.let { return it }
            ancestor = ancestor.parent
        }
        return null
    }

    fun isConfig(file: VirtualFile): Boolean = file.name == "tsconfig.json" || file.name == "jsconfig.json"
}
