package intellijandroid.client.session

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Undo and Redo's enabled state from what the backend told about its undo stacks. */
class UndoAvailableTest {
  @Test
  fun undoIsOfferedUntilTheStacksAreKnownEmpty() {
    assertTrue(undoAvailable(sizesKnown = false, editedSince = false, anyUndo = false))
    assertFalse(undoAvailable(sizesKnown = true, editedSince = false, anyUndo = false))
    assertTrue(undoAvailable(sizesKnown = true, editedSince = true, anyUndo = false))
    assertTrue(undoAvailable(sizesKnown = true, editedSince = false, anyUndo = true))
  }

  @Test
  fun redoIsOfferedOnlyRightAfterAnUndo() {
    assertFalse(redoAvailable(sizesKnown = false, editedSince = false, anyRedo = false))
    assertTrue(redoAvailable(sizesKnown = true, editedSince = false, anyRedo = true))
    // Typing empties the backend's redo stack.
    assertFalse(redoAvailable(sizesKnown = true, editedSince = true, anyRedo = true))
  }
}
