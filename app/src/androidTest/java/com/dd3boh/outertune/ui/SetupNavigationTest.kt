package com.dd3boh.outertune.ui

import android.os.Bundle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavGraph
import androidx.navigation.NavGraphNavigator
import androidx.navigation.NavOptions
import androidx.navigation.Navigator
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.constants.OOBE_VERSION
import com.dd3boh.outertune.ui.utils.openSetupIfNeeded
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real navigation save/restore; screen rendering and network access are outside this fixture. */
class SetupNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun freshStartupAddsOneWizardAndRepeatedStartupStillExitsWithOneBack() = onMain {
        val nav = controller()
        nav.openSetupIfNeeded(0)
        val setupId = nav.getBackStackEntry(SETUP).id

        nav.openSetupIfNeeded(0)
        assertEquals(setupId, nav.currentBackStackEntry!!.id)
        assertExitsToHome(nav)
    }

    @Test
    fun restoredWizardIsNotDuplicatedAndSkipReturnsToHome() = onMain {
        val original = controller()
        original.openSetupIfNeeded(0)
        val setupId = original.currentBackStackEntry!!.id

        val restored = controller(requireNotNull(original.saveState()))
        restored.openSetupIfNeeded(0)
        assertEquals(SETUP, restored.currentDestination!!.route)
        assertEquals(setupId, restored.currentBackStackEntry!!.id)
        assertExitsToHome(restored)
    }

    @Test
    fun restoringBackupChildKeepsItVisibleAndBackThenSkipReturnsToHome() = onMain {
        val original = controller()
        original.openSetupIfNeeded(0)
        original.navigate(BACKUP)
        val backupId = original.currentBackStackEntry!!.id
        val setupId = original.getBackStackEntry(SETUP).id

        val restored = controller(requireNotNull(original.saveState()))
        restored.openSetupIfNeeded(0)
        assertEquals(BACKUP, restored.currentDestination!!.route)
        assertEquals(backupId, restored.currentBackStackEntry!!.id)
        assertTrue(restored.navigateUp())
        assertEquals(setupId, restored.currentBackStackEntry!!.id)
        assertExitsToHome(restored)
    }

    @Test
    fun completedSetupDoesNotRedirectOnFreshOrRestoredStartup() = onMain {
        val original = controller()
        original.openSetupIfNeeded(OOBE_VERSION)
        assertEquals(HOME, original.currentDestination!!.route)
        original.navigate(BACKUP)

        val restored = controller(requireNotNull(original.saveState()))
        restored.openSetupIfNeeded(OOBE_VERSION)
        assertEquals(BACKUP, restored.currentDestination!!.route)
        assertFalse(runCatching { restored.getBackStackEntry(SETUP) }.isSuccess)
    }

    private fun assertExitsToHome(nav: NavController) {
        // SetupWizard's Skip and final-page action both finish with this single navigateUp.
        assertTrue(nav.navigateUp())
        assertEquals(HOME, nav.currentDestination!!.route)
        assertFalse("A completed duplicate wizard would render a blank page",
            runCatching { nav.getBackStackEntry(SETUP) }.isSuccess)
    }

    private fun controller(savedState: Bundle? = null): NavController =
        NavController(instrumentation.targetContext).apply {
            val screenNavigator = FixtureNavigator()
            navigatorProvider.addNavigator(screenNavigator)
            if (savedState != null) restoreState(savedState)
            graph = NavGraph(NavGraphNavigator(navigatorProvider)).apply {
                route = "fixture"
                listOf(HOME, SETUP, BACKUP).forEach { screenRoute ->
                    addDestination(screenNavigator.createDestination().apply { route = screenRoute })
                }
                setStartDestination(HOME)
            }
        }

    @Navigator.Name("setup-fixture")
    private class FixtureNavigator : Navigator<NavDestination>() {
        override fun createDestination() = NavDestination(this)

        override fun navigate(entries: List<NavBackStackEntry>, navOptions: NavOptions?, navigatorExtras: Extras?) {
            entries.forEach(state::push)
        }

        override fun popBackStack(popUpTo: NavBackStackEntry, savedState: Boolean) {
            state.pop(popUpTo, savedState)
        }
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }

    private companion object {
        const val HOME = "home"
        const val SETUP = "setup_wizard"
        const val BACKUP = "settings/backup_restore"
    }
}
