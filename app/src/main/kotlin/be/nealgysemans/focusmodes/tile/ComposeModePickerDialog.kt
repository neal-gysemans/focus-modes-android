package be.nealgysemans.focusmodes.tile

import android.app.Dialog
import android.content.Context
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import be.nealgysemans.focusmodes.R
import be.nealgysemans.focusmodes.ui.FocusModesTheme
import be.nealgysemans.focusmodes.ui.ModePickerSheet

/**
 * The mode picker the tile shows when a tap has no obvious answer.
 *
 * Compose inside a plain [Dialog], not an XML layout: spike #2 built both on the
 * 17T Pro and the Compose one looked materially better under HyperOS while costing
 * nothing measurable at tap time (the dialog is constructed after the optimistic
 * tile flip has already landed).
 *
 * The price is the plumbing below. A `TileService` is a Service, so there is no
 * Activity to inherit ViewTree owners from, and Compose refuses to compose without a
 * `LifecycleOwner` in the view tree — while `rememberSaveable` and `viewModel()` need
 * the other two. All three are implemented here and the lifecycle is pumped by hand
 * around [show] / [dismiss], because nothing else will do it for a Service-hosted
 * window.
 *
 * @param snapshot the already-warm tile state. The dialog does no I/O: it is built on
 *   the main thread inside a tile tap, so it renders from memory or not at all.
 * @param onPick receives a mode id, or null for "Off". The dialog dismisses itself
 *   first so the caller's optimistic flip is the last thing the user sees move.
 */
class ComposeModePickerDialog(
    context: Context,
    private val snapshot: TileSnapshot,
    private val onPick: (String?) -> Unit,
) : Dialog(context, R.style.Theme_FocusModes_TileDialog),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    init {
        // Must happen while the registry is still INITIALIZED, i.e. before any
        // lifecycle event is dispatched in show().
        savedStateController.performAttach()
        savedStateController.performRestore(null)

        val composeView = ComposeView(this.context).apply {
            setViewTreeLifecycleOwner(this@ComposeModePickerDialog)
            setViewTreeViewModelStoreOwner(this@ComposeModePickerDialog)
            setViewTreeSavedStateRegistryOwner(this@ComposeModePickerDialog)
            setContent {
                FocusModesTheme {
                    ModePickerSheet(
                        modes = snapshot.modes,
                        activeModeId = snapshot.activeModeId,
                        onPick = { picked ->
                            dismiss()
                            onPick(picked)
                        },
                    )
                }
            }
        }
        setContentView(
            composeView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        setCanceledOnTouchOutside(true)
    }

    override fun show() {
        // RESUMED before the window attaches, or the ComposeView never composes.
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        super.show()
    }

    override fun dismiss() {
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        store.clear()
        super.dismiss()
    }
}
