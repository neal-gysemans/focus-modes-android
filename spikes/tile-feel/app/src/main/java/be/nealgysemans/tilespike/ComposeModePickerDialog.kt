package be.nealgysemans.tilespike

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

/**
 * Dialog picker, implementation (b): a [ComposeView] inside a plain [Dialog].
 *
 * A TileService is a Service, so there is no Activity to inherit ViewTree owners from.
 * Compose refuses to compose without a LifecycleOwner in the view tree, and
 * `rememberSaveable` / `viewModel()` blow up without the other two. So all three owners
 * are implemented and driven by hand here — and the lifecycle has to be pumped manually
 * around [show]/[dismiss], because nothing else will do it for a Service-hosted window.
 *
 * That is ~70 lines of plumbing for the same three rows that [ClassicModePicker] draws
 * in ~30 with zero failure modes. See the README's verdict.
 */
class ComposeModePickerDialog(
    context: Context,
    private val current: FakeMode?,
    private val onPick: (FakeMode?) -> Unit,
) : Dialog(context, R.style.Theme_TileSpike_TileDialog_Compose),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    init {
        // Must happen while the registry is still INITIALIZED, i.e. before any
        // lifecycle event is dispatched below.
        savedStateController.performAttach()
        savedStateController.performRestore(null)

        val composeView = ComposeView(this.context).apply {
            setViewTreeLifecycleOwner(this@ComposeModePickerDialog)
            setViewTreeViewModelStoreOwner(this@ComposeModePickerDialog)
            setViewTreeSavedStateRegistryOwner(this@ComposeModePickerDialog)
            setContent {
                SpikeTheme {
                    ModePickerContent(
                        title = "Focus",
                        footer = "ComposeView dialog",
                        current = current,
                        onPick = {
                            onPick(it)
                            dismiss()
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
