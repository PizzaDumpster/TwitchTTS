package com.example.twitchtts

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

// This is a temporary function to be copied
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
        
        // Log supported languages and voices
        val textToSpeech: TextToSpeech? = null
        textToSpeech?.let { tts ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val voices = tts.voices
                if (voices != null && voices.isNotEmpty()) {
                    Log.d("TTS", "Available voices: ${voices.size}")
                    
                    // Log first 5 voices for debugging
                    voices.take(5).forEach { voice ->
                        Log.d("TTS", "Voice: ${voice.name}, Locale: ${voice.locale}, " +
                                "Quality: ${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) voice.quality else "unknown"}")
                    }
                } else {
                    Log.w("TTS", "No voices found")
                }
                
                // Log current voice
                Log.d("TTS", "Current voice: ${tts.voice?.name ?: "Default"}")
            }
            
            // Log language availability
            val isLanguageAvailable = tts.isLanguageAvailable(Locale.US)
            Log.d("TTS", "US Language availability: $isLanguageAvailable")
            val availableLocales = Locale.getAvailableLocales()
                .filter { tts.isLanguageAvailable(it) == TextToSpeech.LANG_AVAILABLE || 
                          tts.isLanguageAvailable(it) == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                          tts.isLanguageAvailable(it) == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE }
                .take(5)
            Log.d("TTS", "Sample available locales: ${availableLocales.joinToString { it.displayName }}")
        }
    } catch (e: Exception) {
        Log.e("TTS", "Error checking TTS availability", e)
    }
}
