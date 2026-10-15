/* SPDX-FileCopyrightText: 2023 The Android Open Source Project
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.UserHandle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.material.color.DynamicColors
import org.lineageos.settings.galaxian.adaptive.AdaptiveScreen
import org.lineageos.settings.galaxian.adaptive.AdaptiveService
import org.lineageos.settings.galaxian.battery.BatteryScreen
import org.lineageos.settings.galaxian.cpu.CpuScreen
import org.lineageos.settings.galaxian.gpu.GpuScreen
import org.lineageos.settings.galaxian.memory.MemoryScreen
import org.lineageos.settings.galaxian.temperature.TemperatureScreen
import org.lineageos.settings.galaxian.ui.GalaxianPartsTheme

class PartsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}

class PartsActivity : ComponentActivity() {
    private var pageLaunchPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requireDeviceOwner()) return
        enableEdgeToEdge()
        setContent {
            GalaxianPartsTheme {
                PartsHomeScreen { route ->
                    PartsPage.fromRoute(route)?.let { page ->
                        if (!pageLaunchPending) {
                            pageLaunchPending = true
                            openPage(page)
                        }
                    }
                }
            }
        }
        // Android restores the child Activity itself. Replaying the notification here
        // would open it twice after rotation or process recreation.
        if (savedInstanceState == null) openAdaptiveRequest(intent)
    }

    override fun onResume() {
        super.onResume()
        pageLaunchPending = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (UserHandle.myUserId() == UserHandle.USER_SYSTEM) openAdaptiveRequest(intent)
    }

    private fun openAdaptiveRequest(request: Intent) {
        if (request.action != ACTION_OPEN_ADAPTIVE) return
        openPage(PartsPage.ADAPTIVE, request.getStringExtra(AdaptiveService.EXTRA_PROPOSAL_ID))
        // Keep the notification's identity and flags out of the internal page Intent.
        // Only the requested proposal ID is forwarded, never an approval or action.
        setIntent(Intent(request).apply {
            action = null
            data = null
            removeExtra(AdaptiveService.EXTRA_PROPOSAL_ID)
        })
    }

    private fun openPage(page: PartsPage, proposalId: String? = null) {
        startActivity(Intent(this, PartsSubActivity::class.java).apply {
            putExtra(EXTRA_PAGE, page.route)
            if (page == PartsPage.ADAPTIVE && proposalId != null) {
                putExtra(AdaptiveService.EXTRA_PROPOSAL_ID, proposalId)
            }
        })
    }

    companion object {
        const val ACTION_OPEN_ADAPTIVE = "org.lineageos.settings.galaxian.OPEN_ADAPTIVE"
    }
}

/**
 * Like Settings' SubSettings, each page is an Activity in the existing task.
 * There is deliberately no app-wide back callback or Compose navigation transition:
 * Android animates the two windows, including gesture progress, cancel and commit.
 */
class PartsSubActivity : ComponentActivity() {
    private var page: PartsPage? = null
    private var proposalToReview by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requireDeviceOwner()) return
        val route = if (savedInstanceState == null) intent.getStringExtra(EXTRA_PAGE)
            else savedInstanceState.getString(EXTRA_PAGE)
        val destination = PartsPage.fromRoute(route) ?: run {
            finish()
            return
        }
        page = destination
        if (destination == PartsPage.ADAPTIVE) {
            proposalToReview = if (savedInstanceState == null) {
                intent.getStringExtra(AdaptiveService.EXTRA_PROPOSAL_ID)
            } else savedInstanceState.getString(STATE_PENDING_REVIEW)
        }
        enableEdgeToEdge()
        setContent {
            GalaxianPartsTheme {
                when (destination) {
                    PartsPage.CPU -> CpuScreen(::finish)
                    PartsPage.GPU -> GpuScreen(::finish)
                    PartsPage.MEMORY -> MemoryScreen(::finish)
                    PartsPage.BATTERY -> BatteryScreen(::finish)
                    PartsPage.TEMPERATURE -> TemperatureScreen(::finish)
                    PartsPage.ADAPTIVE -> AdaptiveScreen(
                        onBack = ::finish,
                        proposalToReview = proposalToReview,
                        onReviewRequestConsumed = { proposalToReview = null },
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(EXTRA_PAGE, page?.route)
        outState.putString(STATE_PENDING_REVIEW, proposalToReview)
        super.onSaveInstanceState(outState)
    }
}

private fun ComponentActivity.requireDeviceOwner(): Boolean {
    if (UserHandle.myUserId() == UserHandle.USER_SYSTEM) return true
    Toast.makeText(this, R.string.parts_owner_only, Toast.LENGTH_LONG).show()
    finish()
    return false
}

private enum class PartsPage(val route: String) {
    CPU("cpu"), GPU("gpu"), MEMORY("memory"), BATTERY("battery"),
    TEMPERATURE("temperature"), ADAPTIVE("adaptive");

    companion object {
        fun fromRoute(route: String?): PartsPage? = entries.firstOrNull { it.route == route }
    }
}

private const val EXTRA_PAGE = "org.lineageos.settings.galaxian.PAGE"
private const val STATE_PENDING_REVIEW = "pending_review"
