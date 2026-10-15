/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.galaxian.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.DurationBasedAnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import com.android.settingslib.spa.framework.theme.SettingsTheme

/** Uses the same dynamic-colour and typography pipeline as Lineage Settings. */
@Composable
fun GalaxianPartsTheme(content: @Composable () -> Unit) {
    SettingsTheme(content)
}

/** Motion tokens for controls and live readings; page navigation follows Settings. */
object Motion {
    private val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    fun <T> pressSpec(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    fun <T> liveValueSpec(): FiniteAnimationSpec<T> =
        tween(durationMillis = 700, easing = StandardEasing)

    fun <T> shimmerSpec(): DurationBasedAnimationSpec<T> =
        tween(durationMillis = 2_800, easing = LinearEasing)

}

const val shimmerAlpha: Float = 0.05f
