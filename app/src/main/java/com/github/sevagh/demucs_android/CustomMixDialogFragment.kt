package com.github.sevagh.demucs_android

import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.DialogFragment
import java.io.File
import java.io.FileInputStream
import java.io.IOException

class CustomMixDialogFragment : DialogFragment() {
    private lateinit var savedSettings: SavedSettings
    private lateinit var playbackSeekBar: SeekBar

    private var outputStems: Array<String> = arrayOf()
    private var mediaPlayer: MediaPlayer? = null

    private val seekBarHandler = Handler(Looper.getMainLooper())

    private var currentMixFilePath = ""

    private val openDocumentTreeForMix = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let {
            // Handle saving of the current mix file to the selected directory
            saveCurrentMixToFile(uri)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.custom_mix_dialog_fragment, container, false)

        savedSettings = SavedSettings(requireContext())

        // first, check if there are any saved stems
        // if so, enable the copy button
        if (savedSettings.lastStemsSet != null) {
            val lastStems = savedSettings.lastStemsSet!!.toTypedArray()
            outputStems = lastStems
        }

        val tvStemStatus: TextView = view.findViewById(R.id.tvStemStatus)

        val btnCreateMix: Button = view.findViewById(R.id.btnCreateMix)
        val btnSaveMix: Button = view.findViewById(R.id.btnSaveMix)
        val tvMixStatus: TextView = view.findViewById(R.id.tvMixStatus)

        val btnStemStartPlayback: Button = view.findViewById(R.id.btnStemStartPlayback)
        val btnStemStopPlayback: Button = view.findViewById(R.id.btnStemStopPlayback)

        playbackSeekBar = view.findViewById(R.id.seekBarPlayback)
        val chkLoop: CheckBox = view.findViewById(R.id.chkLoop)
        val checkboxContainer: LinearLayout = view.findViewById(R.id.checkboxContainer)

        // first, check if there are any saved stems
        // if not, show a message and disable buttons
        outputStems = savedSettings.lastStemsSet?.toTypedArray() ?: arrayOf()
        if (outputStems.isEmpty()) {
            tvStemStatus.text = "No output stems available"
        } else {
            tvStemStatus.text = "Using last output stems"
            outputStems.forEach { stemFilePath ->
                val checkBox = CheckBox(context)
                checkBox.text = File(stemFilePath).name
                checkBox.isChecked = true  // Or restore saved state
                checkboxContainer.addView(checkBox)
            }

            // enable all buttons
            btnCreateMix.isEnabled = true

            // alpha/opacity to 1
            btnCreateMix.alpha = 1f
        }

        val outputDir = requireContext().getExternalFilesDir(Environment.DIRECTORY_MUSIC)!!.absolutePath
        val outputFile = File(outputDir, "custom_mix.wav")
        currentMixFilePath = outputFile.path

        // first, check if there are any pre-generated mixes
        if (savedSettings.lastMixStatus != null) {
            val lastMixStatus = savedSettings.lastMixStatus ?: ""
            tvMixStatus.text = lastMixStatus

            btnSaveMix.isEnabled = true
            btnStemStartPlayback.isEnabled = true
            btnStemStopPlayback.isEnabled = true
            chkLoop.isEnabled = true
            btnSaveMix.alpha = 1f
            btnStemStartPlayback.alpha = 1f
            btnStemStopPlayback.alpha = 1f
            chkLoop.alpha = 1f
        }

