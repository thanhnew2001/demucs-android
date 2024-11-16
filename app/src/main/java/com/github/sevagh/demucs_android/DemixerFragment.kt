package com.github.sevagh.demucs_android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.io.IOException
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.activityViewModels
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

const val CAPTURE_PERMISSION_MSG = "This feature requires permission to capture audio from other apps. Please enable it in the app settings."

enum class InputType {
    MIC, CAPTURE, FILE
}

class DemixerFragment : Fragment() {
    private lateinit var savedSettings: SavedSettings

    // demix job inputs
    private var selectedModel: String = "free-4s"
    private var modelFilePaths: Array<String> = arrayOf()
    var selectedFileUri: Uri? = null
    private lateinit var recordingFilePath: String
    private lateinit var captureFilePath: String
    private var stemsOutDir: String = ""
    private var outputStems: Array<String> = arrayOf()
    private var currentOutputType: InputType = InputType.FILE
    private var uploadFilePath: String = ""

    // shared data from dialog fragments
    // will store any of these:
    //   pro unlocked status
    private val sharedViewModel: SharedViewModel by activityViewModels()

    private lateinit var jobStoppedReceiver: BroadcastReceiver
    private lateinit var jobCompletedReceiver: BroadcastReceiver

    private val requestRecordPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                launchRecordControls()
            } else {
                handlePermissionDenied("RECORD_AUDIO")
            }
        }

    private val requestCapturePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                launchCaptureControls(result.data!!)
            } else {
                handlePermissionDenied("MEDIA_PROJECTION")
            }
        }

    private lateinit var mediaProjectionManager: MediaProjectionManager

    private val getFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { selectedUri ->
            selectedFileUri = selectedUri
            getView()?.findViewById<TextView>(R.id.tvSelectedInput)?.text = "File: " + getFileNameFromUri(requireContext(), selectedUri)
            currentOutputType = InputType.FILE
        }
    }

    private val openDocumentTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let {
            // Handle copying of files to the selected directory here
            copyStemFilesToSelectedDirectory(uri)
        }
    }

    private fun copyStemFilesToSelectedDirectory(directoryUri: Uri) {
        // Implementation to copy files to the user-selected directory.
        // This could involve using a DocumentFile to create new documents in the selected directory and copying your stem file contents into them.
        val context = requireContext() // Or getContext(), depending on where you are calling from
        val contentResolver = context.contentResolver

        // Convert the Uri to a DocumentFile
        val directory = DocumentFile.fromTreeUri(context, directoryUri)

        // Iterate over each stem file path
        outputStems.forEach { stemFilePath ->
            val stemFile = File(stemFilePath)
            val stemFileName = stemFile.name

            try {
                // Create a new file within the selected directory
                val newFile = directory?.createFile("audio/wav", stemFileName)

                newFile?.uri?.let { newFileUri ->
                    // Open an OutputStream to write into the new file
                    contentResolver.openOutputStream(newFileUri)?.use { outputStream ->
                        // Open a FileInputStream for the original stem file
                        FileInputStream(stemFile).use { inputStream ->
                            // Copy the contents
                            inputStream.copyTo(outputStream)
                        }
                    }

                    // Optionally, inform the user that the file was successfully copied
                }
            } catch (e: Exception) {
                Log.e("FileCopy", "Failed to copy $stemFileName", e)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.demixer_fragment, container, false)
        savedSettings = SavedSettings(requireContext())

        recordingFilePath = requireContext().externalCacheDir?.absolutePath + "/recording.opus"
        captureFilePath = requireContext().externalCacheDir?.absolutePath + "/capture.wav"

        val tvStemOutputs = view.findViewById<TextView>(R.id.tvStemOutputs)
        val btnStemOutputs: Button = view.findViewById(R.id.btnStemOutputs)
        val btnCustomMix: Button = view.findViewById(R.id.btnCustomMix)

        // first, check if there are any saved stems
        // if so, enable the copy button
        if (savedSettings.lastStemsSet != null) {
            val lastStems = savedSettings.lastStemsSet!!.toTypedArray()
            outputStems = lastStems
            val lastStemsStatus = savedSettings.lastStemStatus ?: ""
            tvStemOutputs.text = lastStemsStatus

            btnStemOutputs.isEnabled = true
            btnStemOutputs.alpha = 1.0f
        }

        btnStemOutputs.setOnClickListener {
            // use ActivityResultContracts.OpenDocumentTree to let user copy
            // outputStems from their app-specific directory to a location of their choice
            // and then show a toast message to confirm the action
            openDocumentTree.launch(null)
        }

        btnCustomMix.setOnClickListener {
            activity?.supportFragmentManager?.let { it1 ->
                CustomMixDialogFragment().show(
                    it1,
                    "CustomMixDialog"
                )
            }
        }

        sharedViewModel.useMicAsInput.observe(viewLifecycleOwner) { useMicAsInput ->
            if (useMicAsInput) {
                // use externalCacheDir/recording.opus as the selectedFileUri
                getView()?.findViewById<TextView>(R.id.tvSelectedInput)?.text = "Using last mic recording"
                currentOutputType = InputType.MIC
            }
        }

        sharedViewModel.useCaptureAsInput.observe(viewLifecycleOwner) { useCaptureAsInput ->
            if (useCaptureAsInput) {
                // use externalCacheDir/recording.opus as the selectedFileUri
                getView()?.findViewById<TextView>(R.id.tvSelectedInput)?.text = "Using last screen capture"
                currentOutputType = InputType.CAPTURE
            }
        }

        val uploadButton: Button = view.findViewById(R.id.btnUploadAudio)
        uploadButton.setOnClickListener {
            getFile.launch("audio/*")
        }

        val recordButton: Button = view.findViewById(R.id.btnRecordControls)
        recordButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                launchRecordControls()
                // if record successful, will save path to sharedViewModel
                // and mention that in the chosenInputTextView
                // i.e. "Recording: 37s_2021-08-31_14-00-00.wav" (their last-saved...)
            } else {
                // If the permission is not granted, attempt to request it
                requestRecordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        mediaProjectionManager = requireActivity().getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val captureButton: Button = view.findViewById(R.id.btnAppCaptureControls)
        captureButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestRecordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
                requestCapturePermissionLauncher.launch(captureIntent)
            }
            // if capture dialog successful, will have saved path to sharedViewModel
            // mention that in the chosenInputTextView
            // i.e. "Capture: 37s_2021-08-31_14-00-00.wav" (their last-saved...)
        }

        val stopButton: Button = view.findViewById(R.id.btnStopJob)
        stopButton.isEnabled = false
        stopButton.alpha = 0.5f

        val startButton: Button = view.findViewById(R.id.btnStartJob)
        startButton.setOnClickListener {
            // Define a map for model identifiers to their respective file names
            val modelToFileNames = mapOf(
                "free-4s" to arrayOf("ggml-model-htdemucs-4s-f16.bin"),
            )

            if (currentOutputType == InputType.MIC) {
                uploadFilePath = recordingFilePath
            } else if (currentOutputType == InputType.CAPTURE) {
                uploadFilePath = captureFilePath
            } else if (currentOutputType == InputType.FILE) {
                uploadFilePath = selectedFileUri?.let { copyUriToFile(requireContext(), it) } ?: ""
            }

            if (!File(uploadFilePath).exists()) {
                Toast.makeText(requireContext(), "Please select an existing audio file to demix", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Use the selected model to get the corresponding file paths
            val modelFiles = modelToFileNames[selectedModel] ?: arrayOf()

            val modelWeightsDir = File(requireContext().getExternalFilesDir(null), "model_weights")

            // create the model weights directory if it doesn't exist
            if (!modelWeightsDir.exists()) {
                modelWeightsDir.mkdirs()
            }

            // get app external files dir for output files
            val stemsOutputDir = requireContext().getExternalFilesDir(Environment.DIRECTORY_MUSIC)!!

            // create the stems output directory if it doesn't exist
            if (!stemsOutputDir.exists()) {
                stemsOutputDir.mkdirs()
            }

            stemsOutDir = stemsOutputDir.absolutePath

            copyAssetToFilesystem(requireContext(), "ggml-model-htdemucs-4s-f16.bin", modelWeightsDir)

            // Assuming 'modelFiles' contains just the filenames of your models
            modelFilePaths = modelFiles.map { fileName ->
                File(modelWeightsDir, fileName).absolutePath
            }.toTypedArray()

            // Disable the startButton
            startButton.isEnabled = false
            startButton.alpha = 0.5f // Make button appear grayed out

            // Disable the share and save buttons
            btnStemOutputs.isEnabled = false
            btnStemOutputs.alpha = 0.5f

            // Enable the stopButton
            stopButton.isEnabled = true
            stopButton.alpha = 1.0f // Make button appear normal

            // Call NDK inference function with the correct model file paths
            startInferenceService(uploadFilePath)
        }

        stopButton.setOnClickListener {
            stopInference()
        }

        // Initialize BroadcastReceiver
        jobStoppedReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {

                // Re-enable the startButton
                startButton.isEnabled = true
                startButton.alpha = 1.0f

                // Disable the stopButton
                stopButton.isEnabled = false
                stopButton.alpha = 0.5f
            }
        }

        // Register BroadcastReceiver to listen for the stop action
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            jobStoppedReceiver,
            IntentFilter("ACTION_DEMIX_JOB_STOPPED")
        )

        jobCompletedReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // Extract the array of written stem paths
                val writtenStems = intent?.getStringArrayExtra("writtenStems")

                // remove the temporary uploadFilePath if the type is FILE
                // if it was a capture or recording, we want to keep it
                if (currentOutputType == InputType.FILE) {
                    File(uploadFilePath).delete()
                }

                if (writtenStems != null) {
                    // Update the UI with the written stem paths

                    // construct string of stem paths with current time
                    val dateTimeFormatter = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                    val currentTime = dateTimeFormatter.format(System.currentTimeMillis())
                    tvStemOutputs.text = "Last stems output at $currentTime"

                    outputStems = writtenStems

                    savedSettings.lastStemStatus = tvStemOutputs.text.toString()
                    savedSettings.lastStemsSet = writtenStems.toSet()

                    // Enable the copy button
                    btnStemOutputs.isEnabled = true
                    btnStemOutputs.alpha = 1.0f
                }
            }
        }

        // Register BroadcastReceiver to listen for the stop action
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            jobCompletedReceiver,
            IntentFilter("ACTION_DEMIX_JOB_COMPLETED")
        )

        val progressBar: ProgressBar = view.findViewById(R.id.progressBarJob)
        val tvTerminalLogs: TextView = view.findViewById(R.id.tvTerminalLogs)
        val scrollViewLogs: ScrollView = view.findViewById(R.id.scrollViewLogs)

        val updateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val progress = intent?.getFloatExtra("EXTRA_PROGRESS", 0f) ?: 0f

                // ignore negative progress values
                if (progress > -1.0f) {
                    progressBar.progress = (progress * progressBar.max).toInt()
                }

                val message = intent?.getStringExtra("EXTRA_MESSAGE") ?: ""
                // Update your UI here using progress and message
                tvTerminalLogs.append("$message\n")

                scrollViewLogs.post {
                    scrollViewLogs.scrollTo(0, tvTerminalLogs.height)
                }
            }
        }
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            updateReceiver,
            IntentFilter("ACTION_DEMIX_PROGRESS_UPDATE")
        )

        val clearLogsButton: Button = view.findViewById(R.id.btnClearLogs)
        clearLogsButton.setOnClickListener {
            tvTerminalLogs.text = ""
        }

        return view
    }

    private fun startInferenceService(inputFilePath: String) {
        val context = requireContext()
        val serviceIntent = Intent(context, DemucsAndroidForegroundService::class.java).apply {
            putExtra("audioFilePath", inputFilePath)
            putExtra("model", selectedModel)
            putExtra("modelFilePaths", modelFilePaths)
            putExtra("outDir", stemsOutDir)
        }
        context.startForegroundService(serviceIntent)
    }

    private fun stopInference() {
        val context = requireContext()
        val serviceIntent = Intent(context, DemucsAndroidForegroundService::class.java)
        context.stopService(serviceIntent)
    }

    private fun handlePermissionDenied(permissionType: String) {
        val rationaleMessage = when (permissionType) {
            "RECORD_AUDIO" -> "This feature requires microphone access to record audio. Please enable it in the app settings."
            "MEDIA_PROJECTION" -> CAPTURE_PERMISSION_MSG
            else -> "This feature requires additional permissions. Please enable them in the app settings."
        }

        showPermissionRationale(rationaleMessage)
    }

    private fun showPermissionRationale(rationaleMessage: String) {
        AlertDialog.Builder(requireContext())
            .setMessage(rationaleMessage)
            .setPositiveButton("App Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", requireActivity().packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun launchRecordControls() {
        activity?.supportFragmentManager?.let { it1 ->
            RecordDialogFragment().show(
                it1,
                "RecordingDialog"
            )
        }
    }

    private fun launchCaptureControls(captureIntentData: Intent) {
        val dialog = CaptureDialogFragment().apply {
            arguments = Bundle().apply {
                putParcelable("CAPTURE_INTENT_DATA", captureIntentData)
            }
        }
        activity?.supportFragmentManager?.let { fragmentManager ->
            dialog.show(fragmentManager, "CaptureDialog")
        }
    }

    companion object {
        fun newInstance() = DemixerFragment()
    }
}

fun copyAssetToFilesystem(context: Context, assetFileName: String, destDir: File) {
    val outputFile = File(destDir, assetFileName)
    if (!outputFile.exists()) {
        try {
            context.assets.open(assetFileName).use { inputStream ->
                try {
                    FileOutputStream(outputFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                } catch (e: IOException) {
                    Log.e("SEVAG-DEBUG", "Failed to open output stream: ${e.message}")
                }
            }
        } catch (e: IOException) {
            Log.e("SEVAG-DEBUG", "Failed to copy asset file: ${e.message}")
        }
    }
}

fun copyUriToFile(context: Context, selectedUri: Uri): String {
    var uploadFilePath = ""
    val fileName = getFileNameFromUri(context, selectedUri)

    if (fileName != null) {
        val destinationFile = File(context.externalCacheDir, fileName)

        try {
            context.contentResolver.openInputStream(selectedUri)?.use { inputStream ->
                FileOutputStream(destinationFile).use { fileOutputStream ->
                    inputStream.copyTo(fileOutputStream)
                }
            }
            uploadFilePath = destinationFile.absolutePath
        } catch (e: IOException) {
            e.printStackTrace()
        }
    } else {
        Log.e("UploadError", "Could not determine the file name for the URI: $selectedUri")
    }

    return uploadFilePath
}

fun getFileNameFromUri(context: Context, uri: Uri): String? {
    var fileName: String? = null
    if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
        val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
        cursor?.let {
            if (it.moveToFirst()) {
                val columnIndex = it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                fileName = it.getString(columnIndex)
            }
            it.close()
        }
    }
    // If the Uri scheme is not "content" (e.g., "file"), then extract the last segment from the Uri path.
    if (fileName == null) {
        fileName = uri.lastPathSegment
    }
    return fileName
}

class CustomArrayAdapter(
    context: Context,
    resource: Int,
    objects: Array<String>,
    private val lockedIndices: List<Int>
) : ArrayAdapter<String>(context, resource, objects) {

    override fun isEnabled(position: Int): Boolean {
        // Return false for positions you want to disable
        return !lockedIndices.contains(position)
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getDropDownView(position, convertView, parent)
        // Grey out the text for disabled items
        if (!isEnabled(position)) {
            view.alpha = 0.5f // set transparency for greyed out look
        } else {
            view.alpha = 1.0f
        }
        return view
    }
}
