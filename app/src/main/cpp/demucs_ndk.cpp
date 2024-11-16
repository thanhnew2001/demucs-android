#include "demucs/dsp.hpp"
#include "demucs/model.hpp"
#include "demucs/tensor.hpp"
#include <Eigen/Core>
#include <Eigen/Dense>
#include <filesystem>
#include <iomanip>
#include <iostream>
#include <libnyquist/Common.h>
#include <libnyquist/Decoders.h>
#include <libnyquist/Encoders.h>
#include <sstream>
#include <string>
#include <unsupported/Eigen/FFT>
#include <vector>
#include <jni.h>
#include <fstream>
#include <android/log.h>
#include <chrono>
#include <sstream>
#include "resampler/MultiChannelResampler.h"

using namespace demucscpp;
using namespace nqr;

// Example definition in your NDK code
volatile bool shouldStop = false;

struct AudioLoadResult {
    Eigen::MatrixXf audioData;
    int originalSampleRate;
    std::string originalFileExtension;
};

AudioLoadResult load_audio_file(std::string filename) {
    AudioLoadResult result;

    // Load the audio file with libnyquist
    std::shared_ptr<AudioData> fileData = std::make_shared<AudioData>();
    NyquistIO loader;
    loader.Load(fileData.get(), filename);

    // Extract the file extension
    size_t dotPos = filename.find_last_of(".");
    result.originalFileExtension = (dotPos != std::string::npos) ? filename.substr(dotPos) : "";

    // Set the original sample rate
    result.originalSampleRate = fileData->sampleRate;

    // Handle the audio data based on the number of channels and sample rate
    size_t N = fileData->samples.size() / fileData->channelCount;
    Eigen::MatrixXf loadedAudio = Eigen::MatrixXf::Zero(2, N); // Always work in stereo for simplicity

    std::cout << "Input samples: " << N << std::endl;
    std::cout << "Length in seconds: " << fileData->lengthSeconds << std::endl;
    std::cout << "Number of channels: " << fileData->channelCount << std::endl;

    if (fileData->channelCount != 2 && fileData->channelCount != 1)
    {
        std::cerr << "[ERROR] demucs.cpp only supports mono and stereo audio"
                  << std::endl;
        return result;
    }

    if (fileData->channelCount == 1)
    {
        // Mono case
        for (size_t i = 0; i < N; ++i)
        {
            loadedAudio(0, i) = fileData->samples[i]; // left channel
            loadedAudio(1, i) = fileData->samples[i]; // right channel
        }
    }
    else
    {
        // Stereo case
        for (size_t i = 0; i < N; ++i)
        {
            loadedAudio(0, i) = fileData->samples[2 * i];     // left channel
            loadedAudio(1, i) = fileData->samples[2 * i + 1]; // right channel
        }
    }

    // Check if resampling is needed
    if (fileData->sampleRate != SUPPORTED_SAMPLE_RATE) {
        std::cout << "Resampling from " << fileData->sampleRate << " to " << SUPPORTED_SAMPLE_RATE << std::endl;

        oboe::resampler::MultiChannelResampler* resampler = oboe::resampler::MultiChannelResampler::make(
                fileData->channelCount,
                fileData->sampleRate,
                SUPPORTED_SAMPLE_RATE,
                oboe::resampler::MultiChannelResampler::Quality::Best);

        // Calculate the required output buffer size
        int numOutputFrames = N * SUPPORTED_SAMPLE_RATE / fileData->sampleRate + 1;
        Eigen::MatrixXf resampledAudio = Eigen::MatrixXf::Zero(2, numOutputFrames);

        float* inputBuffer = loadedAudio.data();
        float* outputBuffer = resampledAudio.data();
        int numInputFrames = N;
        int inputFramesLeft = numInputFrames;
        int numResampledFrames = 0;

        while (inputFramesLeft > 0) {
            if (resampler->isWriteNeeded()) {
                resampler->writeNextFrame(inputBuffer);
                inputBuffer += fileData->channelCount;
                inputFramesLeft--;
            } else {
                resampler->readNextFrame(outputBuffer);
                outputBuffer += fileData->channelCount;
                numResampledFrames++;
            }
        }

        // Update the result with the resampled audio
        result.audioData = resampledAudio.leftCols(numResampledFrames);

        delete resampler;
    } else {
        // No resampling needed
        result.audioData = loadedAudio;
    }

    return result;
}

