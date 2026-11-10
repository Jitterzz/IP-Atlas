package app.ipatlas

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import app.ipatlas.ui.AtlasTheme
import app.ipatlas.ui.BottomBar
import app.ipatlas.ui.HistoryScreen
import app.ipatlas.ui.HomeScreen
import app.ipatlas.ui.LocalPalette
import app.ipatlas.ui.LocalToaster
import app.ipatlas.ui.ResultScreen
import app.ipatlas.ui.SettingsScreen
import app.ipatlas.ui.ToastHost
import app.ipatlas.ui.Toaster

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) incomingText(intent)?.let { model.handleIncoming(it) }
        setContent { App(model) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingText(intent)?.let { model.handleIncoming(it) }
    }

    /** Text shared to IP Atlas, or picked with "Look up IP" from another app's text selection menu. */
    private fun incomingText(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
        Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        else -> null
    }?.takeIf { it.isNotBlank() }
}

@Composable
private fun App(model: AppModel) {
    AtlasTheme(model.store.settings.theme) {
        val p = LocalPalette.current
        val toaster = remember { Toaster() }
        SystemBarIcons(dark = p.dark)
        CompositionLocalProvider(LocalToaster provides toaster) {
            BackHandler(enabled = model.canGoBack) { model.back() }
            val holder = rememberSaveableStateHolder()
            Box(Modifier.fillMaxSize().background(p.bg)) {
                AnimatedContent(
                    targetState = model.stack.last(),
                    transitionSpec = {
                        val forward = targetState is Screen.Result &&
                            (initialState !is Screen.Result || (targetState as Screen.Result).state.id > (initialState as Screen.Result).state.id)
                        if (forward) {
                            (slideInHorizontally { it / 5 } + fadeIn()) togetherWith fadeOut()
                        } else {
                            fadeIn() togetherWith (slideOutHorizontally { it / 5 } + fadeOut())
                        }
                    },
                    label = "screen",
                ) { screen ->
                    // Keeps each screen's scroll position when you come back to it.
                    holder.SaveableStateProvider(if (screen is Screen.Result) "result-${screen.state.id}" else "main") {
                        when (screen) {
                            Screen.Main -> MainTabs(model)
                            is Screen.Result -> ResultScreen(model, screen.state)
                        }
                    }
                }
                ToastHost(toaster, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 84.dp))
            }
        }
    }
}

@Composable
private fun MainTabs(model: AppModel) {
    val holder = rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            holder.SaveableStateProvider(model.tab.name) {
                when (model.tab) {
                    Tab.LOOKUP -> HomeScreen(model)
                    Tab.HISTORY -> HistoryScreen(model)
                    Tab.SETTINGS -> SettingsScreen(model)
                }
            }
        }
        BottomBar(model.tab) { model.tab = it }
    }
}

/** Light status-bar icons on the night chart, dark ones on the day chart (whatever the phone's own theme). */
@Composable
private fun SystemBarIcons(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }
}
