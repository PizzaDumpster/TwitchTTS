package com.example.twitchtts

import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice as AndroidVoice
import java.util.Locale

/**
 * A data class representing a Text-to-Speech voice
 */
data class Voice(
    val id: String, // Voice identifier
    val name: String, // Display name
    val locale: Locale, // Language locale
    val quality: Int = 0, // Voice quality (higher is better)
    val isNetworkVoice: Boolean = false, // Whether it's a network voice
    val androidVoice: AndroidVoice? = null // Android Voice object (API 21+)
) {
    companion object {
        /**
         * Create a fallback default voice for older Android versions
         */
        fun createDefaultVoice(locale: Locale): Voice {
            val languageTag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                locale.toLanguageTag()
            } else {
                "${locale.language}_${locale.country}"
            }
            
            val displayName = try {
                locale.getDisplayName(Locale.US)
            } catch (e: Exception) {
                "Default Voice"
            }
            
            return Voice(
                id = "default_$languageTag",
                name = displayName,
                locale = locale,
                quality = 100 // Default is high quality
            )
        }
        
        /**
         * Create a Voice object from an Android Voice object
         */
        fun fromAndroidVoice(androidVoice: AndroidVoice?): Voice? {
            if (androidVoice == null) return null
            
            return Voice(
                id = androidVoice.name,
                name = createReadableName(androidVoice),
                locale = androidVoice.locale,
                quality = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) androidVoice.quality else 100,
                isNetworkVoice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    androidVoice.features.contains(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS)
                } else false,
                androidVoice = androidVoice
            )
        }
        
        /**
         * Create a human-readable name from the voice name
         */
        private fun createReadableName(androidVoice: AndroidVoice): String {
            // Voice names are often technical, try to make them more readable
            val baseName = androidVoice.name.substringAfterLast("#").substringAfterLast("-")
                .replace('_', ' ').replace(Regex("[0-9]+"), "")
            
            // Try to guess a gender from common naming patterns
            val gender = when {
                androidVoice.name.contains("female", ignoreCase = true) -> "Female"
                androidVoice.name.contains("woman", ignoreCase = true) -> "Female"
                androidVoice.name.contains("male", ignoreCase = true) -> "Male"
                androidVoice.name.contains("man", ignoreCase = true) -> "Male"
                else -> null
            }
            
            // Get a proper locale name
            val localeName = androidVoice.locale.getDisplayName(Locale.US)
            
            // Combine available parts into a readable name
            return when {
                baseName.isNotEmpty() && gender != null -> "$baseName ($gender, $localeName)"
                baseName.isNotEmpty() -> "$baseName ($localeName)"
                gender != null -> "$gender voice - $localeName"
                else -> "Voice - $localeName"
            }.replaceFirstChar { it.uppercase() }
        }
    }
    
    override fun toString(): String = name
}