// write a function to write a StereoWaveform to a wav file
static void write_audio_file(const Eigen::MatrixXf &waveform,
                             std::string filename)
{
    // create a struct to hold the audio data
    std::shared_ptr<AudioData> fileData = std::make_shared<AudioData>();

    // set the sample rate
    fileData->sampleRate = SUPPORTED_SAMPLE_RATE;

    // set the number of channels
    fileData->channelCount = 2;

    // set the number of samples
    fileData->samples.resize(waveform.cols() * 2);

    // write the left channel
    for (long int i = 0; i < waveform.cols(); ++i)
    {
        fileData->samples[2 * i] = waveform(0, i);
        fileData->samples[2 * i + 1] = waveform(1, i);
    }

    int encoderStatus =
            encode_wav_to_disk({fileData->channelCount, PCM_FLT, DITHER_TRIANGLE},
                               fileData.get(), filename);
    std::cout << "Encoder Status: " << encoderStatus << std::endl;
}

extern "C" JNIEXPORT void JNICALL
Java_com_github_sevagh_demucs_1android_CaptureForegroundService_writeAudioFile(
        JNIEnv* env,
        jobject /* this */,
        jstring tempFilePath,
        jint sampleRate,
        jstring filePath) {
    const char* nativeTempFilePath = env->GetStringUTFChars(tempFilePath, JNI_FALSE);
    const char* nativeFilePath = env->GetStringUTFChars(filePath, JNI_FALSE);

    __android_log_print(ANDROID_LOG_INFO, "WRITE FILE", "Starting to process temp file: %s", nativeTempFilePath);

    std::ifstream tempFile(nativeTempFilePath, std::ios::binary);
    if (!tempFile.is_open()) {
        __android_log_print(ANDROID_LOG_ERROR, "WRITE FILE", "Failed to open temp file: %s", nativeTempFilePath);
        env->ReleaseStringUTFChars(tempFilePath, nativeTempFilePath);
        env->ReleaseStringUTFChars(filePath, nativeFilePath);
        return;
    }

    std::string audioFilePathStr(nativeFilePath);
    env->ReleaseStringUTFChars(filePath, nativeFilePath);

    // Create a struct to hold the audio data
    std::shared_ptr<AudioData> fileData = std::make_shared<AudioData>();
    fileData->sampleRate = sampleRate;
    fileData->channelCount = 2;

    // Read and normalize data from temp file
    std::vector<short> buffer(4096);
    float maxAbs = 0.0f;

    // First pass: Find the maximum absolute value
    while (tempFile.read(reinterpret_cast<char*>(buffer.data()), buffer.size() * sizeof(short))) {
        std::streamsize bytesRead = tempFile.gcount();
        for (int i = 0; i < bytesRead / sizeof(short); ++i) {
            float absValue = std::abs(static_cast<float>(buffer[i]));
            if (absValue > maxAbs) {
                maxAbs = absValue;
            }
        }
    }
    if (tempFile.fail() && !tempFile.eof()) {
        __android_log_print(ANDROID_LOG_ERROR, "WRITE FILE", "Error reading temp file during first pass");
        tempFile.close();
        env->ReleaseStringUTFChars(tempFilePath, nativeTempFilePath);
        return;
    }

    // Clear the EOF and other state flags to allow further I/O operations
    tempFile.clear();
    // Seek to the beginning of the file for the second pass
    tempFile.seekg(0, std::ios::beg);

    if (maxAbs == 0) {
        maxAbs = std::numeric_limits<float>::max();
    }

    // Second pass: Normalize and write the samples
    while (tempFile.read(reinterpret_cast<char*>(buffer.data()), buffer.size() * sizeof(short))) {
        std::streamsize bytesRead = tempFile.gcount();
        for (int i = 0; i < bytesRead / sizeof(short); ++i) {
            float normalizedValue = static_cast<float>(buffer[i]) / maxAbs;
            fileData->samples.push_back(normalizedValue);
            fileData->samples.push_back(normalizedValue);
        }
    }

    tempFile.close();
    env->ReleaseStringUTFChars(tempFilePath, nativeTempFilePath);

    int encoderStatus =
            encode_wav_to_disk({fileData->channelCount, PCM_FLT, DITHER_TRIANGLE},
                               fileData.get(), audioFilePathStr);
}

