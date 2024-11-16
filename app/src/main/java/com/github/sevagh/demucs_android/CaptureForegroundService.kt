package com.github.sevagh.demucs_android

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CaptureForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? {
        // This service is not bound to any activity, so return null
        return null
    }

    private var sampleRate: Int = 48000
    private var outPath: String = ""
    private lateinit var tempFilePath: String
    private lateinit var fileOutputStream: FileOutputStream

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Extract parameters from intent
        outPath = intent?.getStringExtra("writePath") ?: ""
        sampleRate = intent?.getIntExtra("sampleRate", 48000) ?: 48000
        val captureIntent = intent?.getParcelableExtra("captureIntent", Intent::class.java)

        // Start in the foreground with a persistent notification
        startForeground(NOTIFICATION_ID, createNotification())

        // Launch your long-running NDK process in a background thread
        startRecording(captureIntent)

        // START_NOT_STICKY tells the system not to recreate the service after it's been killed
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()

        audioRecorder?.release()
        audioRecorder = null

        val intent = Intent("ACTION_CAPTURE_JOB_STOPPED").apply {
            putExtra("EXTRA_ELAPSED", elapsedTime)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    fun captureElapsedUpdate(elapsedTime: Int) {
        val intent = Intent("ACTION_CAPTURE_ELAPSED_TIME").apply {
            putExtra("EXTRA_ELAPSED", elapsedTime)
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
        private const val NOTIFICATION_ID = 2
        private const val NOTIFICATION_CHANNEL_ID = "DemucsAndroidChannel"
        private const val NOTIFICATION_CHANNEL_DESCRIPTION = "Capturing app audio from Music Demixer"
        private const val NOTIFICATION_CONTENT_TITLE = "Capturing app audio"
        private const val NOTIFICATION_CONTENT_TEXT = "Your app or screen audio is being recorded"

        init {
            System.loadLibrary("demucs_ndk")
        }
    }

    private external fun writeAudioFile(
        tempFilePath: String,
        sampleRate: Int,
        filePath: String
    )

    private var isRecording = false
    private var audioRecorder: AudioRecord? = null
    private var mediaProjection: MediaProjection? = null

    private var elapsedTime = 0

    private val handler = Handler(Looper.getMainLooper())
    private val updateRecordingTime = object : Runnable {
        override fun run() {
            if (isRecording) {
                elapsedTime++ // Increment time
                captureElapsedUpdate(elapsedTime)
                handler.postDelayed(this, 1000) // Schedule the next update
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startRecording(captureIntent: Intent?) {
        isRecording = true
        elapsedTime = 0

        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, captureIntent!!)

        val audioPlaybackCaptureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA) // Capture media playback
            .build()

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        audioRecorder = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build())
            .setBufferSizeInBytes(minBufferSize)
            .setAudioPlaybackCaptureConfig(audioPlaybackCaptureConfig)
            .build()

        handler.post(updateRecordingTime)

        // Create a temp file
        val tempFile = File.createTempFile("audio_capture", ".raw", cacheDir)
        tempFilePath = tempFile.absolutePath
        fileOutputStream = FileOutputStream(tempFile)

        audioRecorder?.startRecording()

        // Start a thread to read from AudioRecord into the temp file
        Thread {
            val buffer = ShortArray(minBufferSize)
            while (isRecording) {
                val readResult = audioRecorder?.read(buffer, 0, buffer.size) ?: 0
                if (readResult > 0) {
                    val byteBuffer = ByteBuffer.allocate(readResult * 2)
                    byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
                    byteBuffer.asShortBuffer().put(buffer, 0, readResult)
                    fileOutputStream.write(byteBuffer.array())
                }
            }
            fileOutputStream.close()
        }.start()
    }

    private fun stopRecording() {
        isRecording = false

        audioRecorder?.apply {
            stop()
            release()
        }
        audioRecorder = null
        handler.removeCallbacks(updateRecordingTime)

        // Call the native function to process the temp file
        writeAudioFile(tempFilePath, sampleRate, outPath)

        // Delete the temp file
        val tempFile = File(tempFilePath)
        if (tempFile.exists()) {
            tempFile.delete()
        }

        // Stop the media projection
        mediaProjection?.stop()
        mediaProjection = null
    }
}
