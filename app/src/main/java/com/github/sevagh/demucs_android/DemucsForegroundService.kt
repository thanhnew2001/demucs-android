package com.github.sevagh.demucs_android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class DemucsAndroidForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? {
        // This service is not bound to any activity, so return null
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Extract parameters from intent
        val audioFilePath = intent?.getStringExtra("audioFilePath") ?: ""
        val selectedModel = intent?.getStringExtra("model") ?: ""
        val modelFilePaths = intent?.getStringArrayExtra("modelFilePaths") ?: arrayOf()
        val outDir = intent?.getStringExtra("outDir") ?: ""

        // Start in the foreground with a persistent notification
        startForeground(NOTIFICATION_ID, createNotification())

        // Launch your long-running NDK process in a background thread
        Thread {
            // Replace 'demucsInference' with your actual NDK method call
            // Example call: demucsInference(audioFilePath, selectedModel, modelFilePaths, numThreads)
            // Notify completion or handle errors

            // Stop the service once the processing is done
            val writtenStems = demucsInference(audioFilePath, selectedModel, modelFilePaths, outDir)

            val completionIntent = Intent("ACTION_DEMIX_JOB_COMPLETED").apply {
                putExtra("writtenStems", writtenStems)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(completionIntent)
            stopSelf()
        }.start()

        // START_NOT_STICKY tells the system not to recreate the service after it's been killed
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopInference()

        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("ACTION_DEMIX_JOB_STOPPED"))
    }

    fun inferenceProgressUpdate(progress: Float, message: String) {
        val intent = Intent("ACTION_DEMIX_PROGRESS_UPDATE").apply {
            putExtra("EXTRA_PROGRESS", progress)
            putExtra("EXTRA_MESSAGE", message)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun createNotification(): android.app.Notification {
        createNotificationChannel()
        val notificationChannelId = NOTIFICATION_CHANNEL_ID

        // You should setup Notification Channel in Application class or here before creating notification
        // Refer to Android documentation for Notification Channels

        return NotificationCompat.Builder(this, notificationChannelId)
            .setContentTitle(NOTIFICATION_CONTENT_TITLE)
            .setContentText(NOTIFICATION_CONTENT_TEXT)
            .setColor(ContextCompat.getColor(this, R.color.colorBackground))
            .build()
    }

    private fun createNotificationChannel() {
        val name = NOTIFICATION_CHANNEL_ID
        val importance = NotificationManager.IMPORTANCE_DEFAULT
        val channel = NotificationChannel(name, name, importance).apply {
            description = NOTIFICATION_CHANNEL_DESCRIPTION
        }
        // Register the channel with the system
        val notificationManager: NotificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "DemucsAndroidChannel"
        private const val NOTIFICATION_CHANNEL_DESCRIPTION = "Music demixing job is currently running..."
        private const val NOTIFICATION_CONTENT_TITLE = "Demixing in progress"
        private const val NOTIFICATION_CONTENT_TEXT = "Your audio file is being processed..."

        init {
            System.loadLibrary("demucs_ndk")
        }
    }

    private external fun stopInference()
    private external fun demucsInference(audioFilePath: String, modelName: String, modelFilePaths: Array<String>, outDir: String): Array<String>
}
