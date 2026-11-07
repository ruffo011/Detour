package dev.detour.guard.detect

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * A 1×1 transparent, untouchable overlay window kept on screen while the detector runs.
 *
 * Why: since Android 15, holding "Display over other apps" only lets an app start an activity
 * from the background if it currently has a *visible* overlay window. With this window present,
 * the detector can launch the full-screen intervention immediately. It draws nothing, can't be
 * touched, and takes no focus, so it doesn't affect anything on screen.
 */
class OverlayKeepAlive(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: View? = null

    fun attach() {
        if (view != null || !Settings.canDrawOverlays(context)) return
        val v = View(context)
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "Detour"
        }
        runCatching { wm.addView(v, params) }
            .onSuccess { view = v }
            .onFailure { Log.w("OverlayKeepAlive", "Could not add overlay", it) }
    }

    fun detach() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }
}
