package com.tarek.guardian.voice

import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.RecognitionListener
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tarek.guardian.Prefs
import com.tarek.guardian.monitor.SosReporter

/**
 * Voice Command SOS — listens for "يا حارس العيلة" or "Family SOS".
 */
class VoiceCommandActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sr = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar")
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT, android.app.PendingIntent.getActivity(
                this@VoiceCommandActivity, 0,
                android.content.Intent(this@VoiceCommandActivity, VoiceCommandActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE
            ))
        }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spoken = list?.joinToString(" ")?.lowercase() ?: ""
                if (spoken.contains("حارس") || spoken.contains("family") || spoken.contains("sos")) {
                    val code = Prefs.getPairCode(this@VoiceCommandActivity)
                    if (code.isNullOrBlank()) {
                        Toast.makeText(this@VoiceCommandActivity, "SOS unavailable — device not paired", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@VoiceCommandActivity, "SOS triggered — alerting parent", Toast.LENGTH_LONG).show()
                        SosReporter.trigger(this@VoiceCommandActivity, code)
                    }
                }
                finish()
            }
            override fun onError(error: Int) { finish() }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        sr.startListening(intent)
    }
}
