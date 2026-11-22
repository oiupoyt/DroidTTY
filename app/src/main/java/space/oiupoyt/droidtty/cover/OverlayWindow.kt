package space.oiupoyt.droidtty.cover

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * One Compose window drawn by the accessibility service. Accessibility overlays
 * sit above the lock screen and the status bar and get touches, and they open
 * without the launch splash an activity would show.
 */
class OverlayWindow(
    private val context: Context,
    private val name: String,
    /** Called on the back key while this window has focus. */
    private val onBack: (() -> Unit)? = null,
    private val content: @Composable () -> Unit,
) : LifecycleOwner, SavedStateRegistryOwner {

    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: ComposeView? = null

    val isShown: Boolean get() = view != null

    init {
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    /** Adds the window, or updates its flags if it's already there. */
    fun show(params: WindowManager.LayoutParams) {
        val v = view
        if (v != null) {
            runCatching { wm.updateViewLayout(v, params) }
            return
        }
        val fresh = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@OverlayWindow)
            setViewTreeSavedStateRegistryOwner(this@OverlayWindow)
            setContent(content)
            onBack?.let { back ->
                isFocusableInTouchMode = true
                setOnKeyListener { _, code, event ->
                    if (code == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                        back()
                        true
                    } else {
                        code == KeyEvent.KEYCODE_BACK
                    }
                }
            }
        }
        try {
            wm.addView(fresh, params)
            view = fresh
            registry.currentState = Lifecycle.State.RESUMED
        } catch (e: Exception) { // BadTokenException when the service is going away
            Log.w(TAG, "couldn't show the $name window", e)
        }
    }

    fun hide() {
        val v = view ?: return
        view = null
        registry.currentState = Lifecycle.State.CREATED
        runCatching { wm.removeViewImmediate(v) }
    }

    fun update(params: WindowManager.LayoutParams) {
        view?.let { runCatching { wm.updateViewLayout(it, params) } }
    }

    fun destroy() {
        hide()
        registry.currentState = Lifecycle.State.DESTROYED
    }

    val rootView: View? get() = view

    companion object {
        private const val TAG = "OverlayWindow"

        /** A full-screen accessibility overlay, edge to edge and over the cutout. */
        fun fullScreen(
            focusable: Boolean,
            turnScreenOn: Boolean = false,
            keepOn: Boolean = false,
            brightness: Float = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
        ): WindowManager.LayoutParams {
            var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            @Suppress("DEPRECATION")
            if (turnScreenOn) flags = flags or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (keepOn) flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            return WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags,
                PixelFormat.OPAQUE,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                screenBrightness = brightness
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }

        /** A transparent strip along the top edge that catches the swipe down for DroidTTY's shade. */
        fun topStrip(heightPx: Int): WindowManager.LayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            heightPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }
}
