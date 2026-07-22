package me.timschneeberger.rootlessjamesdsp.interop

internal fun parseFiniteDoubles(value: String, expectedCount: Int): DoubleArray? {
    val tokens = value.split(';')
    if (tokens.size != expectedCount) return null

    val result = DoubleArray(expectedCount)
    for (index in tokens.indices) {
        val parsed = tokens[index].toDoubleOrNull()
        if (parsed == null || !parsed.isFinite()) return null
        result[index] = parsed
    }
    return result
}

internal fun parseClampedInt(value: String, default: Int, range: IntRange): Int =
    value.toIntOrNull()?.coerceIn(range) ?: default

internal fun sanitizeFiniteFloat(
    value: Float,
    default: Float,
    range: ClosedFloatingPointRange<Float>,
): Float = value.takeIf(Float::isFinite)?.coerceIn(range) ?: default
