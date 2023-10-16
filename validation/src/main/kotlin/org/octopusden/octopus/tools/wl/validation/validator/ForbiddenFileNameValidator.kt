package org.octopusden.octopus.tools.wl.validation.validator

import java.io.File

class ForbiddenFileNameValidator(allowedFileNames: String) {
    private val allowedFileNames: Regex = allowedFileNames.toRegex()

    fun validate(fileNames: Collection<String>): List<String> {
        val errorFileNames = ZipNode("", fileNames)
            .allChildren()
            .filter { !allowedFileNames.matches(it.name) }
            .map { it.fullPath() }

        return errorFileNames.map { "$it does not match $allowedFileNames" }
    }

    private inner class ZipNode(val name: String, val parent: ZipNode?, childrenFileNames: Collection<String>) {
        private val children: Collection<ZipNode>

        init {
            val relativeFileNames = childrenFileNames.map { fileName ->
                parent?.let { fileName.substring("${name}$separator".length, fileName.length) } ?: fileName
            }.filter { it.isNotEmpty() }

            val currentDirFileNames = relativeFileNames.filter { fileName -> !fileName.contains(separator) }

            val groupedChildrenFileNames = (relativeFileNames - currentDirFileNames)
                .groupBy { fileName -> fileName.substring(0, fileName.indexOfFirst { it.toString() == separator }) }

            children = groupedChildrenFileNames.map { (dir, childrenFileNames) ->
                ZipNode(dir, this, childrenFileNames)
            } + currentDirFileNames.map { ZipNode(it, this, emptySet()) }
        }

        constructor(name: String, childrenFileNames: Collection<String>) : this(name, null, childrenFileNames)

        fun fullPath(): String {
            return if (parent == null) {
                name
            } else {
                "${parent.fullPath()}/$name"
            }
        }

        fun allChildren(): Collection<ZipNode> =
            children.flatMap { it.allChildren() } + this
    }

    companion object {
        private val separator = File.separator
    }
}
