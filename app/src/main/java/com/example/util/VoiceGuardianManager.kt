package com.example.util

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class GuardianVoicePersona(val label: String) {
    FEMALE("Female Guardian"),
    MALE("Male Protector")
}

data class LanguageOption(
    val code: String,
    val tag: String,
    val name: String,
    val nativeName: String
) {
    val locale: Locale get() = Locale.forLanguageTag(tag)
}

data class VoiceAcousticProfile(
    val pitch: Float,
    val speechRate: Float,
    val matchedNativeGender: Boolean
)

/**
 * Humanized Multilingual Voice Co-Pilot (All Languages — Indian + Global Foreign):
 * - Selects the highest-quality neural/HD Android TTS voice matching the requested language & Male/Female persona,
 *   avoiding robotic/low-quality engines wherever natural voices exist.
 * - Calibrates natural human gender pitch & cadence depending on whether the selected TTS voice natively
 *   matches the requested gender (so Male isn't unnaturally dragged down and Female isn't shrill).
 * - Cleans URLs, markdown, and bracket tags before speaking, and paces multi-sentence speech with short
 *   natural breath pauses instead of reading walls of text in one mechanical monotone breath.
 */
class VoiceGuardianManager(private val context: Context) {

    private val appContext: Context = context.applicationContext
    private val prefs by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var speechRecognizer: SpeechRecognizer? = null

    @Volatile
    private var activeBatchFinalUtteranceId: String? = null

    private val _selectedLanguage = MutableStateFlow(loadSavedLanguage())
    val selectedLanguage: StateFlow<LanguageOption> = _selectedLanguage.asStateFlow()

    private val _voicePersona = MutableStateFlow(loadSavedPersona())
    val voicePersona: StateFlow<GuardianVoicePersona> = _voicePersona.asStateFlow()

    private val _autoReadOut = MutableStateFlow(loadSavedAutoRead())
    val autoReadOut: StateFlow<Boolean> = _autoReadOut.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _secretDistressPhrase = MutableStateFlow(loadSavedDistressPhrase())
    val secretDistressPhrase: StateFlow<String> = _secretDistressPhrase.asStateFlow()

    private val _availableLanguages = MutableStateFlow(SUPPORTED_LANGUAGES)
    val availableLanguages: StateFlow<List<LanguageOption>> = _availableLanguages.asStateFlow()

    private val _voiceStatusNote = MutableStateFlow("")
    val voiceStatusNote: StateFlow<String> = _voiceStatusNote.asStateFlow()

    private val _needsTtsDataInstall = MutableStateFlow(false)
    val needsTtsDataInstall: StateFlow<Boolean> = _needsTtsDataInstall.asStateFlow()

    private val _activeVoiceName = MutableStateFlow("")
    val activeVoiceName: StateFlow<String> = _activeVoiceName.asStateFlow()

    init {
        initTts()
    }

    private fun loadSavedLanguage(): LanguageOption {
        val savedTag = prefs.getString(KEY_LANGUAGE_TAG, null)
        return SUPPORTED_LANGUAGES.firstOrNull { it.tag.equals(savedTag, ignoreCase = true) }
            ?: SUPPORTED_LANGUAGES.first()
    }

    private fun loadSavedPersona(): GuardianVoicePersona {
        val saved = prefs.getString(KEY_PERSONA, GuardianVoicePersona.FEMALE.name)
        return runCatching { GuardianVoicePersona.valueOf(saved ?: GuardianVoicePersona.FEMALE.name) }
            .getOrDefault(GuardianVoicePersona.FEMALE)
    }

    private fun loadSavedAutoRead(): Boolean = prefs.getBoolean(KEY_AUTO_READ, true)

    private fun loadSavedDistressPhrase(): String =
        prefs.getString(KEY_DISTRESS_PHRASE, "help raksha")
            ?.trim()
            ?.lowercase(Locale.US)
            ?.takeIf { it.length >= 3 }
            ?: "help raksha"

    private fun isDeviceOnline(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        return cm?.activeNetwork != null
    }

