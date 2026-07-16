package me.timschneeberger.rootlessjamesdsp.liveprog

internal const val EEL_NUMBER_PATTERN = """[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?"""

private val initSectionRegex = Regex(
    """(?m)^[\t ]*@init(?:[\t ]*(?://[^\r\n]*)?)?[\t ]*\r?$"""
)
private val firstCodeSectionRegex = Regex(
    """(?m)^[\t ]*@(init|slider|block|sample)\b[^\r\n]*"""
)

internal fun replaceOrInsertAssignment(
    key: String,
    replacement: String,
    contents: String,
    match: MatchResult?,
): String? {
    if (match != null) {
        val valueRange = match.groups[1]?.range ?: return null
        return contents.replaceRange(valueRange, replacement)
    }

    val newline = if (contents.contains("\r\n")) "\r\n" else "\n"
    val assignment = "$key = $replacement;$newline"
    val initSection = initSectionRegex.find(contents)
    if (initSection != null) {
        val nextLine = contents.indexOf('\n', initSection.range.last + 1)
        return if (nextLine >= 0) {
            contents.replaceRange(nextLine + 1, nextLine + 1, assignment)
        } else {
            "$contents$newline$assignment"
        }
    }

    val sectionStart = firstCodeSectionRegex.find(contents)?.range?.first ?: contents.length
    val separator = if (sectionStart > 0 && contents[sectionStart - 1] != '\n') newline else ""
    val initBlock = "${separator}@init$newline$assignment"
    return contents.replaceRange(sectionStart, sectionStart, initBlock)
}

interface IPropertyCompanion {
    val definitionRegex: Regex
    fun parse(line: String, contents: String): EelBaseProperty?
}
