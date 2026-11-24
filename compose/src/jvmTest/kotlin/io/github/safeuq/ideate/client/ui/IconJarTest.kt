package io.github.safeuq.ideate.client.ui

import io.github.safeuq.ideate.client.ui.theme.ISLANDS_DARK
import io.github.safeuq.ideate.client.ui.theme.ISLANDS_LIGHT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IconJarTest {
  @Test
  fun `light check box is recoloured by element id, fill and stroke from the two keys`() {
    val svg = IconJar.extra("laf/checkBox.svg", ISLANDS_LIGHT)!!
    assertTrue("""fill="#FFFFFF" stroke="#A8ADBD"""" in svg, svg)
  }

  @Test
  fun `an id of one key sets only the attributes the element has`() {
    val svg = IconJar.extra("laf/checkBoxSelectedFocused.svg", ISLANDS_LIGHT)!!
    assertTrue(
      """<rect id="Checkbox.Focus.Wide" x="2" y="2" width="20" height="20" rx="4.5" stroke="#3574F0"""" in
        svg,
      svg,
    )
    assertTrue("""stroke="#FFFFFF" stroke-width="2" stroke-linecap""" in svg, svg)
  }

  @Test
  fun `dark keeps the dark check box as drawn`() {
    val svg = IconJar.extra("laf/checkBox.svg", ISLANDS_DARK)!!
    assertTrue("""fill="#2B2D30" stroke="#5A5D63"""" in svg, svg)
  }

  @Test
  fun `dark takes the _dark file through the palette, light the plain file`() {
    assertTrue("#C3C5CB" in IconJar.extra("/markdown/link.svg", ISLANDS_DARK)!!)
    assertTrue("#6C707E" in IconJar.extra("/markdown/link.svg", ISLANDS_LIGHT)!!)
    assertEquals(null, IconJar.extra("missing.svg", ISLANDS_LIGHT))
  }
}
