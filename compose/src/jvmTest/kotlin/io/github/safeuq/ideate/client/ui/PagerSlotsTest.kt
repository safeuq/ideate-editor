package io.github.safeuq.ideate.client.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PagerSlotsTest {
  @Test
  fun aPageBroughtInByTheFingerKeepsItsSlot() {
    val slots = PagerSlots("middle")
    slots.placeBeside("left", -1)
    assertEquals(-1, slots.slotOf("left"))
    // Order says "to the right", but the page is already there on the left.
    slots.show("left", toTheRight = true)
    assertEquals(-1, slots.currentSlot)
    assertEquals(0, slots.slotOf("middle"), "the page left is still there to slide out")
  }

  @Test
  fun aPageShownFromElsewhereComesInOnTheSideItsOrderSays() {
    val slots = PagerSlots("alpha")
    slots.show("gamma", toTheRight = true)
    assertEquals(1, slots.currentSlot)
    slots.show("alpha", toTheRight = false)
    assertEquals(0, slots.currentSlot)
    slots.settle()
    assertEquals(setOf("alpha"), slots.keys)
    assertNull(slots.slotOf("gamma"))
  }

  @Test
  fun aSideHoldsOnePage() {
    val slots = PagerSlots("middle")
    slots.placeBeside("left", -1)
    slots.placeBeside("other", -1)
    assertNull(slots.slotOf("left"))
    assertEquals(-1, slots.slotOf("other"))
  }
}
