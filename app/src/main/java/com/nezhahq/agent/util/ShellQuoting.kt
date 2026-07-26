package com.nezhahq.agent.util

/**
 * Quotes [value] as a single `sh` word.
 *
 * Single quotes suppress every form of expansion, and an embedded quote is closed, escaped and
 * reopened — the only sequence a single-quoted string cannot contain literally. Every path or
 * argument that reaches a shell must pass through here, including ones that look safe today:
 * `/sys` entries discovered at runtime and dashboard-supplied paths end up in the same commands,
 * and the difference is not visible at the call site.
 *
 * This lived as three identical private copies before, one of which the GPU collector did not have,
 * which is how its `cat $path` calls came to be unquoted.
 */
internal fun shellEscape(value: String): String = "'" + value.replace("'", "'\\''") + "'"
