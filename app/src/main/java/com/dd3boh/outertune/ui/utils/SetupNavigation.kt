package com.dd3boh.outertune.ui.utils

import androidx.navigation.NavController
import com.dd3boh.outertune.constants.OOBE_VERSION

/** Activity recreation already restores the wizard, including a child backup/login screen. */
internal fun NavController.openSetupIfNeeded(oobeStatus: Int) {
    if (oobeStatus >= OOBE_VERSION) return
    val setupAlreadyOpen = runCatching { getBackStackEntry("setup_wizard") }.isSuccess
    if (!setupAlreadyOpen) navigate("setup_wizard")
}
