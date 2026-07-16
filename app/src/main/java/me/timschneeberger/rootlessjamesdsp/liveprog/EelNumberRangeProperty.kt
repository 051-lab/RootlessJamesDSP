package me.timschneeberger.rootlessjamesdsp.liveprog

import me.timschneeberger.rootlessjamesdsp.utils.extensions.equalsDelta
import timber.log.Timber
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

open class EelNumberRangeProperty<T:Number>(
    key: String,
    description: String,
    var default: T?,
    var value: T,
    val minimum: T,
    val maximum: T,
    val step: T
) : EelBaseProperty(key, description) {
    init {
        if(minimum.toDouble() >= maximum.toDouble()) {
            throw NumberFormatException("Minimum must be smaller than the maximum (key=$key)")
        }

        value = validateRange(value)
    }

    @Suppress("UNCHECKED_CAST")
    fun validateRange(input: T): T {
        return when (input) {
            is Double -> min(max(minimum.toDouble(), input.toDouble()), maximum.toDouble()) as T
            is Int -> min(max(minimum.toInt(), input.toInt()), maximum.toInt()) as T
            is Float -> min(max(minimum.toFloat(), input.toFloat()), maximum.toFloat()) as T
            else -> throw IllegalArgumentException("unsupported type")
        }
    }

    fun handleAsInt(): Boolean {
        return step.toDouble().equalsDelta(floor(step.toDouble()))
    }

    override fun hasDefault(): Boolean {
        return default != null
    }

    override fun isDefault(): Boolean {
        if (!hasDefault())
            return false

        return if (value is Double || value is Float) {
            if(default == value)
                return true
            default!!.toDouble().equalsDelta(value.toDouble())
        } else {
            default == value
        }
    }

    override fun restoreDefaults() {
        default?.let { value = it }
    }

    override fun valueAsString() = if(handleAsInt()) {
        value.toInt().toString()
    } else {
        "%.2f".format(Locale.ROOT, value)
    }

    override fun manipulateProperty(contents: String): String? {
        return replaceVariable(key, valueAsString(), contents)
    }

    override fun toString(): String {
        return "key=$key; desc=$description; value=$value; handleAsInt=${handleAsInt()}; default=$default; min=$minimum; max=$maximum; step=$step"
    }

    companion object : IPropertyCompanion {
        private fun matchVariable(key: String, contents: String): MatchResult? {
            val regex = """(?<!\w)${Regex.escape(key)}\s*=\s*($EEL_NUMBER_PATTERN)\s*;""".toRegex()
            return regex.find(contents)
        }

        fun findVariable(key: String, contents: String): Float? {
            val match = matchVariable(key, contents)
            return match?.groups?.get(1)?.value?.toFloatOrNull()
        }

        fun replaceVariable(key: String, replacement: String, contents: String): String? {
            val match = matchVariable(key, contents)
            return replaceOrInsertAssignment(key, replacement, contents, match)
        }

        override val definitionRegex = Regex(
            """^\s*(?://\s*)?(?<var>\w+)\s*:\s*(?<def>$EEL_NUMBER_PATTERN)?\s*<\s*(?<min>$EEL_NUMBER_PATTERN)\s*,\s*(?<max>$EEL_NUMBER_PATTERN)(?:\s*,\s*(?<step>$EEL_NUMBER_PATTERN))?\s*>\s*(?<desc>.*)\s*$"""
        )

        override fun parse(line: String, contents: String): EelBaseProperty? {
            val matchRange = definitionRegex.find(line)
            val groupsRange = matchRange?.groups
            groupsRange ?: return null

            val key = groupsRange["var"]?.value
            val def = groupsRange["def"]?.value
            val min = groupsRange["min"]?.value
            val max = groupsRange["max"]?.value
            val step = groupsRange["step"]?.value ?: "0.1"
            val desc = groupsRange["desc"]?.value?.trim()

            if (key == null || desc == null || min == null || max == null) {
                return null
            }

            val defaultValue = def?.toFloatOrNull()
            val current = findVariable(key, contents) ?: defaultValue
            val minimum = min.toFloatOrNull()
            val maximum = max.toFloatOrNull()
            val stepValue = step.toFloatOrNull()
            if ((def != null && defaultValue == null) || current == null || minimum == null ||
                maximum == null || stepValue == null ||
                !current.isFinite() || !minimum.isFinite() || !maximum.isFinite() ||
                !stepValue.isFinite() || stepValue <= 0f || defaultValue?.isFinite() == false
            ) {
                Timber.e("Invalid number range parameter (key=$key)")
                return null
            }

            try {
                return EelNumberRangeProperty(
                    key,
                    desc,
                    defaultValue,
                    current,
                    minimum,
                    maximum,
                    stepValue
                ).also { Timber.d("Found number range property: $it") }
            } catch (ex: IllegalArgumentException) {
                Timber.e("Failed to parse number range parameter (key=$key)")
                Timber.e(ex)
            }
            return null
        }
    }
}