        // create and save mix to file
        btnCreateMix.setOnClickListener {
            val selectedStems = getSelectedStems()
            if (selectedStems.isNotEmpty()) {
                try {
                    // ndk function
                    //mixStemsToWav(selectedStems, outputFile.path)
                    val selectedStems = getSelectedStems() // Array of file paths

                    // create array joined to outDir of selectedStems
                    val selectedStemPaths = selectedStems.map { File(outputDir, it).path }.toTypedArray()

                    Log.d("STEMS", "selected stems: ${selectedStems.joinToString(", ")}")
                    val successfulWrite = createCustomMix(selectedStemPaths, outputFile.path)

                    if (successfulWrite != 0) {
                        // make a toast
                        Toast.makeText(context, "Failed to create mix", Toast.LENGTH_SHORT).show()
                    } else {
                        Log.d("STEMS", "wrote mix to ${outputFile.path}")

                        // construct string of stem paths with current time
                        val dateTimeFormatter = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                        val currentTime = dateTimeFormatter.format(System.currentTimeMillis())
                        tvMixStatus.text = "Mix created at $currentTime"

                        savedSettings.lastMixStatus = tvMixStatus.text.toString()
                        Toast.makeText(context, "Mix created successfully!", Toast.LENGTH_SHORT).show()

                        btnSaveMix.isEnabled = true
                        btnStemStartPlayback.isEnabled = true
                        btnStemStopPlayback.isEnabled = true
                        chkLoop.isEnabled = true
                        btnSaveMix.alpha = 1f
                        btnStemStartPlayback.alpha = 1f
                        btnStemStopPlayback.alpha = 1f
                        chkLoop.alpha = 1f
                    }
                } catch (e: Exception) {
                    Log.e("CustomMixDialogFragment", "Mixing failed", e)
                    Toast.makeText(context, "Failed to create mix", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "No stems selected for mixing", Toast.LENGTH_SHORT).show()
            }
        }

        btnSaveMix.setOnClickListener {
            // save mix to device storage
            // use already-generated currentMixFilePath
            openDocumentTreeForMix.launch(null)  // You might want to specify an initial Uri
        }

        // playback controls
        btnStemStartPlayback.setOnClickListener {
            startPlayback()
        }

        btnStemStopPlayback.setOnClickListener {
            stopPlayback()
        }

        chkLoop.isChecked = false // Reset checkbox to unchecked on fragment view creation
        chkLoop.setOnCheckedChangeListener { _, isChecked ->
            mediaPlayer?.isLooping = isChecked
        }

        playbackSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
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

        return view
    }

    private fun getSelectedStems(): List<String> {
        val selectedStems = mutableListOf<String>()
        val checkboxContainer = view?.findViewById<LinearLayout>(R.id.checkboxContainer)
        if (checkboxContainer != null) {
            // Loop through all the children of the checkboxContainer
            for (i in 0 until checkboxContainer.childCount) {
                val child = checkboxContainer.getChildAt(i)
                // Check if the child is an instance of CheckBox
                if (child is CheckBox && child.isChecked) {
                    selectedStems.add(child.text.toString())
                }
            }
        }
        return selectedStems
    }

    private fun saveCurrentMixToFile(directoryUri: Uri) {
        val context = requireContext()
        val contentResolver = context.contentResolver

        // Retrieve the URI for the directory
        val directory = DocumentFile.fromTreeUri(context, directoryUri)
        val mixFile = File(currentMixFilePath)
        val mixFileName = mixFile.name

        try {
            // Create a new file in the selected directory
            val newFile = directory?.createFile("audio/wav", mixFileName)

            newFile?.uri?.let { newFileUri ->
                // Open an OutputStream to write into the new file
                contentResolver.openOutputStream(newFileUri)?.use { outputStream ->
                    // Open a FileInputStream for the current mix file
                    FileInputStream(mixFile).use { inputStream ->
                        // Copy the contents
                        inputStream.copyTo(outputStream)
                    }
                }

                // Optionally, show a toast message indicating success
                Toast.makeText(context, "File saved successfully: $mixFileName", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e("FileSave", "Failed to save $mixFileName", e)
            Toast.makeText(context, "Failed to save file: $mixFileName", Toast.LENGTH_LONG).show()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun startPlayback() {
        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(currentMixFilePath)
                prepare()

                // Set looping based on the checkbox state
                val loopCheckbox: CheckBox? = view?.findViewById(R.id.chkLoop)
                isLooping = loopCheckbox?.isChecked ?: false

                start()
            } catch (e: IOException) {
                Log.e("RecordDialogFragment", "prepare() failed")
            }
        }
        playbackSeekBar.max = mediaPlayer?.duration ?: 0
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
                playbackSeekBar.progress = it.currentPosition
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
    }

    companion object {
        init {
            System.loadLibrary("demucs_ndk")
        }
    }

    private external fun createCustomMix(stemFilePaths: Array<String>, outPath: String): Int}
