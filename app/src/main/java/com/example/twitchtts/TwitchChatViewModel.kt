package com.example.twitchtts

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class TwitchChatViewModel : ViewModel() {
    
    private val _chatMessages = mutableStateListOf<ChatMessage>()
    val chatMessages: List<ChatMessage> = _chatMessages
    
    val channelName = mutableStateOf("")
    val isConnected = mutableStateOf(false)
    val isTtsEnabled = mutableStateOf(true)
    
    // TTS queue size management
    val maxTtsQueueSize = mutableStateOf(5) // Maximum number of messages in queue
    val currentQueueSize = mutableStateOf(0) // Current number of messages waiting
    val speechRate = mutableStateOf(1.0f) // Speech rate: 0.5 (slower) to 2.0 (faster)
    
    // Maximum chat history size - can be adjusted
    val maxChatHistorySize = mutableStateOf(100)
    
    // Memory management - track processed messages to detect potential leaks
    private val messageCount = AtomicInteger(0)
    private var lastMemoryCheckTime = System.currentTimeMillis()
    private var memoryCheckJob: Job? = null
    
    private var textToSpeech: TextToSpeech? = null
    private val ttsQueue = ConcurrentLinkedQueue<String>()
    private var isSpeaking = false
    
    // Audio focus handling
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private val audioAttributes by lazy {
        AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()
    }
    
    private val chatService = TwitchChatService()
    private var chatJob: Job? = null
    
    init {
        // Start periodic memory check
        startMemoryCheck()
    }
    
    private fun startMemoryCheck() {
        memoryCheckJob = viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                delay(60000) // Check every minute
                performMemoryMaintenance()
            }
        }
    }
    
    private fun performMemoryMaintenance() {
        // Log memory stats
        val runtime = Runtime.getRuntime()
        val usedMemoryMB = (runtime.totalMemory() - runtime.freeMemory()) / 1048576L
        val maxMemoryMB = runtime.maxMemory() / 1048576L
        val memoryUsagePercent = (usedMemoryMB.toFloat() / maxMemoryMB) * 100
        
        Log.d("TwitchTTS", "Memory: ${usedMemoryMB}MB / ${maxMemoryMB}MB (${memoryUsagePercent.toInt()}%)")
        Log.d("TwitchTTS", "Messages processed: ${messageCount.get()}, Chat history size: ${_chatMessages.size}")
        
        // If memory usage is high (over 70%), trim chat history more aggressively
        if (memoryUsagePercent > 70 && _chatMessages.size > 50) {
            val toRemove = _chatMessages.size / 2
            repeat(toRemove) {
                if (_chatMessages.isNotEmpty()) {
                    _chatMessages.removeAt(_chatMessages.lastIndex)
                }
            }
            Log.d("TwitchTTS", "Memory pressure detected: Reduced chat history by $toRemove messages")
        }
        
        // Request garbage collection (this is just a suggestion to the system)
        System.gc()
    }
    
    // Initialize TextToSpeech
    fun initTts(context: Context) {
        // Clean up any existing TTS instance first to prevent memory leaks
        cleanupTts()
        
        // Get AudioManager
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        try {
            textToSpeech = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val result = textToSpeech?.setLanguage(Locale.US)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.e("TTS", "Language not supported")
                    } else {
                        setupTtsCallbacks()
                        // Set initial speech rate
                        textToSpeech?.setSpeechRate(speechRate.value)
                        
                        // Use modern audio attributes instead of stream type for API 21+
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            textToSpeech?.setAudioAttributes(audioAttributes)
                        } else {
                            @Suppress("DEPRECATION")
                            textToSpeech?.setEngineByPackageName("com.google.android.tts")
                        }
                    }
                } else {
                    Log.e("TTS", "TTS Initialization failed with status: $status")
                }
            }
        } catch (e: Exception) {
            Log.e("TTS", "Error initializing TextToSpeech", e)
        }
    }
    
    // Separate cleanup method for TTS resources
    private fun cleanupTts() {
        try {
            textToSpeech?.let { tts ->
                tts.stop()
                tts.shutdown()
                textToSpeech = null
            }
        } catch (e: Exception) {
            Log.e("TTS", "Error cleaning up TTS", e)
        }
    }
    
    private fun setupTtsCallbacks() {
        try {
            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    viewModelScope.launch(Dispatchers.Main) {
                        isSpeaking = true
                        requestAudioFocus()
                    }
                }
                
                override fun onDone(utteranceId: String?) {
                    viewModelScope.launch(Dispatchers.Main) {
                        isSpeaking = false
                        abandonAudioFocus()
                        updateQueueSize()
                        processNextTtsItem()
                    }
                }
                
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    viewModelScope.launch(Dispatchers.Main) {
                        Log.e("TTS", "TTS error with utterance ID: $utteranceId")
                        isSpeaking = false
                        abandonAudioFocus()
                        updateQueueSize()
                        processNextTtsItem()
                    }
                }
                
                // For older Android versions
                @Suppress("DEPRECATION")
                override fun onError(utteranceId: String?, errorCode: Int) {
                    viewModelScope.launch(Dispatchers.Main) {
                        Log.e("TTS", "TTS error with utterance ID: $utteranceId, error code: $errorCode")
                        isSpeaking = false
                        abandonAudioFocus()
                        updateQueueSize()
                        processNextTtsItem()
                    }
                }
            })
        } catch (e: Exception) {
            Log.e("TTS", "Error setting up TTS callbacks", e)
        }
    }
    
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Permanent loss of audio focus
                textToSpeech?.stop()
                isSpeaking = false
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Temporary loss of audio focus
                textToSpeech?.stop()
                isSpeaking = false
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Loss of audio focus for a short time, but we can duck (play at lower volume)
                textToSpeech?.setSpeechRate(speechRate.value * 0.8f)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // Your app has been granted audio focus
                textToSpeech?.setSpeechRate(speechRate.value)
            }
        }
    }
    
    private fun requestAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Use modern AudioFocusRequest for Android O and above
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(audioAttributes)
                    .setWillPauseWhenDucked(false)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .build()
                
                audioManager?.requestAudioFocus(audioFocusRequest!!)
            } else {
                @Suppress("DEPRECATION")
                audioManager?.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        } catch (e: Exception) {
            Log.e("TTS", "Error requesting audio focus", e)
        }
    }
    
    private fun abandonAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(audioFocusChangeListener)
            }
        } catch (e: Exception) {
            Log.e("TTS", "Error abandoning audio focus", e)
        }
    }
    
    private fun processNextTtsItem() {
        if (ttsQueue.isNotEmpty() && !isSpeaking && isTtsEnabled.value && textToSpeech != null) {
            val nextText = ttsQueue.poll()
            updateQueueSize()
            nextText?.let { text ->
                try {
                    val utteranceId = UUID.randomUUID().toString()
                    
                    // Handle different API levels for speak method
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        val bundle = Bundle()
                        bundle.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                        textToSpeech?.speak(text, TextToSpeech.QUEUE_ADD, bundle, utteranceId)
                    } else {
                        @Suppress("DEPRECATION")
                        val params = HashMap<String, String>()
                        params[TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID] = utteranceId
                        textToSpeech?.speak(text, TextToSpeech.QUEUE_ADD, params)
                    }
                } catch (e: Exception) {
                    Log.e("TTS", "Error processing TTS item", e)
                    // Reset speaking state in case of error
                    isSpeaking = false
                    // Try next item
                    viewModelScope.launch {
                        delay(500)
                        processNextTtsItem()
                    }
                }
            }
        }
    }
    
    private fun updateQueueSize() {
        currentQueueSize.value = ttsQueue.size
    }
    
    // Set speech rate
    fun setSpeechRate(rate: Float) {
        if (rate in 0.5f..2.0f) {
            speechRate.value = rate
            textToSpeech?.setSpeechRate(rate)
        }
    }
    
    // Skip current message and move to the next
    fun skipCurrentMessage() {
        if (isSpeaking) {
            try {
                textToSpeech?.stop()
                isSpeaking = false
                // Add a small delay to ensure TTS engine has time to process the stop command
                viewModelScope.launch {
                    delay(300)
                    processNextTtsItem()
                }
            } catch (e: Exception) {
                Log.e("TTS", "Error skipping current message", e)
                isSpeaking = false
                processNextTtsItem()
            }
        }
    }
    
    // Clear the TTS queue
    fun clearTtsQueue() {
        try {
            ttsQueue.clear()
            updateQueueSize()
            if (isSpeaking) {
                textToSpeech?.stop()
                isSpeaking = false
            }
            // The actual queue in the TTS engine might still have pending items
            // Make sure we flush the internal TTS queue as well if possible
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                textToSpeech?.let {
                    try {
                        val result = it.stop()
                        if (result != TextToSpeech.SUCCESS) {
                            Log.w("TTS", "Failed to stop TTS engine properly, result: $result")
                        }
                    } catch (e: Exception) {
                        Log.e("TTS", "Error stopping TTS engine", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("TTS", "Error clearing TTS queue", e)
        }
    }
    
    /**
     * Check if TTS is available on the device
     * This can help ensure we have a working TTS engine
     */
    fun checkTtsAvailability(context: Context) {
        try {
            // Check if TTS data is present
            val intent = Intent()
            intent.action = TextToSpeech.Engine.ACTION_CHECK_TTS_DATA
            
            // Use context.startActivity for API below 23 to avoid permission issues
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            }
            
            // Log supported languages
            textToSpeech?.let { tts ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val voices = tts.voices
                    if (voices != null && voices.isNotEmpty()) {
                        Log.d("TTS", "Available voices: ${voices.size}")
                    } else {
                        Log.w("TTS", "No voices found")
                    }
                }
                
                val isLanguageAvailable = tts.isLanguageAvailable(Locale.US)
                Log.d("TTS", "US Language availability: $isLanguageAvailable")
            }
        } catch (e: Exception) {
            Log.e("TTS", "Error checking TTS availability", e)
        }
    }
    
    // Connect to Twitch chat - no login required
    fun connectToTwitchChat() {
        if (channelName.value.isNotBlank()) {
            // Cancel existing job if any
            chatJob?.cancel()
            
            // Clear previous messages when connecting to a new channel
            _chatMessages.clear()
            
            // Start new connection
            chatJob = viewModelScope.launch {
                try {
                    isConnected.value = true
                    
                    // Use our custom chat service instead of Twitch4J
                    val result = chatService.connectToChat(channelName.value) { message ->
                        viewModelScope.launch(Dispatchers.Main) {
                            // Increment message counter
                            messageCount.incrementAndGet()
                            
                            _chatMessages.add(0, message)
                            // Limit the chat history size
                            if (_chatMessages.size > maxChatHistorySize.value) {
                                _chatMessages.removeAt(_chatMessages.lastIndex)
                            }
                            
                            // Add to TTS queue if enabled and not exceeding max queue size
                            if (isTtsEnabled.value) {
                                val ttsText = "${message.username} says: ${message.message}"
                                if (ttsQueue.size < maxTtsQueueSize.value) {
                                    ttsQueue.add(ttsText)
                                    updateQueueSize()
                                    if (!isSpeaking) {
                                        processNextTtsItem()
                                    }
                                }
                            }
                        }
                    }
                    
                    if (!result) {
                        isConnected.value = false
                    }
                    
                } catch (e: Exception) {
                    Log.e("TwitchChat", "Error connecting to Twitch chat", e)
                    isConnected.value = false
                }
            }
        }
    }
    
    fun disconnectFromTwitchChat() {
        chatJob?.cancel()
        chatJob = null
        chatService.disconnect()
        isConnected.value = false
    }
    
    fun toggleTts() {
        isTtsEnabled.value = !isTtsEnabled.value
        if (isTtsEnabled.value && ttsQueue.isNotEmpty() && !isSpeaking) {
            processNextTtsItem()
        }
    }
    
    fun clearChat() {
        _chatMessages.clear()
    }
    
    override fun onCleared() {
        super.onCleared()
        
        try {
            // Cancel all running jobs
            viewModelScope.launch {
                // Disconnect from Twitch chat
                disconnectFromTwitchChat()
                
                // Cancel memory check job
                memoryCheckJob?.cancel()
                
                // Clear TTS queue
                ttsQueue.clear()
                updateQueueSize()
                
                // Properly abandon audio focus
                abandonAudioFocus()
                
                // Clear audio focus resources
                audioFocusRequest = null
                audioManager = null
                
                // Proper cleanup of TextToSpeech resources
                cleanupTts()
            }
            
            // Force garbage collection suggestion
            System.gc()
            
        } catch (e: Exception) {
            Log.e("TwitchChatViewModel", "Error during cleanup", e)
        }
    }
}