package io.github.safeuq.ideate.client.session

import com.jetbrains.codeWithMe.model.BePortForwardingPanel
import com.jetbrains.ide.model.uiautomation.BeConsoleViewImpl
import com.jetbrains.ide.model.uiautomation.BeContextActionBehavior
import com.jetbrains.ide.model.uiautomation.BeControl
import com.jetbrains.ide.model.uiautomation.BeGrid
import com.jetbrains.ide.model.uiautomation.BeSplitComponentControl
import com.jetbrains.rd.ide.model.KernelAwareTextControlId
import com.jetbrains.rd.ide.model.LuxLayoutSource
import com.jetbrains.rd.ide.model.LxEvent
import com.jetbrains.rd.ide.model.LxLocalBeControl
import com.jetbrains.rd.ide.model.LxLocalComponentId
import com.jetbrains.rd.ide.model.LxPanel
import com.jetbrains.rd.ide.model.LxPoint
import com.jetbrains.rd.ide.model.LxRect
import com.jetbrains.rd.ide.model.LxSize
import com.jetbrains.rd.util.lifetime.Lifetime

/**
 * A control the client draws inside a Lux panel, at [bounds] (backend pixels = dp, in the panel).
 */
sealed interface PanelLocal {
  val bounds: LxRect
}

/** A console, e.g. the Run window's output; [kernelEditorId] keys `SessionState.consoles`. */
data class PanelConsole(val kernelEditorId: String, override val bounds: LxRect) : PanelLocal

/**
 * The Build tree (`BeSplitComponentControl` "BuildTree"); [modelUid] names the build to
 * `BuildTreeApi` (`BuildViewId.uid`, e.g. 5).
 */
data class PanelBuildTree(val modelUid: Int, override val bounds: LxRect) : PanelLocal

/**
 * One shown Lux panel, as JetBrains Client's `BeneluxViewControl` shows it (spec 7.1): it is drawn
 * once it has a screen, a place and `visible`; its size is the tool window's ([resize]) when its
 * layout source is the frontend, else what it asks for. [surface] is what the UI draws. Its
 * client-drawn controls (`localComponents`, spec 7.2) are in `surface.locals`. Called on the
 * protocol thread.
 */
internal class LuxPanelHost(
  private val panel: LxPanel,
  private val lifetime: Lifetime,
  luxId: Int,
  private val log: (String) -> Unit,
) {
  val surface = LuxDialog(luxId, false, true, true, "", 0, 0)
  private val locals = linkedMapOf<LxLocalComponentId, List<PanelLocal>>()
  private val hidden = mutableSetOf<LxLocalComponentId>()

  private fun publishLocals() {
    surface.locals = locals.filterKeys { it !in hidden }.values.flatten()
  }

  init {
    if (panel.layoutSource != LuxLayoutSource.Frontend)
      panel.preferredSize.advise(lifetime) { preferred ->
        panel.size.set(LuxDialogs.fitToScreen(preferred))
      }
    panel.size.advise(lifetime) { size ->
      surface.width = size.width
      surface.height = size.height
    }
    panel.inputLocation.advise(lifetime) { location -> surface.acceptsText = location != null }
    panel.graphics.drawOperations.advise(lifetime) { operations ->
      surface.batches += operations
      surface.version++
    }
    panel.localComponents.view(lifetime) { entryLifetime, id, local ->
      if (local !is LxLocalBeControl) return@view
      val kinds = kindsOf(local.control)
      local.bounds.advise(entryLifetime) { bounds ->
        log("Lux panel ${surface.id}: local ${id.id} has bounds $bounds")
        locals[id] = kinds.map { it(bounds) }
        publishLocals()
      }
      // The backend keeps a Run window's consoles and shows one at a time (a step of the Build tree
      // picks its own console): the others are invisible, not removed.
      local.visible.advise(entryLifetime) { visible ->
        log("Lux panel ${surface.id}: local ${id.id} visible $visible")
        if (visible) hidden -= id else hidden += id
        publishLocals()
      }
      entryLifetime.onTermination {
        locals.remove(id)
        hidden -= id
        publishLocals()
      }
    }
    if (panel.graphicsConfiguration.valueOrNull == null)
      panel.graphicsConfiguration.set(LuxDialogs.CONFIGURATION)
    if (panel.position.valueOrNull == null) panel.position.set(LxPoint(0.0, 0.0))
    panel.visible.set(true)
    // The panel may have been painted for an earlier connection (the backend keeps it), and then
    // nothing new arrives: ask for a frame, as LuxFrontendPanelAdapter.forceHostToRepaint does.
    panel.requestRepaint.start(lifetime, Unit)
  }

  /**
   * What the client draws for a local [control] (a function of the control's bounds): a
   * `BeContextActionBehavior` is looked through, a `BeGrid` gives its items. ponytail: every item
   * of a grid gets the grid's whole bounds, right for the port bar (draws nothing) above the
   * console; lift when a grid with two drawn items shows up.
   */
  private fun kindsOf(control: BeControl): List<(LxRect) -> PanelLocal> =
    when (control) {
      is BeContextActionBehavior -> kindsOf(control.content)
      is BeGrid -> control.items.valueOrNull.orEmpty().flatMap { kindsOf(it.content) }
      is BeConsoleViewImpl -> {
        val kernelEditorId = (control.editorId as? KernelAwareTextControlId)?.kernelEditorId
        if (kernelEditorId == null) {
          log("Lux panel ${surface.id}: console without a kernel editor id: ${control.editorId}")
          emptyList()
        } else listOf { bounds -> PanelConsole(kernelEditorId, bounds) }
      }
      // ponytail: empty unless a port is forwarded; lift with port forwarding.
      is BePortForwardingPanel -> emptyList()
      is BeSplitComponentControl ->
        if (control.placeId == "BuildTree")
          listOf { bounds -> PanelBuildTree(control.modelUid, bounds) }
        else logged(control)
      else -> logged(control)
    }

  private fun logged(control: BeControl): List<(LxRect) -> PanelLocal> {
    log("Lux panel ${surface.id}: no drawing for local control ${control::class.simpleName}")
    return emptyList()
  }

  /**
   * The tool window's content area, in backend pixels (dp), and where it is on Ideate's screen, so
   * the backend places the panel's popups (New ▾) next to it.
   */
  fun resize(x: Int, y: Int, width: Int, height: Int) {
    surface.x = x
    surface.y = y
    panel.position.set(LxPoint(x.toDouble(), y.toDouble()))
    if (panel.layoutSource == LuxLayoutSource.Frontend) panel.size.set(LxSize(width, height))
  }

  /** Asks the backend to paint the panel again, whole. */
  fun repaint() {
    panel.requestRepaint.start(lifetime, Unit)
  }

  /** A tap, a scroll or a key for the panel. */
  fun event(event: LxEvent) {
    panel.events.fire(event)
  }
}
