package com.dd3boh.outertune.fixtures

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import java.util.Locale

/** Empty debug-only host: instrumentation supplies real UI without starting the player or search. */
class SearchUiFixtureActivity : ComponentActivity() {
    lateinit var fixtureView: ComposeView
        private set

    override fun attachBaseContext(newBase: Context) {
        val configuration = Configuration(newBase.resources.configuration).apply {
            setLocale(Locale.JAPANESE)
        }
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fixtureView = ComposeView(this)
        setContentView(fixtureView)
    }
}
