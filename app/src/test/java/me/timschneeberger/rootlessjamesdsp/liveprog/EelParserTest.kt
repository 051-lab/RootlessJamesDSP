package me.timschneeberger.rootlessjamesdsp.liveprog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EelParserTest {
    @Test
    fun parsesLegacyDeclarationsAndNumericFormats() {
        val parser = parse(
            """
            // leading: .5 < -1, 1. > Leading decimal
            exponent: -2.5e-1<-1e0, 1E+0, 5e-2>Scientific notation
            // mode:1<0,2{Off, Normal, Wide}>Mode
            @init
            leading = .5;
            exponent = -2.5e-1;
            mode = 1;
            """.trimIndent()
        )

        assertEquals(listOf("leading", "exponent", "mode"), parser.properties.map { it.key })

        val leading = parser.properties[0] as EelNumberRangeProperty<*>
        assertEquals(0.5f, leading.value.toFloat(), 0f)
        assertEquals(1.0f, leading.maximum.toFloat(), 0f)
        assertEquals(0.1f, leading.step.toFloat(), 0f)

        val exponent = parser.properties[1] as EelNumberRangeProperty<*>
        assertEquals(-0.25f, exponent.value.toFloat(), 0f)
        assertEquals(0.05f, exponent.step.toFloat(), 0f)

        val mode = parser.properties[2] as EelListProperty
        assertEquals(listOf("Off", "Normal", "Wide"), mode.options)
    }

    @Test
    fun keepsFirstDuplicateAndIgnoresDeclarationsInsideCodeSections() {
        val parser = parse(
            """
            duplicate:1<0,2>First
            // duplicate:1<0,2>Second
            @init
            duplicate = 1;
            hidden:1<0,2>Not metadata
            hidden = 1;
            """.trimIndent()
        )

        assertEquals(1, parser.properties.size)
        assertEquals("First", parser.properties.single().description)
    }

    @Test
    fun limitsDiscoveredProperties() {
        val declarations = (0 until 130).joinToString("\n") { "p$it:0<0,1>Parameter $it" }
        val assignments = (0 until 130).joinToString("\n") { "p$it = 0;" }

        val parser = parse("$declarations\n@init\n$assignments")

        assertEquals(128, parser.properties.size)
        assertTrue(parser.properties.none { it.key == "p128" })
    }

    @Test
    fun rejectsNonFiniteMetadata() {
        val parser = parse(
            """
            invalid:1e999<0,1>Invalid
            @init
            invalid = 0;
            """.trimIndent()
        )

        assertTrue(parser.properties.isEmpty())
    }

    @Test
    fun usesDeclaredDefaultsWhenAssignmentsAreAbsent() {
        val parser = parse(
            """
            slider1:.5<0,1,.1>Gain
            mode:2<0,2,1{Off, Normal, Wide}>Mode
            @slider
            gain = slider1;
            @sample
            spl0 *= gain;
            spl1 *= gain;
            """.trimIndent()
        )

        assertEquals(0.5f, (parser.properties[0] as EelNumberRangeProperty<*>).value.toFloat(), 0f)
        assertEquals(2, (parser.properties[1] as EelListProperty).value)
    }

    @Test
    fun insertsMissingAssignmentAtStartOfInit() {
        val source = """
            slider1:.5<0,1,.1>Gain
            @init
            state = 1;
            @sample
            spl0 *= slider1;
        """.trimIndent()
        val property = parse(source).properties.single() as EelNumberRangeProperty<Float>
        property.value = 0.75f

        val updated = property.manipulateProperty(source)!!

        assertTrue(updated.contains("@init\nslider1 = 0.75;\nstate = 1;"))
    }

    @Test
    fun createsInitBeforeSliderWhenScriptHasNoInit() {
        val source = """
            slider1:.5<0,1,.1>Gain
            @slider
            gain = slider1;
            @sample
            spl0 *= gain;
        """.trimIndent()
        val property = parse(source).properties.single() as EelNumberRangeProperty<Float>
        property.value = 0.25f

        val updated = property.manipulateProperty(source)!!

        assertTrue(updated.contains("@init\nslider1 = 0.25;\n@slider"))
        assertTrue(updated.indexOf("@init") < updated.indexOf("@slider"))
    }

    private fun parse(source: String) = EelParser().apply {
        contents = source
        parse()
    }
}