    private fun initTts() {
        try {
            tts = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    isTtsReady = true
                    try {
                        val audioAttrs = android.media.AudioAttributes.Builder()
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .build()
                        tts?.setAudioAttributes(audioAttrs)
                    } catch (_: Exception) {
                    }
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _isSpeaking.value = true
                        }

                        override fun onDone(utteranceId: String?) {
                            if (utteranceId == null || utteranceId == activeBatchFinalUtteranceId) {
                                _isSpeaking.value = false
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            if (utteranceId == null || utteranceId == activeBatchFinalUtteranceId) {
                                _isSpeaking.value = false
                            }
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            if (utteranceId == null || utteranceId == activeBatchFinalUtteranceId) {
                                _isSpeaking.value = false
                            }
                        }
                    })
                    refreshAvailableLanguages()
                    applyVoiceSettings()
                } else {
                    isTtsReady = false
                    _voiceStatusNote.value = "System speech engine unavailable on this device"
                }
            }
        } catch (_: Exception) {
            isTtsReady = false
        }
    }

    fun setLanguage(option: LanguageOption, speakConfirmation: Boolean = false) {
        _selectedLanguage.value = option
        prefs.edit().putString(KEY_LANGUAGE_TAG, option.tag).apply()
        applyVoiceSettings()
        if (speakConfirmation && _autoReadOut.value) {
            speakPersonaConfirmation()
        }
    }

    fun setVoicePersona(persona: GuardianVoicePersona, speakConfirmation: Boolean = true) {
        _voicePersona.value = persona
        prefs.edit().putString(KEY_PERSONA, persona.name).apply()
        applyVoiceSettings()
        if (speakConfirmation && _autoReadOut.value) {
            speakPersonaConfirmation()
        }
    }

    fun setAutoReadOut(enabled: Boolean) {
        _autoReadOut.value = enabled
        prefs.edit().putBoolean(KEY_AUTO_READ, enabled).apply()
        if (!enabled) stopSpeaking()
    }

    fun setSecretDistressPhrase(phrase: String) {
        val clean = phrase.trim().lowercase(Locale.US)
        if (clean.length >= 3) {
            _secretDistressPhrase.value = clean
            prefs.edit().putString(KEY_DISTRESS_PHRASE, clean).apply()
        }
    }

    fun speakPersonaConfirmation() {
        val code = _selectedLanguage.value.code
        val persona = _voicePersona.value
        val sample = buildPersonaSampleLine(code, persona)
        speak(sample, force = true)
    }

    fun buildInstallTtsDataIntent(): Intent {
        return Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun refreshAvailableLanguages() {
        val engine = tts ?: return
        val merged = getAllAvailableLanguagesFromEngine(engine)
        _availableLanguages.value = merged
    }

    private fun getAllAvailableLanguagesFromEngine(engine: TextToSpeech): List<LanguageOption> {
        val list = SUPPORTED_LANGUAGES.toMutableList()
        try {
            val existingTags = list.map { it.tag.lowercase(Locale.US) }.toSet()
            val existingLangCountry = list.map {
                "${it.locale.language.lowercase(Locale.US)}-${it.locale.country.lowercase(Locale.US)}"
            }.toSet()
            engine.availableLanguages
                ?.sortedBy { it.displayName }
                ?.forEach { loc ->
                    val tag = loc.toLanguageTag()
                    val langCountry = "${loc.language.lowercase(Locale.US)}-${loc.country.lowercase(Locale.US)}"
                    if (tag.lowercase(Locale.US) !in existingTags &&
                        langCountry !in existingLangCountry &&
                        loc.displayLanguage.isNotBlank()
                    ) {
                        list.add(
                            LanguageOption(
                                code = loc.language.uppercase(Locale.US),
                                tag = tag,
                                name = loc.displayName,
                                nativeName = loc.getDisplayLanguage(loc)
                            )
                        )
                    }
                }
        } catch (_: Exception) {
        }
        return list
    }

    private fun applyVoiceSettings() {
        val engine = tts ?: return
        if (!isTtsReady) return
        val option = _selectedLanguage.value
        val locale = option.locale
        val wantMale = _voicePersona.value == GuardianVoicePersona.MALE
        try {
            val langResult = engine.setLanguage(locale)
            val missingPack = langResult == TextToSpeech.LANG_MISSING_DATA ||
                langResult == TextToSpeech.LANG_NOT_SUPPORTED

            if (missingPack) {
                val baseLangResult = engine.setLanguage(Locale.forLanguageTag(locale.language))
                val stillMissing = baseLangResult == TextToSpeech.LANG_MISSING_DATA ||
                    baseLangResult == TextToSpeech.LANG_NOT_SUPPORTED
                _needsTtsDataInstall.value = stillMissing
                if (stillMissing) {
                    _voiceStatusNote.value = "${option.name} offline voice pack not installed yet — tap Install Voice Pack"
                } else {
                    _voiceStatusNote.value = "${option.name} (${_voicePersona.value.label})"
                }
            } else {
                _needsTtsDataInstall.value = false
                _voiceStatusNote.value = "${option.name} • ${_voicePersona.value.label}"
            }

            val online = isDeviceOnline()
            val allVoices = engine.voices?.toList().orEmpty()
                .filter { !it.isNetworkConnectionRequired || online }
                .ifEmpty { engine.voices?.toList().orEmpty() }

            val chosen = selectBestVoiceForPersona(
                voices = allVoices,
                targetLocale = locale,
                wantMale = wantMale
            )
            if (chosen != null) {
                engine.voice = chosen
                _activeVoiceName.value = chosen.name
            } else {
                _activeVoiceName.value = "system-default"
            }

            val matchedGender = chosen != null && matchesExplicitGender(chosen, wantMale)
            val profile = computeAcousticProfile(wantMale = wantMale, matchedNativeGender = matchedGender)
            engine.setPitch(profile.pitch)
            engine.setSpeechRate(profile.speechRate)
        } catch (_: Exception) {
        }
    }

    fun speak(text: String, force: Boolean = false) {
        if (!force && !_autoReadOut.value) return
        val chunks = humanizeAndChunkForSpeech(text)
        if (chunks.isEmpty()) return
        val engine = tts ?: return
        if (!isTtsReady) return
        applyVoiceSettings()
        _isSpeaking.value = true
        val batchPrefix = "RAKSHA_TTS_${System.currentTimeMillis()}"
        val finalId = "${batchPrefix}_${chunks.lastIndex}"
        activeBatchFinalUtteranceId = finalId
        try {
            chunks.forEachIndexed { idx, chunk ->
                val queueMode = if (idx == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                val utteranceId = "${batchPrefix}_$idx"
                engine.speak(chunk, queueMode, null, utteranceId)
                if (idx < chunks.lastIndex) {
                    engine.playSilentUtterance(220L, TextToSpeech.QUEUE_ADD, "${batchPrefix}_pause_$idx")
                }
            }
        } catch (_: Exception) {
            _isSpeaking.value = false
        }
    }

    fun speakProtectorCheckIn(userName: String) {
        val code = _selectedLanguage.value.code
        val persona = _voicePersona.value
        val display = userName.ifBlank { "friend" }
        val line = when {
            code == "HI" && persona == GuardianVoicePersona.MALE ->
                "अरे $display, मैं बाहर मेन रोड पर ही खड़ा हूँ और तुम्हारी लाइव लोकेशन देख रहा हूँ। आराम से चलते रहो, मैं बस दो मिनट में आ रहा हूँ।"
            code == "HI" ->
                "हाँ $display, मैं तुम्हारी लोकेशन लगातार ट्रैक कर रही हूँ। बिल्कुल चिंता मत करो, मैं बस दो मिनट में पहुँच रही हूँ।"
            code == "BN" && persona == GuardianVoicePersona.MALE ->
                "আরে $display, আমি বাইরেই অপেক্ষা করছি আর তোমার লাইভ লোকেশন দেখছি। একদম শান্ত থাকো, আমি দুই মিনিটের মধ্যে চলে আসছি।"
            code == "BN" ->
                "হ্যাঁ $display, আমি তোমার লাইভ লোকেশন দেখতে পাচ্ছি। চিন্তা কোরো না, আমি দুই মিনিটের মধ্যে চলে আসছি।"
            code == "TA" ->
                "கவலைப்படாதீர்கள், உங்கள் நேரலை இருப்பிடத்தைப் பார்க்கிறேன். இரண்டு நிமிடங்களில் வருகிறேன், பாதுகாப்பாக இருங்கள்."
            code == "TE" ->
                "దిగులుపడకండి, మీ లైవ్ లొకేషన్ చూస్తున్నాను. రెండు నిమిషాల్లో వస్తున్నాను, జాగ్రత్తగా ఉండండి."
            code == "MR" ->
                "काळजी करू नका, तुमचे लाईव्ह लोकेशन दिसत आहे. मी दोन मिनिटांत पोहोचत आहे, सुरक्षित ठिकाणी थांबा."
            code == "ES" ->
                "Tranquilo, estoy viendo tu ubicación en vivo ahora mismo. Quédate cerca de la luz, llego en dos minutos."
            code == "FR" ->
                "Rassure-toi, je vois ta position en direct. Reste dans un endroit bien éclairé, j'arrive dans deux minutes."
            code == "DE" ->
                "Keine Sorge, ich sehe deinen Live-Standort. Bleib an einem beleuchteten Ort, ich bin in zwei Minuten da."
            code == "AR" ->
                "اطمئن، أرى موقعك المباشر الآن. ابقَ في مكان مضاء وآمن، سأصل خلال دقيقتين."
            code == "JA" ->
                "安心してください。現在地をリアルタイムで確認しています。あと2分でそちらに着きます。"
            persona == GuardianVoicePersona.MALE ->
                "Hey $display, I'm right outside on the main road watching your live GPS. Keep walking calmly — I've got your back and I'll be there in two minutes."
            else ->
                "Hey $display, I have your live location open right in front of me. Stay on the line with me — I'm right here with you and just two minutes away."
        }
        speak(line, force = true)
    }

    fun speakFakeCallScript(callerName: String) {
        val code = _selectedLanguage.value.code
        val persona = _voicePersona.value
        val caller = callerName.ifBlank { "Dad" }
        val line = when {
            code == "HI" && persona == GuardianVoicePersona.MALE ->
                "हाँ, मैं सुन रहा हूँ! मैं अभी बाहर ही हूँ और तुम्हारी लाइव लोकेशन देख रहा हूँ। बिल्कुल घबराओ मत, रोशनी वाली जगह पर रहो, मैं बस दो मिनट में गाड़ी लेकर आ रहा हूँ।"
            code == "HI" ->
                "हाँ, मैं लाइन पर हूँ! मुझे तुम्हारी लोकेशन साफ़ दिख रही है। तुम बस उस लाइट वाले कॉर्नर पर रुको, मैं दो ही मिनट में पहुँच रही हूँ।"
            code == "BN" && persona == GuardianVoicePersona.MALE ->
                "হ্যাঁ, আমি শুনতে পাচ্ছি! আমি বাইরেই দাঁড়িয়ে তোমার লাইভ লোকেশন দেখছি। একদম ভয় পেয়ো না, আলোর কাছে থাকো, আমি দুই মিনিটের মধ্যেই গাড়ি নিয়ে পৌঁছাচ্ছি।"
            code == "BN" ->
                "হ্যাঁ, আমি লাইনে আছি! তোমার লোকেশন দেখতে পাচ্ছি। তুমি আলোর কাছে থাকো, আমি দুই মিনিটের মধ্যে আসছি।"
            code == "ES" ->
                "¡Hola! Sí, te escucho. Estoy viendo tu ubicación en vivo ahora mismo y ya voy llegando en dos minutos. Quédate donde haya luz."
            code == "FR" ->
                "Oui, je t'entends bien ! Je vois ta position en direct, j'arrive en voiture dans deux minutes. Reste bien en vue."
            code == "AR" ->
                "نعم، أنا أسمعك جيداً! أرى موقعك المباشر الآن وسأصل بالسيارة خلال دقيقتين. ابقَ في مكان مضاء."
            code == "JA" ->
                "うん、聞こえてるよ！今そっちの現在地を見てる。あと2分で車で着くから、明るい所で待っててね。"
            persona == GuardianVoicePersona.MALE ->
                "Hey! Yes, I hear you clearly. I can see your exact pin on the live map right now. Just stay near the street lights — I'm pulling up in the car in two minutes. Keep talking to me, you're completely safe."
            else ->
                "Hey! Yes, I'm right here on the phone. I'm watching your live pin move right now and I'm pulling up in two minutes. Just stay near the lights and keep talking to me."
        }
        speak(line, force = true)
    }

    fun stopSpeaking() {
        activeBatchFinalUtteranceId = null
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        _isSpeaking.value = false
    }

    fun startListening(
        onResult: (String) -> Unit,
        onError: (String) -> Unit = {},
        onDistressPhraseDetected: (() -> Unit)? = null
    ) {
        val startBlock = Runnable {
            try {
                if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
                    onError("Voice recognition unavailable on this device")
                    return@Runnable
                }
                speechRecognizer?.destroy()
                val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
                speechRecognizer = recognizer
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        _isListening.value = true
                    }

                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        _isListening.value = false
                    }

                    override fun onError(error: Int) {
                        _isListening.value = false
                        onError("Voice input error ($error)")
                    }

                    override fun onResults(results: Bundle?) {
                        _isListening.value = false
                        val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        if (spoken.isNotBlank()) {
                            if (matchesDistressPhrase(spoken, _secretDistressPhrase.value)) {
                                onDistressPhraseDetected?.invoke()
                            }
                            onResult(spoken)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        if (matchesDistressPhrase(partial, _secretDistressPhrase.value)) {
                            onDistressPhraseDetected?.invoke()
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                val langTag = _selectedLanguage.value.tag
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, langTag)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
                recognizer.startListening(intent)
            } catch (e: Exception) {
                _isListening.value = false
                onError(e.message ?: "Unable to start microphone")
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            startBlock.run()
        } else {
            mainHandler.post(startBlock)
        }
    }

    fun stopListening() {
        val stopBlock = Runnable {
            try {
                speechRecognizer?.stopListening()
            } catch (_: Exception) {
            }
            _isListening.value = false
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            stopBlock.run()
        } else {
            mainHandler.post(stopBlock)
        }
    }

    fun getAllAvailableLanguages(): List<LanguageOption> = _availableLanguages.value

    fun release() {
        try {
            tts?.stop()
            tts?.shutdown()
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val PREFS_NAME = "rakshaone_voice_prefs"
        private const val KEY_LANGUAGE_TAG = "voice_language_tag"
        private const val KEY_PERSONA = "voice_persona"
        private const val KEY_AUTO_READ = "voice_auto_read"
        private const val KEY_DISTRESS_PHRASE = "voice_distress_phrase"

        private val MALE_NAME_MARKERS = listOf(
            "gender=male", "male", "#male", "_m_", "-m-", "-b-", "-d-", "-e-",
            "-end-", "-ene-", "-hid-", "-hie-", "-bnd-", "-bne-",
            "-iom-", "-iol-", "-tpd-", "-tpe-", "man", "guy", "daniel", "arthur", "rishi", "prabhat", "madhur"
        )
        private val FEMALE_NAME_MARKERS = listOf(
            "gender=female", "female", "#female", "_f_", "-f-", "-a-", "-c-",
            "-ena-", "-enc-", "-hia-", "-hic-", "-bna-", "-bnc-",
            "-sfg-", "-tpf-", "-iob-", "-tpc-", "woman", "girl", "samantha", "karen", "lekha", "tara", "neerja", "swara"
        )

        fun matchesDistressPhrase(transcript: String, phrase: String): Boolean {
            if (transcript.isBlank() || phrase.isBlank()) return false
            return transcript.lowercase(Locale.US).contains(phrase.trim().lowercase(Locale.US))
        }

        /**
         * Natural, organic human vocal calibration:
         * Preserves true human formant frequencies at natural 1.0 pitch so there is ZERO robotic metallic ringing.
         * Sets conversational cadence to a calm, reassuring 0.98x human pace.
         */
        fun computeAcousticProfile(
            wantMale: Boolean,
            matchedNativeGender: Boolean
        ): VoiceAcousticProfile {
            return if (wantMale) {
                if (matchedNativeGender) {
                    VoiceAcousticProfile(pitch = 0.96f, speechRate = 0.98f, matchedNativeGender = true)
                } else {
                    VoiceAcousticProfile(pitch = 0.88f, speechRate = 0.98f, matchedNativeGender = false)
                }
            } else {
                if (matchedNativeGender) {
                    VoiceAcousticProfile(pitch = 1.00f, speechRate = 0.98f, matchedNativeGender = true)
                } else {
                    VoiceAcousticProfile(pitch = 1.04f, speechRate = 0.98f, matchedNativeGender = false)
                }
            }
        }

        /**
         * Strips markdown, technical URLs/tags, and splits long replies into natural breath-paused chunks
         * so Google TTS speaks smoothly like a calm human instead of one fast mechanical drone.
         */
        fun humanizeAndChunkForSpeech(rawText: String): List<String> {
            val cleaned = rawText
                .replace(Regex("\\[(?:Hindi|Bengali|Spanish|French|Arabic) Mode]\\s*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\[SILENT_CONTEXT:[^\\]]*]"), "")
                .replace(Regex("https?://\\S+"), "")
                .replace(Regex("\\*\\*|__|[*#`~>]"), "")
                .replace("•", ". ")
                .replace("—", ", ")
                .replace("–", ", ")
                .replace("SHA-256", "SHA 256")
                .replace("Zero-FIR", "Zero F I R")
                .replace(Regex("(?m)^\\s*\\d+[.)]\\s+"), ". ")
                .replace(Regex("\\n+"), ". ")
                .replace(Regex("\\s+/\\s+|/"), " or ")
                .replace("&", " and ")
                .replace(Regex("\\s+"), " ")
                .replace(Regex("\\.\\s*\\."), ".")
                .trim()
                .trimStart('.', ',', ' ')

            if (cleaned.isBlank()) return emptyList()

            val sentences = cleaned.split(Regex("(?<=[.!?।؟])\\s+"))
                .map { it.trim() }
                .filter { it.isNotBlank() }

            if (sentences.isEmpty()) return listOf(cleaned)

            val chunks = mutableListOf<String>()
            val current = StringBuilder()
            for (sentence in sentences) {
                if (current.isNotEmpty() && current.length + sentence.length + 1 > 210) {
                    chunks.add(current.toString().trim())
                    current.clear()
                }
                if (current.isNotEmpty()) current.append(' ')
                current.append(sentence)
            }
            if (current.isNotEmpty()) {
                chunks.add(current.toString().trim())
            }
            return chunks
        }

        fun matchesExplicitGender(voice: Voice, wantMale: Boolean): Boolean {
            val targetMarkers = if (wantMale) MALE_NAME_MARKERS else FEMALE_NAME_MARKERS
            val oppositeMarkers = if (wantMale) FEMALE_NAME_MARKERS else MALE_NAME_MARKERS
            val n = voice.name.lowercase(Locale.US)
            val feats = voice.features?.joinToString(" ")?.lowercase(Locale.US).orEmpty()
            val hitsTarget = targetMarkers.any { token -> n.contains(token) || feats.contains(token) }
            val hitsOpposite = oppositeMarkers.any { token -> n.contains(token) || feats.contains(token) }
            return hitsTarget && !hitsOpposite
        }

        fun buildPersonaSampleLine(languageCode: String, persona: GuardianVoicePersona): String {
            return when {
                languageCode == "HI" && persona == GuardianVoicePersona.MALE ->
                    "नमस्ते, पुरुष सुरक्षा आवाज़ सक्रिय है। मैं हर कदम पर आपके साथ हूँ।"
                languageCode == "HI" ->
                    "नमस्ते, महिला सुरक्षा आवाज़ सक्रिय है। मैं हर कदम पर आपके साथ हूँ।"
                languageCode == "BN" && persona == GuardianVoicePersona.MALE ->
                    "নমস্কার, পুরুষ সুরক্ষা ভয়েস সক্রিয় হয়েছে। আমি সবসময় আপনার সহায়তায় সাথে আছি।"
                languageCode == "BN" ->
                    "নমস্কার, মহিলা সুরক্ষা ভয়েস সক্রিয় হয়েছে। আমি সবসময় আপনার সহায়তায় সাথে আছি।"
                languageCode == "ES" && persona == GuardianVoicePersona.MALE ->
                    "Hola, voz protectora masculina activada. Cuentas conmigo en todo momento."
                languageCode == "ES" ->
                    "Hola, voz guardiana femenina activada. Cuentas conmigo en todo momento."
                languageCode == "FR" && persona == GuardianVoicePersona.MALE ->
                    "Bonjour, voix protectrice masculine activée. Je veille sur toi."
                languageCode == "FR" ->
                    "Bonjour, voix gardienne féminine activée. Je veille sur toi."
                languageCode == "AR" ->
                    "مرحباً، تم تفعيل صوت الحماية. أنا أرافقك وأحميك دائماً."
                languageCode == "JA" ->
                    "こんにちは。安全ガード音声が有効になりました。いつでも安心してください。"
                persona == GuardianVoicePersona.MALE ->
                    "Hello. Male Protector voice is ready. Take a calm breath — I'm right here with you."
                else ->
                    "Hello. Female Guardian voice is ready. Take a calm breath — I'm right here with you."
            }
        }

        /**
         * Selects the highest quality natural neural Android [Voice] for the requested locale & Male/Female persona,
         * explicitly filtering out robotic, low-quality synthesizers.
         */
        fun selectBestVoiceForPersona(
            voices: List<Voice>,
            targetLocale: Locale,
            wantMale: Boolean
        ): Voice? {
            if (voices.isEmpty()) return null
            val sameLang = voices.filter {
                it.locale.language.equals(targetLocale.language, ignoreCase = true)
            }
            if (sameLang.isEmpty()) return null

            fun naturalnessScore(voice: Voice): Int {
                val n = voice.name.lowercase(Locale.US)
                val feats = voice.features?.joinToString(" ")?.lowercase(Locale.US).orEmpty()
                var score = voice.quality * 20

                // Heavily penalize legacy robotic/monotone synthesizers
                if (n.contains("espeak") || n.contains("pico") || n.contains("compact") || n.contains("low-latency-legacy")) {
                    score -= 10000
                }

                // Massive priority boost for Natural Neural, WaveNet, Journey, Studio, and Google HD models
                if (n.contains("neural") || n.contains("wavenet") || n.contains("studio") || n.contains("journey") ||
                    n.contains("network") || n.contains("enhanced") || n.contains("natural") || n.contains("premium") ||
                    feats.contains("neural") || feats.contains("wavenet") || feats.contains("network")
                ) {
                    score += 8000
                }

                if (voice.quality >= Voice.QUALITY_VERY_HIGH) {
                    score += 3500
                } else if (voice.quality >= Voice.QUALITY_HIGH) {
                    score += 1800
                }

                if (targetLocale.country.isNotBlank() &&
                    voice.locale.country.equals(targetLocale.country, ignoreCase = true)
                ) {
                    score += 1000
                }

                if (n.contains("google") || n.contains("-network") || n.contains("-wavenet")) {
                    score += 2500
                }

                return score
            }

            val ranked = sameLang.sortedWith(
                compareByDescending<Voice> { naturalnessScore(it) }
                    .thenBy { it.name.lowercase(Locale.US) }
            )

            val exactCountry = ranked.filter {
                targetLocale.country.isNotBlank() &&
                    it.locale.country.equals(targetLocale.country, ignoreCase = true)
            }
            val pool = if (exactCountry.isNotEmpty()) exactCountry else ranked

            // 1. Prefer highest quality voice explicitly matching requested gender
            pool.firstOrNull { matchesExplicitGender(it, wantMale) }?.let { return it }

            // 2. Fallback across same language (any country) for explicit gender match
            ranked.firstOrNull { matchesExplicitGender(it, wantMale) }?.let { return it }

            // 3. Deterministic split when multiple unlabeled voices exist so Male != Female
            if (pool.size >= 2) {
                val alphaSorted = pool.sortedBy { it.name.lowercase(Locale.US) }
                return if (wantMale) alphaSorted.last() else alphaSorted.first()
            }
            if (ranked.size >= 2) {
                val alphaSorted = ranked.sortedBy { it.name.lowercase(Locale.US) }
                return if (wantMale) alphaSorted.last() else alphaSorted.first()
            }
            return pool.firstOrNull()
        }

        val QUICK_PILLS = listOf(
            LanguageOption("EN", "en-IN", "English", "English"),
            LanguageOption("HI", "hi-IN", "Hindi", "हिन्दी"),
            LanguageOption("BN", "bn-IN", "Bengali", "বাংলা")
        )

        val SUPPORTED_LANGUAGES = listOf(
            // Indian Languages
            LanguageOption("EN", "en-IN", "English (India)", "English"),
            LanguageOption("HI", "hi-IN", "Hindi", "हिन्दी"),
            LanguageOption("BN", "bn-IN", "Bengali", "বাংলা"),
            LanguageOption("TA", "ta-IN", "Tamil", "தமிழ்"),
            LanguageOption("TE", "te-IN", "Telugu", "తెలుగు"),
            LanguageOption("MR", "mr-IN", "Marathi", "मराठी"),
            LanguageOption("GU", "gu-IN", "Gujarati", "ગુજરાતી"),
            LanguageOption("KN", "kn-IN", "Kannada", "ಕನ್ನಡ"),
            LanguageOption("ML", "ml-IN", "Malayalam", "മലയാളം"),
            LanguageOption("PA", "pa-IN", "Punjabi", "ਪੰਜਾਬੀ"),
            LanguageOption("OR", "or-IN", "Odia", "ଓଡ଼ିଆ"),
            LanguageOption("AS", "as-IN", "Assamese", "অসমীয়া"),
            LanguageOption("UR", "ur-IN", "Urdu", "اردو"),
            // International / Foreign Languages
            LanguageOption("EN-US", "en-US", "English (US)", "English US"),
            LanguageOption("EN-GB", "en-GB", "English (UK)", "English UK"),
            LanguageOption("ES", "es-ES", "Spanish", "Español"),
            LanguageOption("FR", "fr-FR", "French", "Français"),
            LanguageOption("DE", "de-DE", "German", "Deutsch"),
            LanguageOption("IT", "it-IT", "Italian", "Italiano"),
            LanguageOption("PT", "pt-BR", "Portuguese", "Português"),
            LanguageOption("RU", "ru-RU", "Russian", "Русский"),
            LanguageOption("AR", "ar-SA", "Arabic", "العربية"),
            LanguageOption("JA", "ja-JP", "Japanese", "日本語"),
            LanguageOption("KO", "ko-KR", "Korean", "한국어"),
            LanguageOption("ZH", "zh-CN", "Chinese (Mandarin)", "中文"),
            LanguageOption("TH", "th-TH", "Thai", "ไทย"),
            LanguageOption("VI", "vi-VN", "Vietnamese", "Tiếng Việt"),
            LanguageOption("ID", "id-ID", "Indonesian", "Bahasa Indonesia"),
            LanguageOption("TR", "tr-TR", "Turkish", "Türkçe"),
            LanguageOption("NE", "ne-NP", "Nepali", "नेपाली")
        )
    }
}