void updateInferenceProgress(JNIEnv *env, jobject thiz, const std::string &msg, float progress = -1) {
    __android_log_print(ANDROID_LOG_INFO, "Demucs.cpp", "%s", msg.c_str());

    auto now = std::chrono::system_clock::now();
    std::time_t now_time = std::chrono::system_clock::to_time_t(now);
    std::stringstream ss;
    ss << "[" << std::put_time(std::localtime(&now_time), "%H:%M:%S") << "] " << msg;

    std::string timestampedMsg = ss.str();
    jstring jMsg = env->NewStringUTF(timestampedMsg.c_str());

    jclass clazz = env->GetObjectClass(thiz);
    jmethodID methodId = env->GetMethodID(clazz, "inferenceProgressUpdate", "(FLjava/lang/String;)V");
    env->CallVoidMethod(thiz, methodId, progress, jMsg);
    env->DeleteLocalRef(jMsg);
}

class AndroidLogBuf : public std::streambuf {
public:
    AndroidLogBuf(JNIEnv* env, jobject thiz) : env_(env), thiz_(thiz) {}

protected:
    virtual int_type overflow(int_type c) override {
        if (c != traits_type::eof()) {
            if (c == '\n') {
                if (!buf_.empty()) { // Only log if buffer is not empty
                    updateInferenceProgress(env_, thiz_, buf_, -1); // Assuming -1 indicates logging
                    buf_.clear();
                }
            } else {
                buf_ += static_cast<char>(c);
            }
        }
        return c;
    }

private:
    JNIEnv* env_;
    jobject thiz_;
    std::string buf_;
};

class StopOperationException : public std::exception {
public:
    const char* what() const noexcept override {
        return "Stop operation requested";
    }
};

extern "C"
JNIEXPORT void JNICALL
Java_com_github_sevagh_demucs_1android_DemucsAndroidForegroundService_stopInference(JNIEnv *env, jobject thiz) {
shouldStop = true;
}

extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_github_sevagh_demucs_1android_DemucsAndroidForegroundService_demucsInference(JNIEnv *env, jobject thiz,
                                                              jstring jAudioFilePath,
                                                              jstring jModelName,
                                                              jobjectArray jModelFilePaths,
                                                              jstring jOutDir) {
    std::vector<std::string> written_paths;

    AndroidLogBuf coutLogBuf(env, thiz);
    AndroidLogBuf cerrLogBuf(env, thiz);

    std::cout.rdbuf(&coutLogBuf);
    std::cerr.rdbuf(&cerrLogBuf);

    // Convert jstring to std::string (for audioFilePath and modelName)
    const char *audioFilePath = env->GetStringUTFChars(jAudioFilePath, nullptr);
    std::string audioFilePathStr(audioFilePath);
    env->ReleaseStringUTFChars(jAudioFilePath, audioFilePath);

    const char *modelName = env->GetStringUTFChars(jModelName, nullptr);
    std::string modelNameStr(modelName);
    env->ReleaseStringUTFChars(jModelName, modelName);

    // convert jstring for jOutDir to std::string
    const char *outDir = env->GetStringUTFChars(jOutDir, nullptr);
    std::string out_dir(outDir);
    env->ReleaseStringUTFChars(jOutDir, outDir);

    // Handle the array of model file paths
    jsize modelFilePathsLength = env->GetArrayLength(jModelFilePaths);
    std::vector<std::string> modelFilePathsStr;
    for (jsize i = 0; i < modelFilePathsLength; i++) {
        jstring jstr = (jstring) env->GetObjectArrayElement(jModelFilePaths, i);
        const char *modelFilePath = env->GetStringUTFChars(jstr, nullptr);
        modelFilePathsStr.push_back(std::string(modelFilePath));
        env->ReleaseStringUTFChars(jstr, modelFilePath);
    }

    // on new inference, reset the stop flag
    shouldStop = false;

    std::stringstream ss;
    ss << "Resetting inference for new job...";

    // reset progress bar to 0
    updateInferenceProgress(env, thiz, ss.str(), 0.0f);

    ss.str("");

    ss << "Loading audio file: " << audioFilePathStr;
    updateInferenceProgress(env, thiz, ss.str());

    ss.str("");

    AudioLoadResult audioLoadResult = load_audio_file(audioFilePathStr);
    Eigen::MatrixXf audio = audioLoadResult.audioData;
    if (audio.size() == 0) {
        std::cerr << "Error when loading audio file" << std::endl;
        // delete the audio file
        // using C++
        return nullptr;
    }

    // load model files into std::vector<char>
    std::vector<std::vector<char>> models_data;
    for (const auto &model_file: modelFilePathsStr) {
        std::cout << "Loading Demucs model weights file: " << model_file
                  << std::endl;
        std::ifstream file(model_file, std::ios::binary);
        if (!file) {
            std::cerr << "Error: could not open model file " << model_file
                      << std::endl;
            return nullptr;
        }
        std::vector<char> model_data((std::istreambuf_iterator<char>(file)),
                                     std::istreambuf_iterator<char>());
        models_data.push_back(model_data);
    }

    std::vector<demucs_model> models; // No need to pre-size the vector

    for (const auto &model_data: models_data) {
        demucs_model model{};
        auto ret = load_demucs_model(model_data, &model);
        std::cout << "demucs_model_load returned " << (ret ? "true" : "false")
                  << std::endl;
        if (!ret) {
            std::cerr << "Error loading model" << std::endl;
            return nullptr;
        }
        models.emplace_back(std::move(model));
    }

    // hardcode for 4-source model only
    // free models
    int nb_sources = models[0].num_sources;

    std::cout << "Starting Demucs (" << std::to_string(nb_sources)
              << "-source) inference" << std::endl;

    demucscpp::ProgressCallback cb =
            [env, thiz](float progress, std::string msg) {
                if (shouldStop) {
                    msg = "User stopped job...";
                    progress = 0.0f;
                }

                updateInferenceProgress(env, thiz, msg, progress);

                if (shouldStop) {
                    throw StopOperationException();
                }
            };

    // create 4 audio matrix same size, to hold output
    Eigen::Tensor3dXf audio_targets;

    try {
        audio_targets = demucscpp::demucs_inference(models[0], audio, cb);
    } catch (const StopOperationException &e) {
        std::cout << "Stop operation requested" << std::endl;
        return nullptr;
    }

    int nb_out_sources = models[0].num_sources;
    Eigen::MatrixXf instrum_waveform = Eigen::MatrixXf::Zero(2, audio.cols());

    std::filesystem::path p = out_dir;
    // make sure the directory exists
    std::filesystem::create_directories(p);

    auto p_target = p / "target_0.wav";

    for (int target = 0; target < nb_out_sources; ++target) {
        Eigen::Tensor2dXf current_target = audio_targets.chip<0>(target);
        Eigen::MatrixXf target_waveform = Eigen::Map<Eigen::MatrixXf>(
                current_target.data(), 2, audio.cols());

        // now write the 4 audio waveforms to files in the output dir
        // using libnyquist
        // join out_dir with "/target_0.wav"
        // using std::filesystem::path;

        // target 0,1,2,3 map to drums,bass,other,vocals

        std::string target_name;

        switch (target) {
            case 0:
                target_name = "drums";
                instrum_waveform += target_waveform;
                break;
            case 1:
                target_name = "bass";
                instrum_waveform += target_waveform;
                break;
            case 2:
                if (modelNameStr == "free-4s") {
                    target_name = "melody";
                } else {
                    target_name = "other_melody";
                }
                instrum_waveform += target_waveform;
                break;
            case 3:
                target_name = "vocals";
                // the only target that is not included in instrumental
                break;
            case 4:
                target_name = "guitar";
                instrum_waveform += target_waveform;
                break;
            case 5:
                target_name = "piano";
                instrum_waveform += target_waveform;
                break;
            default:
                std::cerr << "Error: target " << target << " not supported"
                          << std::endl;
                return nullptr;
        }

        // insert target_name into the path after the digit
        // e.g. target_name_0_drums.wav
        p_target.replace_filename(target_name + ".wav");

        std::cout << "Writing wav file " << p_target << std::endl;

        write_audio_file(target_waveform, p_target);
        written_paths.push_back(p_target);
    }

    // write the instrumental waveform
    p_target.replace_filename("instrum.wav");
    std::cout << "Writing wav file " << p_target << std::endl;
    write_audio_file(instrum_waveform, p_target);
    written_paths.push_back(p_target);

    // Convert std::vector<std::string> to jobjectArray
    jobjectArray ret = env->NewObjectArray(written_paths.size(),
                                           env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < written_paths.size(); i++) {
        env->SetObjectArrayElement(ret, i, env->NewStringUTF(written_paths[i].c_str()));
    }
    return ret;
}

