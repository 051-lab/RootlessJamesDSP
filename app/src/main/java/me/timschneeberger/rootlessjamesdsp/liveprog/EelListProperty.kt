package me.timschneeberger.rootlessjamesdsp.liveprog

import timber.log.Timber


class EelListProperty(
    key: String,
    description: String,
    default: Int?,
    value: Int,
    minimum: Int,
    maximum: Int,
    step: Int,
    val options: List<String>
) : EelNumberRangeProperty<Int>(key, description, default, value, minimum, maximum, step) {

    init {
        if (minimum != 0) {
            throw NumberFormatException("Minimum must be zero for list-type parameters")
        }
    }

    override fun toString(): String {
        return "${super.toString()}; options=${options.joinToString(",")}"
    }

    override fun valueAsString() = value.toString()

    override fun manipulateProperty(contents: String): String? {
        return replaceVariable(key, valueAsString(), contents)
    }


    companion object : IPropertyCompanion {
        private fun matchVariable(key: String, contents: String): MatchResult? {
            val regex = """(?<!\w)${Regex.escape(key)}\s*=\s*([+-]?\d+)\s*;""".toRegex()
            return regex.find(contents)
        }

        fun findVariable(key: String, contents: String): Int? {
            val match = matchVariable(key, contents)
            return match?.groups?.get(1)?.value?.toIntOrNull()
        }

        fun replaceVariable(key: String, replacement: String, contents: String): String? {
            val match = matchVariable(key, contents)
            return replaceOrInsertAssignment(key, replacement, contents, match)
        }

        override val definitionRegex = Regex(
            """^\s*(?://\s*)?(?<var>\w+)\s*:\s*(?<def>[+-]?\d+)?\s*<\s*(?<min>[+-]?\d+)\s*,\s*(?<max>[+-]?\d+)(?:\s*,\s*(?<step>[+-]?\d+))?\s*\{(?<opt>[^}]*)\}\s*>\s*(?<desc>.*)\s*$"""
        )

        override fun parse(line: String, contents: String): EelBaseProperty? {
            val matchList = definitionRegex.find(line)
            val groupsList = matchList?.groups ?: return null

            val key = groupsList["var"]?.value
            val def = groupsList["def"]?.value
            val min = groupsList["min"]?.value
            val max = groupsList["max"]?.value
            val step = groupsList["step"]?.value ?: "1"
            val opt = groupsList["opt"]?.value
            val desc = groupsList["desc"]?.value?.trim()

            if (key == null || desc == null || min == null || max == null || opt == null) {
                return null
            }

            val defaultValue = def?.toIntOrNull()
            val current = findVariable(key, contents) ?: defaultValue
            val minimum = min.toIntOrNull()
            val maximum = max.toIntOrNull()
            val stepValue = step.toIntOrNull()
            val options = opt.split(',').map(String::trim)
            if ((def != null && defaultValue == null) || current == null || minimum == null ||
                maximum == null || stepValue == null || stepValue <= 0 || options.any { it.isEmpty() }
            ) {
                Timber.e("Invalid list option parameter (key=$key)")
                return null
            }

            try {
                return EelListProperty(
                    key,
                    desc,
                    defaultValue,
                    current,
                    minimum,
                    maximum,
                    stepValue,
                    options
                ).also { Timber.d("Found list option property: $it") }
            } catch (ex: IllegalArgumentException) {
                Timber.e("Failed to parse list option parameter (key=$key)")
                Timber.e(ex)
            }
            return null
        }
    }
}
