package com.github.sevagh.demucs_android

import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.PlaybackParams
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import java.io.IOException

class RecordDialogFragment : DialogFragment() {
    private val sharedViewModel: SharedViewModel by activityViewModels()
    private lateinit var savedSettings: SavedSettings

    private lateinit var recordingStatusTextView: TextView
    private lateinit var recordingCurrentDuration: TextView
    private lateinit var recordingImg: ImageView
    private lateinit var recordingSeekBar: SeekBar
    private lateinit var useAsInput: Button

    private var isRecording = false
    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var finalRecordingFilePath: String? = null
    private var currentRecordingFilePath: String? = null

    private val elapsedTimeHandler = Handler(Looper.getMainLooper())
    private var elapsedTime = 0
    private val seekBarHandler = Handler(Looper.getMainLooper())

    private val updateRecordingTime = object : Runnable {
        override fun run() {
            elapsedTime++ // Increment time
            val formattedTime = String.format("%02d:%02d:%02d", elapsedTime / 3600, (elapsedTime % 3600) / 60, elapsedTime % 60)
            recordingCurrentDuration.text = formattedTime
            elapsedTimeHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.recording_dialog_fragment, container, false)

        savedSettings = SavedSettings(requireContext())

        val startRecordingButton: Button = view.findViewById(R.id.btnMicStartRecording)
        val stopRecordingButton: Button = view.findViewById(R.id.btnMicStopRecording)

        startRecordingButton.setOnClickListener {
            dialog?.setCancelable(false)

            startRecordingButton.isEnabled = false
            startRecordingButton.alpha = 0.5f

            stopRecordingButton.isEnabled = true
            stopRecordingButton.alpha = 1.0f

            startRecording()
        }

        stopRecordingButton.setOnClickListener {
            startRecordingButton.isEnabled = true
            startRecordingButton.alpha = 1.0f

            stopRecordingButton.isEnabled = false
            stopRecordingButton.alpha = 0.5f

            stopRecording()
            dialog?.setCancelable(true)
        }

        val playRecordingButton: Button = view.findViewById(R.id.btnMicPlayRecording)
        playRecordingButton.setOnClickListener {
            startPlayback()
        }

        val stopRecordingPlaybackButton: Button = view.findViewById(R.id.btnMicStopPlayback)
        stopRecordingPlaybackButton.setOnClickListener {
            stopPlayback()
        }

        finalRecordingFilePath = context?.externalCacheDir?.absolutePath + "/recording.opus"
        currentRecordingFilePath = context?.externalCacheDir?.absolutePath + "/current_recording.opus"
        recordingStatusTextView = view.findViewById(R.id.tvRecordingStatus)

        if (savedSettings.lastRecordingStatus != null) {
            recordingStatusTextView.text = savedSettings.lastRecordingStatus
        }

        val saveRecordingButton: Button = view.findViewById(R.id.btnMicSaveRecording)
        saveRecordingButton.setOnClickListener {
            // copy currentRecordingFilePath to finalRecordingFilePath
            val currentFile = java.io.File(currentRecordingFilePath)
            val finalFile = java.io.File(finalRecordingFilePath)
            currentFile.copyTo(finalFile, true)
            recordingStatusTextView.text = "Latest recording saved"

            useAsInput.isEnabled = true
            useAsInput.alpha = 1.0f
        }

        useAsInput = view.findViewById(R.id.btnMicUseAsInput)
        useAsInput.setOnClickListener {
            sharedViewModel.useMicAsInput.value = true
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

        recordingCurrentDuration = view.findViewById(R.id.tvRecordDuration)

        recordingImg = view.findViewById(R.id.ivMicRecording)
        recordingImg.visibility = View.INVISIBLE

        return view
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun startRecording() {
        recordingImg.visibility = View.VISIBLE
        isRecording = true
        elapsedTime = 0
        elapsedTimeHandler.postDelayed(updateRecordingTime, 1000) // Start the timer
        recordingStatusTextView.text = "Started recording..."
        // Initialize MediaRecorder and start recording

        // get android current sample rate
        val audioManager = context?.getSystemService(AudioManager::class.java)
        val sampleRateString = audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        val sampleRate = Integer.parseInt(sampleRateString!!)

        mediaRecorder = context?.let {
            MediaRecorder(it).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.OGG)
                setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(sampleRate)
                setOutputFile(currentRecordingFilePath)
                try {
                    prepare()
                    start()
                } catch (e: IOException) {
                    // Handle exceptions
                }
            }
        }
    }

    private fun stopRecording() {
        isRecording = false
        recordingImg.visibility = View.INVISIBLE
        // Stop and release MediaRecorder
        mediaRecorder?.apply {
            stop()
            release()
        }
        mediaRecorder = null
        // Handle the recorded audio file as needed
        elapsedTimeHandler.removeCallbacks(updateRecordingTime) // Stop the timer

        val formattedTime = String.format("%02d:%02d:%02d", elapsedTime / 3600, (elapsedTime % 3600) / 60, elapsedTime % 60)
        val dateTimeFormatter = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        val currentTime = dateTimeFormatter.format(System.currentTimeMillis())

        recordingStatusTextView.text = "Last recording: $formattedTime s recorded at $currentTime"
        savedSettings.lastRecordingStatus = recordingStatusTextView.text.toString()
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
        mediaRecorder?.release()
        mediaRecorder = null
    }
}
