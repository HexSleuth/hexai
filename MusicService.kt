package com.hexsleuth.hexai

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle

class MusicService : Service(), AudioManager.OnAudioFocusChangeListener {

    private lateinit var mediaSession: MediaSessionCompat
    private var isPlaying = false
    private var isRepeating = false
    private lateinit var notificationManager: NotificationManager
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null

    companion object {
        const val CHANNEL_ID = "hex_ai_music_playback"
        const val NOTIFICATION_ID = 102
        const val ACTION_TOGGLE = "com.hexsleuth.hexai.TOGGLE"
        const val ACTION_START = "com.hexsleuth.hexai.START"
        const val ACTION_STOP = "com.hexsleuth.hexai.STOP"
        const val ACTION_REPEAT = "com.hexsleuth.hexai.REPEAT"
    }

    var onTogglePlayPause: (() -> Unit)? = null
    var onToggleRepeat: (() -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }
    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        setupMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> onTogglePlayPause?.invoke()
            ACTION_START -> {
                requestAudioFocus()
                updateNotification(true, isRepeating)
            }
            ACTION_REPEAT -> {
                onToggleRepeat?.invoke()
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "HEX AI Studio Playback",
                NotificationManager.IMPORTANCE_LOW // Low so it doesn't pop up but shows the dot
            ).apply {
                description = "Controls for background AI media"
                setShowBadge(true) // Crucial for Notification Dot
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "HexMusicService").apply {
            setPlaybackState(PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP)
                .build())
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { onTogglePlayPause?.invoke() }
                override fun onPause() { onTogglePlayPause?.invoke() }
                override fun onStop() { 
                    onTogglePlayPause?.invoke()
                    stopSelf()
                }
            })
            isActive = true
        }
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(this)
                .build()
            focusRequest?.let { audioManager.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (isPlaying) onTogglePlayPause?.invoke()
            }
        }
    }

    fun updateNotification(playing: Boolean, repeating: Boolean = isRepeating) {
        isPlaying = playing
        isRepeating = repeating
        
        val stopIntent = Intent(this, MusicService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("HEX AI Studio")
            .setContentText(if (playing) "Background playback active" else "Playback paused")
            .setOngoing(playing)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (playing) "Pause" else "Play",
                PendingIntent.getService(
                    this,
                    0,
                    Intent(this, MusicService::class.java).setAction(ACTION_TOGGLE),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .addAction(
                R.drawable.ic_repeat,
                if (repeating) "Repeat On" else "Repeat Off",
                PendingIntent.getService(
                    this,
                    10,
                    Intent(this, MusicService::class.java).setAction(ACTION_REPEAT),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setStyle(MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1)
            )

        try {
            if (playing) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                } else {
                    startForeground(NOTIFICATION_ID, builder.build())
                }
            } else {
                stopForeground(STOP_FOREGROUND_DETACH)
                notificationManager.notify(NOTIFICATION_ID, builder.build())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(this)
        }
        mediaSession.release()
        super.onDestroy()
    }
}
