package com.github.sevagh.demucs_android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.MutableLiveData

class SharedViewModel : ViewModel() {
    var useMicAsInput: MutableLiveData<Boolean> = MutableLiveData(false)
    var useCaptureAsInput: MutableLiveData<Boolean> = MutableLiveData(false)
}
