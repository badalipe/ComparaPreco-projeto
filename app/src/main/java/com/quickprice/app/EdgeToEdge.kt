package com.quickprice.app

import android.graphics.Color
import android.os.Build
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Configuração comum de edge-to-edge com áreas seguras para controlos interativos. */
internal object EdgeToEdge {
    fun configure(activity: AppCompatActivity) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        activity.window.statusBarColor = Color.TRANSPARENT
        activity.window.navigationBarColor = Color.TRANSPARENT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.window.isStatusBarContrastEnforced = false
            activity.window.isNavigationBarContrastEnforced = false
        }

        WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    fun applySystemInsets(view: View, onInsets: (Insets) -> Unit) {
        applyInsets(view) { systemInsets, _ -> onInsets(systemInsets) }
    }

    fun applyInsets(view: View, onInsets: (Insets, Insets) -> Unit) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
            val safeTypes = WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout()
            onInsets(windowInsets.getInsets(safeTypes), windowInsets.getInsets(WindowInsetsCompat.Type.ime()))
            windowInsets
        }
        ViewCompat.requestApplyInsets(view)
    }
}
