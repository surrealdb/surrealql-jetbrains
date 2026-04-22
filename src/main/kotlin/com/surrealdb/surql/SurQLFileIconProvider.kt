package com.surrealdb.surql

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

class SurQLFileIconProvider : FileIconProvider {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? {
        if (file.isDirectory) return null
        val ext = file.extension?.lowercase() ?: return null
        return if (ext == "surql" || ext == "surrealql") SurQLIcons.FILE else null
    }
}
