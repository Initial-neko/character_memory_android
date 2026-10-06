package com.charactermemory.android.audio

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.net.Uri
import com.charactermemory.android.MainActivity
import kotlinx.coroutines.*

/** Explicit, non-restarting call lease. UI unbinding never means hangup. */
class CallSessionService : Service() {
    companion object {
        private const val CHANNEL = "character_memory_call"
        private const val ID = 4109
        private const val END = "com.charactermemory.android.call.END"
        private const val EXTRA_STARTED_AT = "com.charactermemory.android.call.STARTED_AT"
        private const val EXTRA_LEASE_TOKEN = "com.charactermemory.android.call.LEASE_TOKEN"
        private const val EXTRA_START_ID = "com.charactermemory.android.call.START_ID"
        private val leasePolicy = CallServiceLeasePolicy()
        private var heldCall: VoiceCallCoordinator? = null
        private var heldLease: CallServiceLease? = null
        private var heldName = "人物"

        fun owns(call: VoiceCallCoordinator): Boolean {
            val lease = heldLease ?: return false
            val state = call.state.value
            return heldCall === call && state.active && leasePolicy.owns(lease, state.startedAtMs)
        }

        fun begin(context: Context, call: VoiceCallCoordinator, name: String) {
            val state = call.state.value
            check(state.active && state.startedAtMs > 0) { "通话尚未建立，无法创建服务租约" }
            check(heldCall == null || heldCall === call || heldCall?.state?.value?.active != true)
            val lease = leasePolicy.issue(state.startedAtMs)
            heldCall = call
            heldLease = lease
            heldName = name
            val intent = Intent(context, CallSessionService::class.java)
                .putExtra(EXTRA_STARTED_AT, lease.startedAtMs)
                .putExtra(EXTRA_LEASE_TOKEN, lease.token)
            try {
                context.startForegroundService(intent)
            } catch (error: Exception) {
                if (leasePolicy.releasePending(lease)) clearHeldLease(lease)
                throw error
            }
        }

        private fun clearHeldLease(lease: CallServiceLease) {
            val current = heldLease ?: return
            if (current.startedAtMs == lease.startedAtMs && current.token == lease.token) {
                heldLease = null
                heldCall = null
                heldName = "人物"
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var serviceCall: VoiceCallCoordinator? = null
    private var serviceLease: CallServiceLease? = null
    private var observer: Job? = null
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        ensureCallNotificationChannel()
        showBootstrapForegroundNotification()
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundStarted) showBootstrapForegroundNotification()
        if (intent?.action == END) {
            val requestedLease = intent.callLease() ?: return handleUnmatchedStart(startId)
            if (hangUp(requestedLease, startId)) return START_NOT_STICKY
            adoptCurrentLease(startId)
            return START_NOT_STICKY
        }
        adoptCurrentLease(startId)
        return START_NOT_STICKY
    }

    private fun handleUnmatchedStart(startId: Int): Int {
        if (leasePolicy.currentLease() == null) stopSelf(startId)
        else adoptCurrentLease(startId)
        return START_NOT_STICKY
    }

    /** A stale start may still advance Android's startId; attach that id to the live lease. */
    private fun adoptCurrentLease(startId: Int) {
        val pendingLease = leasePolicy.currentLease() ?: run {
            stopSelf(startId)
            return
        }
        val call = heldCall ?: run {
            if (pendingLease.startId == 0) leasePolicy.releasePending(pendingLease)
            else leasePolicy.claim(pendingLease, pendingLease.startedAtMs)
            if (leasePolicy.currentLease() == null) stopSelf(startId)
            return
        }
        val state = call.state.value
        if (!state.active || state.startedAtMs != pendingLease.startedAtMs) {
            if (state.startedAtMs == pendingLease.startedAtMs &&
                leasePolicy.claim(pendingLease, state.startedAtMs)
            ) {
                clearHeldLease(pendingLease)
            }
            stopSelf(startId)
            return
        }
        val lease = leasePolicy.recordStart(pendingLease, startId) ?: return
        heldLease = lease
        serviceCall = call
        serviceLease = lease

        showForegroundNotification(lease, heldName, if (state.microphoneMuted) "麦克风已静音" else "语音通话中")

        observer?.cancel()
        observer = scope.launch {
            call.state.collect { observedState ->
                if (!leasePolicy.isCurrent(lease, observedState.startedAtMs)) return@collect
                if (!observedState.active) {
                    if (leasePolicy.claim(lease, observedState.startedAtMs)) {
                        clearHeldLease(lease)
                        clearServiceLease(lease)
                        removeForegroundNotification()
                        stopSelf(lease.startId)
                    }
                    return@collect
                }
                showForegroundNotification(
                    lease,
                    heldName,
                    if (observedState.microphoneMuted) "麦克风已静音" else "语音通话中"
                )
            }
        }
    }

    private fun hangUp(requestedLease: CallServiceLease, commandStartId: Int): Boolean {
        val call = serviceCall ?: return false
        if (serviceLease != requestedLease) return false
        val state = call.state.value
        if (!leasePolicy.claim(requestedLease, state.startedAtMs)) return false

        clearHeldLease(requestedLease)
        clearServiceLease(requestedLease)
        call.end()
        removeForegroundNotification()
        stopSelf(commandStartId)
        return true
    }

    private fun clearServiceLease(lease: CallServiceLease) {
        if (serviceLease == lease) {
            serviceLease = null
            serviceCall = null
            observer?.cancel()
            observer = null
        }
    }

    private fun Intent.callLease(): CallServiceLease? {
        if (!hasExtra(EXTRA_STARTED_AT) || !hasExtra(EXTRA_LEASE_TOKEN) || !hasExtra(EXTRA_START_ID)) return null
        return CallServiceLease(
            startedAtMs = getLongExtra(EXTRA_STARTED_AT, 0),
            token = getLongExtra(EXTRA_LEASE_TOKEN, 0),
            startId = getIntExtra(EXTRA_START_ID, 0)
        )
    }

    private fun ensureCallNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "语音通话", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun showBootstrapForegroundNotification() {
        val value = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("语音通话")
            .setContentText("正在确认通话状态")
            .setOngoing(true)
            .build()
        startForegroundNotification(value, includeMicrophone = false)
    }

    private fun showForegroundNotification(lease: CallServiceLease, name: String, status: String) {
        startForegroundNotification(notification(lease, name, status), includeMicrophone = true)
    }

    private fun startForegroundNotification(value: Notification, includeMicrophone: Boolean) {
        when {
            Build.VERSION.SDK_INT >= 30 && includeMicrophone -> startForeground(
                ID,
                value,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
            Build.VERSION.SDK_INT >= 29 -> startForeground(ID, value, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else -> startForeground(ID, value)
        }
        foregroundStarted = true
    }

    private fun removeForegroundNotification() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
    }

    private fun notification(lease: CallServiceLease, name: String, status: String): Notification {
        val stopIntent = Intent(this, CallSessionService::class.java)
            .setAction(END)
            .setData(Uri.parse(leasePolicy.notificationIdentity(lease, kind = "end")))
            .putLease(lease)
        val stop = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data = Uri.parse(leasePolicy.notificationIdentity(lease, kind = "return"))
            putLease(lease)
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("$name · $status")
            .setContentText("点击返回通话，或选择挂断")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "挂断", stop).build())
            .setOngoing(true)
            .build()
    }

    private fun Intent.putLease(lease: CallServiceLease): Intent =
        putExtra(EXTRA_STARTED_AT, lease.startedAtMs)
            .putExtra(EXTRA_LEASE_TOKEN, lease.token)
            .putExtra(EXTRA_START_ID, lease.startId)

    override fun onDestroy() {
        observer?.cancel()
        observer = null
        scope.cancel()
        val lease = serviceLease
        val call = serviceCall
        if (lease != null && call != null && leasePolicy.claim(lease, call.state.value.startedAtMs)) {
            clearHeldLease(lease)
            if (call.state.value.active) call.end("通话服务已结束")
        }
        serviceLease = null
        serviceCall = null
        removeForegroundNotification()
        super.onDestroy()
    }
}
