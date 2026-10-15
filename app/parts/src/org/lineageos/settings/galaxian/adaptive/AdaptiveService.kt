/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.adaptive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.lineageos.settings.galaxian.PartsActivity
import org.lineageos.settings.galaxian.R

/** Explicit opt-in. Telemetry, approval and device writes share one serial worker. */
class AdaptiveService : Service() {
    private data class Command(
        val action: String?, val startId: Int, val objective: String? = null,
        val proposalId: String? = null,
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val owner = Any()
    private val repository by lazy { AdaptiveRepository.get(this) }
    private var publishedProposalId: String? = null

    override fun onCreate() {
        super.onCreate()
        // Pending proposals are in-memory only; an old notification cannot revive one.
        clearProposalNotification(this)
        scope.launch {
            while (isActive) {
                val command = if (repository.isRunning(owner)) withTimeoutOrNull(5_000) { commands.receive() }
                    else commands.receive()
                try {
                    if (command == null) {
                        repository.tick(owner)
                    } else when (command.action) {
                        ACTION_START -> repository.startSession(owner)
                        ACTION_OBJECTIVE -> {
                            val objective = runCatching { AdaptiveObjective.valueOf(command.objective.orEmpty()) }.getOrNull()
                            if (objective != null) repository.changeObjective(objective)
                            if (!repository.isRunning(owner)) stopSelfResult(command.startId)
                        }
                        ACTION_APPROVE, ACTION_REJECT -> {
                            command.proposalId?.let { id ->
                                if (command.action == ACTION_APPROVE) repository.approveProposal(owner, id)
                                else repository.rejectProposal(owner, id)
                            }
                            if (!repository.isRunning(owner)) stopSelfResult(command.startId)
                        }
                        else -> {
                            repository.stopSession(owner, explicit = true)
                            // Do not destroy a newer start queued after this stop request.
                            if (stopSelfResult(command.startId)) stopForeground(STOP_FOREGROUND_REMOVE)
                        }
                    }
                    if (isActive) publishProposalNotification()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.e(TAG, "Adaptive control failed; restoring owned limits", error)
                    repository.stopSession(owner)
                    clearProposalNotification(this@AdaptiveService)
                    if (command != null) stopSelfResult(command.startId) else stopSelf()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            try {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } catch (error: Exception) {
                Log.e(TAG, "Cannot start adaptive foreground service", error)
                commands.trySend(Command(ACTION_STOP, startId))
                return START_NOT_STICKY
            }
        }
        commands.trySend(Command(intent?.action, startId, intent?.getStringExtra(EXTRA_OBJECTIVE),
            intent?.getStringExtra(EXTRA_PROPOSAL_ID)))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        commands.close()
        clearProposalNotification(this)
        // The owner token prevents an old instance from stopping a newer session.
        CoroutineScope(Dispatchers.IO).launch { repository.stopSession(owner) }
        super.onDestroy()
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(NotificationChannel(CHANNEL_ID,
                getString(R.string.adaptive_notification_channel), NotificationManager.IMPORTANCE_LOW))
            createNotificationChannel(NotificationChannel(PROPOSAL_CHANNEL_ID,
                getString(R.string.adaptive_review_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(this, 0, openAdaptiveIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, AdaptiveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_galaxian_parts)
            .setContentTitle(getString(R.string.adaptive_notification_title))
            .setContentText(getString(R.string.adaptive_review_monitoring))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.adaptive_notification_stop), stop).build())
            .build()
    }

    private fun openAdaptiveIntent() = Intent(this, PartsActivity::class.java)
        .setAction(PartsActivity.ACTION_OPEN_ADAPTIVE)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun publishProposalNotification() {
        // Notification permissions/channels are independent from the controller. A blocked
        // notification never applies a proposal or prevents review inside the app.
        try {
            val manager = getSystemService(NotificationManager::class.java)
            val pending = repository.snapshot().pendingProposal.takeIf { repository.isRunning(owner) }
            if (pending == null || !proposalNotificationsEnabled(this)) {
                manager.cancel(PROPOSAL_NOTIFICATION_ID)
                publishedProposalId = null
                return
            }
            if (publishedProposalId == pending.id) return
            val remaining = pending.expiresElapsedMs - SystemClock.elapsedRealtime()
            if (remaining <= 0) {
                manager.cancel(PROPOSAL_NOTIFICATION_ID)
                publishedProposalId = null
                return
            }
            val proposal = pending.proposal
            val cluster = if (proposal.target == AdaptiveTarget.GPU) getString(R.string.temperature_type_gpu) else when (proposal.policyId) {
                0 -> getString(R.string.adaptive_cluster_efficiency)
                4 -> getString(R.string.adaptive_cluster_performance)
                else -> getString(R.string.adaptive_cluster_number, proposal.policyId)
            }
            val detail = getString(R.string.adaptive_decision_limits, cluster,
                getString(R.string.adaptive_frequency, proposal.beforeMaximum / 1_000),
                getString(R.string.adaptive_frequency, proposal.targetMaximum / 1_000))
            // Unique immutable identity: an old tap always refers to the old proposal.
            val intent = openAdaptiveIntent()
                .putExtra(EXTRA_PROPOSAL_ID, pending.id)
                .setData(Uri.Builder().scheme("galaxian-parts").authority("adaptive").appendPath(pending.id).build())
            val open = PendingIntent.getActivity(this, 2, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(PROPOSAL_NOTIFICATION_ID, Notification.Builder(this, PROPOSAL_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_galaxian_parts)
                .setContentTitle(getString(R.string.adaptive_review_notification_title))
                .setContentText(detail)
                .setStyle(Notification.BigTextStyle().bigText(detail))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setTimeoutAfter(remaining)
                .addAction(Notification.Action.Builder(null, getString(R.string.adaptive_review_title), open).build())
                .build())
            publishedProposalId = pending.id
        } catch (error: RuntimeException) {
            Log.w(TAG, "Proposal notification unavailable; review remains available in the app", error)
        }
    }

    companion object {
        const val ACTION_START = "org.lineageos.settings.galaxian.adaptive.START"
        const val ACTION_STOP = "org.lineageos.settings.galaxian.adaptive.STOP"
        const val ACTION_OBJECTIVE = "org.lineageos.settings.galaxian.adaptive.OBJECTIVE"
        const val ACTION_APPROVE = "org.lineageos.settings.galaxian.adaptive.APPROVE"
        const val ACTION_REJECT = "org.lineageos.settings.galaxian.adaptive.REJECT"
        const val EXTRA_OBJECTIVE = "objective"
        const val EXTRA_PROPOSAL_ID = "proposal_id"
        private const val TAG = "GalaxianAdaptive"
        private const val CHANNEL_ID = "galaxian_adaptive"
        private const val PROPOSAL_CHANNEL_ID = "galaxian_adaptive_proposals"
        private const val NOTIFICATION_ID = 7300
        private const val PROPOSAL_NOTIFICATION_ID = 7301

        internal fun proposalNotificationsEnabled(context: Context): Boolean = try {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.areNotificationsEnabled() &&
                manager.getNotificationChannel(PROPOSAL_CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        } catch (_: RuntimeException) { false }

        internal fun clearProposalNotification(context: Context) {
            runCatching { context.getSystemService(NotificationManager::class.java).cancel(PROPOSAL_NOTIFICATION_ID) }
        }
    }
}
