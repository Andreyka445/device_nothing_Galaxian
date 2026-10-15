/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.adaptive

/** Counts from one rendering layer in the sampled, finite SurfaceFlinger history.
 *
 * Tokens identify frames across dumps. Dump timestamps have a moving relative origin,
 * so observedDurationNs is a duration, never an absolute uptime. Package/layer names
 * are deliberately absent. Missing classification is never counted as smoothness.
 */
internal data class AdaptiveFrameWindow(
    val contextKey: String,
    val frameCount: Int,
    val jankyFrames: Int,
    val droppedFrames: Int,
    val appJankyFrames: Int,
    val compositorJankyFrames: Int,
    val framePeriodNs: Long,
    val refreshPeriodNs: Long,
    val firstFrameToken: Long,
    val lastFrameToken: Long,
    val sampledElapsedMs: Long,
    val observedDurationNs: Long,
    val frameTimeP95Ms: Double? = null,
)

internal data class FrameTelemetryResult(
    val window: AdaptiveFrameWindow?,
    val reason: String,
    val sampledFrames: Int = 0,
    val classifiedFrames: Int = 0,
    val unknownFrames: Int = 0,
    /** The compositor ring is finite; a snapshot never implies full-session coverage. */
    val partialCoverage: Boolean = true,
)
