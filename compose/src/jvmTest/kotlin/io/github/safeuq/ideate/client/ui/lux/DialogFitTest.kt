package io.github.safeuq.ideate.client.ui.lux

import kotlin.test.Test
import kotlin.test.assertEquals

class DialogFitTest {
  @Test
  fun keepsDialogsThatFitWhole() {
    assertEquals(1f, dialogFit(395f, 358))
  }

  @Test
  fun shrinksSlightlyWiderDialogsToFit() {
    assertEquals(395f / 440, dialogFit(395f, 440))
  }

  @Test
  fun neverShrinksBelowReadable() {
    assertEquals(LEAST_FIT, dialogFit(395f, 1920))
  }

  @Test
  fun keepsUnsizedDialogsWhole() {
    assertEquals(1f, dialogFit(395f, 0))
  }
}
