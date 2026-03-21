package dev.effect.intellij.core

import com.intellij.openapi.application.PathManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

object EffectFileUtil {
    fun ensureDirectory(path: Path): Path {
        Files.createDirectories(path)
        return path
    }

    fun systemCacheDir(relative: String): Path =
        ensureDirectory(Path.of(PathManager.getSystemPath(), relative))

    fun atomicMove(source: Path, target: Path) {
        Files.createDirectories(target.parent)
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