class AndroidSimpleLogBuf : public std::streambuf {
public:
    AndroidSimpleLogBuf(JNIEnv* env, jobject thiz) : env_(env), thiz_(thiz) {}

protected:
    virtual int_type overflow(int_type c) override {
        if (c != traits_type::eof()) {
            if (c == '\n') {
                if (!buf_.empty()) { // Only log if buffer is not empty
                    __android_log_print(ANDROID_LOG_INFO, "CUSTOM MIX", "%s", buf_.c_str());
                    buf_.clear();
                }
            } else {
                buf_ += static_cast<char>(c);
            }
        }
        return c;
    }

private:
    JNIEnv* env_;
    jobject thiz_;
    std::string buf_;
};

extern "C"
JNIEXPORT jint JNICALL
Java_com_github_sevagh_demucs_1android_CustomMixDialogFragment_createCustomMix(JNIEnv *env, jobject thiz,
                                                                      jobjectArray jStemFilePaths, jstring jOutPath) {
    AndroidSimpleLogBuf coutLogBuf(env, thiz);
    AndroidSimpleLogBuf cerrLogBuf(env, thiz);

    std::cout.rdbuf(&coutLogBuf);
    std::cerr.rdbuf(&cerrLogBuf);

    const char *outPath = env->GetStringUTFChars(jOutPath, nullptr);
    std::string outPathStr(outPath);
    env->ReleaseStringUTFChars(jOutPath, outPath);

    jsize numStems = env->GetArrayLength(jStemFilePaths);
    std::vector<Eigen::MatrixXf> waveforms; // This assumes you have Eigen or similar for handling audio data matrices.

    for (jsize i = 0; i < numStems; i++) {
        jstring jStemPath = (jstring) env->GetObjectArrayElement(jStemFilePaths, i);
        const char *stemPath = env->GetStringUTFChars(jStemPath, nullptr);
        std::string stemPathStr(stemPath);
        env->ReleaseStringUTFChars(jStemPath, stemPath);

        // Load stem file, add to waveforms vector
        AudioLoadResult result = load_audio_file(stemPath);

        if (result.audioData.size() > 0) {
            waveforms.push_back(result.audioData);
        } else {
            std::cerr << "Failed to load stem: " << stemPath << std::endl;
        }
    }

    // Process waveforms to mix down to a single track
    // Initialize the mixedTrack matrix with the size of the first waveform matrix.
    Eigen::MatrixXf mixedTrack = Eigen::MatrixXf::Zero(waveforms[0].rows(), waveforms[0].cols());

    // Sum all waveforms. Assuming all matrices are of the same size.
    for (const auto& waveform : waveforms) {
        mixedTrack += waveform;
    }

    // Write output
    std::filesystem::path outputPath = std::filesystem::path(outPathStr);
    write_audio_file(mixedTrack, outputPath);

    return 0;
}
