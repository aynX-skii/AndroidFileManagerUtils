package com.aynux.afmu.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.aynux.afmu.core.LocaleHelper

/**
 * Makes every [androidx.compose.ui.res.stringResource] below resolve in [language].
 *
 * The alternative is `Activity.recreate()`, which this replaces. Recreating worked, but it is a
 * request to the system that lands whole frames later, and driving it from composition state was
 * a standing source of trouble: the flag had to be consumed exactly once or the rebuilt activity
 * asked to be rebuilt again, and a tap arriving while a relaunch was still in flight could be
 * dropped, leaving the UI on the old language. Swapping a composition local is synchronous,
 * cannot race, and costs one recomposition.
 *
 * [LocalContext] is deliberately *not* overridden — it is what dialogs and CameraX resolve their
 * activity and window from, and a configuration context is not an Activity.
 */
@Composable
fun ProvideAppLocale(language: String, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val resources = remember(base, language) { LocaleHelper.wrap(base, language).resources }
    CompositionLocalProvider(
        LocalResources provides resources,
        LocalConfiguration provides resources.configuration,
        content = content,
    )
}
