package com.github.sevagh.demucs_android

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.PlaybackParams
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.IOException
import kotlin.math.min

class CaptureDialogFragment : DialogFragment() {
    private val sharedViewModel: SharedViewModel by activityViewModels()
    private lateinit var savedSettings: SavedSettings

    private lateinit var recordingStatusTextView: TextView
    private lateinit var recordingCurrentDuration: TextView
    private lateinit var recordingImg: ImageView
    private lateinit var recordingSeekBar: SeekBar
    private lateinit var useAsInput: Button

    private var captureIntent: Intent? = null
    private var mediaPlayer: MediaPlayer? = null
    private var finalRecordingFilePath: String? = null
    private var currentRecordingFilePath: String? = null

    private val seekBarHandler = Handler(Looper.getMainLooper())

    private lateinit var elapsedTimeReceiver: BroadcastReceiver
    private lateinit var captureStoppedReceiver: BroadcastReceiver

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.capture_dialog_fragment, container, false)

        savedSettings = SavedSettings(requireContext())

        // Retrieve the captureIntentData from the arguments bundle
        captureIntent = arguments?.getParcelable("CAPTURE_INTENT_DATA", Intent::class.java)

        val stopRecordingButton: Button = view.findViewById(R.id.btnCapStopRecording)
        val startRecordingButton: Button = view.findViewById(R.id.btnCapStartRecording)

        startRecordingButton.setOnClickListener {
            // make the dialog non-cancelable while recording
            dialog?.setCancelable(false)

            // enable stop button now that we started recording, and disable start button
            startRecordingButton.isEnabled = false
            startRecordingButton.alpha = 0.5f

            stopRecordingButton.isEnabled = true
            stopRecordingButton.alpha = 1.0f

            startRecording()
        }

        stopRecordingButton.setOnClickListener {
            // enable start button now that we stopped recording, and disable stop button
            startRecordingButton.isEnabled = true
            startRecordingButton.alpha = 1.0f

            stopRecordingButton.isEnabled = false
            stopRecordingButton.alpha = 0.5f

            stopRecording()

            dialog?.setCancelable(true)
        }

        val playRecordingButton: Button = view.findViewById(R.id.btnCapPlayRecording)
        playRecordingButton.setOnClickListener {
            startPlayback()
        }

        val stopRecordingPlaybackButton: Button = view.findViewById(R.id.btnCapStopPlayback)
        stopRecordingPlaybackButton.setOnClickListener {
            stopPlayback()
        }

        finalRecordingFilePath = context?.externalCacheDir?.absolutePath + "/capture.wav"
        currentRecordingFilePath = context?.externalCacheDir?.absolutePath + "/current_capture.wav"
        recordingStatusTextView = view.findViewById(R.id.tvCapRecordingStatus)

        if (savedSettings.lastCaptureStatus != null) {
            recordingStatusTextView.text = savedSettings.lastCaptureStatus
        }

        val saveRecordingButton: Button = view.findViewById(R.id.btnCapSaveRecording)
        saveRecordingButton.setOnClickListener {
            // copy currentRecordingFilePath to finalRecordingFilePath
            val currentFile = java.io.File(currentRecordingFilePath)
            val finalFile = java.io.File(finalRecordingFilePath)
            currentFile.copyTo(finalFile, true)

            recordingStatusTextView.text = "Latest capture saved"

            useAsInput.isEnabled = true
            useAsInput.alpha = 1.0f
        }

        useAsInput = view.findViewById(R.id.btnCapUseAsInput)
        useAsInput.setOnClickListener {
            sharedViewModel.useCaptureAsInput.value = true
            dialog?.dismiss()
        }

        recordingSeekBar = view.findViewById(R.id.seekBarPlayback)
        recordingSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    mediaPlayer?.seekTo(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // Optional: Implement if needed
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Optional: Implement if needed
            }
        })

        recordingCurrentDuration = view.findViewById(R.id.tvCapRecordDuration)

        recordingImg = view.findViewById(R.id.ivCapRecording)
        recordingImg.visibility = View.INVISIBLE

        val relaunchAlertLayout: View = view.findViewById(R.id.layoutRelaunchAlert)

        captureStoppedReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                recordingImg.visibility = View.INVISIBLE
                // Handle the recorded audio file as needed

                val elapsedTime = intent?.getIntExtra("EXTRA_ELAPSED", 0) ?: 0
                val formattedTime = String.format("%02d:%02d:%02d", elapsedTime / 3600, (elapsedTime % 3600) / 60, elapsedTime % 60)
                val dateTimeFormatter = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                val currentTime = dateTimeFormatter.format(System.currentTimeMillis())

                recordingStatusTextView.text = "Last capture: $formattedTime s captured at $currentTime"
                savedSettings.lastCaptureStatus = recordingStatusTextView.text.toString()

                // disable the startRecording button
                startRecordingButton.isEnabled = false
                startRecordingButton.alpha = 0.5f

                relaunchAlertLayout.visibility = View.VISIBLE
            }
        }

        // Register BroadcastReceiver to listen for the stop action
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            captureStoppedReceiver,
            IntentFilter("ACTION_CAPTURE_JOB_STOPPED")
        )

        elapsedTimeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // Extract the elapsed time from the intent
                val elapsedTime = intent?.getIntExtra("EXTRA_ELAPSED", 0) ?: 0
                // Update your UI with the new elapsed time
                val formattedTime = String.format("%02d:%02d:%02d", elapsedTime / 3600, (elapsedTime % 3600) / 60, elapsedTime % 60)
                recordingCurrentDuration.text = formattedTime
            }
        }

        // Register the receiver to listen for ACTION_CAPTURE_ELAPSED_TIME
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            elapsedTimeReceiver, IntentFilter("ACTION_CAPTURE_ELAPSED_TIME")
        )

        return view
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun startRecording() {
        recordingImg.visibility = View.VISIBLE
        recordingStatusTextView.text = "Started capture..."

        // get android current sample rate
        val audioManager = context?.getSystemService(AudioManager::class.java)
        val sampleRateString = audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        val sampleRate = Integer.parseInt(sampleRateString!!)

        // Initialize CaptureForegroundService
        val context = requireContext()
        val serviceIntent = Intent(context, CaptureForegroundService::class.java).apply {
            putExtra("sampleRate", sampleRate)
            putExtra("writePath", currentRecordingFilePath)
            putExtra("captureIntent", captureIntent) // Pass the captureIntent dat
        }
        context.startForegroundService(serviceIntent)
    }

    private fun stopRecording() {
        val context = requireContext()
        val serviceIntent = Intent(context, CaptureForegroundService::class.java)
        context.stopService(serviceIntent)
        recordingStatusTextView.text = "Stopping capture..."
    }

    private fun startPlayback() {
        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(currentRecordingFilePath)
                prepare()
                start()
            } catch (e: IOException) {
                Log.e("RecordDialogFragment", "prepare() failed")
            }
        }
        recordingSeekBar.max = mediaPlayer?.duration ?: 0
        updateSeekBarProgress()
    }

    private fun stopPlayback() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        seekBarHandler.removeCallbacks(updateSeekBarTask)
    }

    private val updateSeekBarTask = object : Runnable {
        override fun run() {
            mediaPlayer?.let {
                recordingSeekBar.progress = it.currentPosition
                seekBarHandler.postDelayed(this, 1000)
            }
        }
    }

    private fun updateSeekBarProgress() {
        seekBarHandler.postDelayed(updateSeekBarTask, 1000)
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(elapsedTimeReceiver)
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(captureStoppedReceiver)
    }
}
