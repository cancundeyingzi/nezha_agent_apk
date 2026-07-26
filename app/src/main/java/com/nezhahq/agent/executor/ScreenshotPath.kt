package com.nezhahq.agent.executor

/**
 * Resolves the path an `@agent screenshot` invocation asked for.
 *
 * A relative path is resolved against [defaultDirectory] and rejected — null — when it climbs out
 * of it. `../../somewhere/x.png` used to escape silently, which contradicted the help text's
 * promise that relative paths land in the default directory.
 *
 * An absolute path is honoured as given, including one containing `..`. Constraining it would buy
 * nothing: reaching this command already requires the remote-shell capability, so the caller can
 * write anywhere the process can regardless.
 *
 * Resolution is lexical, with no filesystem access, which keeps this a pure function. Symlinks are
 * therefore not followed; that is not a gap for the reason above.
 */
internal fun resolveScreenshotPath(
    requestedPath: String,
    defaultDirectory: String,
    generatedFileName: String
): String? {
    val requested = requestedPath.replace('\\', '/')
    val withFileName = when {
        requested.isBlank() -> generatedFileName
        requested.endsWith('/') -> requested + generatedFileName
        else -> requested
    }

    if (withFileName.startsWith('/')) return collapseTraversal(withFileName)

    val base = defaultDirectory.trimEnd('/')
    val resolved = collapseTraversal("$base/$withFileName") ?: return null
    return resolved.takeIf { it.startsWith("$base/") }
}

/**
 * Collapses `.` and `..` segments of an absolute path.
 *
 * Returns null when `..` would climb above the root, or when nothing is left to name a file.
 */
private fun collapseTraversal(absolutePath: String): String? {
    val segments = mutableListOf<String>()
    for (segment in absolutePath.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> {
                if (segments.isEmpty()) return null
                segments.removeAt(segments.lastIndex)
            }
            else -> segments.add(segment)
        }
    }
    if (segments.isEmpty()) return null
    return segments.joinToString(separator = "/", prefix = "/")
}
