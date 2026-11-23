package com.example.data.ai

import android.util.Log
import com.example.util.AppCheckRuntimeState
import com.example.util.GuardianVoicePersona
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.content
import com.google.firebase.appcheck.FirebaseAppCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Locale

data class AiMessage(
    val id: String = System.currentTimeMillis().toString(),
    val sender: String, // "user", "assistant", "system"
    val text: String,
    val isEmergencyAlert: Boolean = false,
    val showSosAction: Boolean = false,
    val isError: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

sealed interface SurakshaAiUiState {
    data object Idle : SurakshaAiUiState
    data object Loading : SurakshaAiUiState
    data class Streaming(val partialText: String) : SurakshaAiUiState
    data class Error(val message: String, val failedQuery: String) : SurakshaAiUiState
}

sealed interface SurakshaStreamEvent {
    data class Partial(val accumulatedText: String) : SurakshaStreamEvent
    data class Completed(val message: AiMessage) : SurakshaStreamEvent
    data class Error(val errorMessage: String) : SurakshaStreamEvent
}

/**
 * Smart, Emotionally Intelligent Local Safety Brain (Zero-API-Key Offline / Fallback Companion):
 * Handles distress, anxiety/panic grounding, emotional companionship, stalking, cabs, indoor coercion,
 * medical first-aid, disasters, cyber threats, Zero-FIR rights, and post-safe check-ins without repeating canned text.
 */
object SurakshaContextBuilder {

    @JvmOverloads
    fun buildLocalResponse(
        query: String,
        primarySosNumber: String = "112",
        languageName: String = "English",
        runtimeContext: String = "",
        previousAssistantReplies: List<String> = emptyList(),
        persona: GuardianVoicePersona = GuardianVoicePersona.FEMALE
    ): String {
        val q = query.trim().lowercase(Locale.US)
        val lang = languageName.lowercase(Locale.US)
        val dialTarget = primarySosNumber.ifBlank { "112" }

        val isHindi = lang.contains("hindi") || lang == "hi"
        val isBengali = lang.contains("bengali") || lang.contains("bangla") || lang == "bn"
        val isSpanish = lang.contains("spanish") || lang.contains("español") || lang == "es"
        val isFrench = lang.contains("french") || lang.contains("français") || lang == "fr"
        val isArabic = lang.contains("arabic") || lang == "ar"

        val isSafeNow = q.contains("i am safe") || q.contains("i'm safe") || q.contains("reached home") ||
            q.contains("reached safely") || q.contains("false alarm") || q.contains("all good") ||
            q.contains("thank you") || q.contains("thanks") || q.contains("सुरक्षित हूँ") || q.contains("নিরাপদ")
        val isEmotionalPanic = q.contains("scared") || q.contains("afraid") || q.contains("panic") ||
            q.contains("anxious") || q.contains("anxiety") || q.contains("crying") || q.contains("nervous") ||
            q.contains("calm me") || q.contains("reassure") || q.contains("breathe") || q.contains("shaking") ||
            q.contains("lonely") || q.contains("fear") || q.contains("डर") || q.contains("घबरा") ||
            q.contains("ভয়") || q.contains("চিন্তা")
        val isCompanionship = q.contains("talk to me") || q.contains("stay with me") || q.contains("are you there") ||
            q.contains("walk with me") || q.contains("keep talking") || q.contains("hello") || q.contains("hi ") ||
            q == "hi" || q == "hey"
        val isFirstAid = q.contains("first aid") || q.contains("bleed") || q.contains("cpr") ||
            q.contains("injur") || q.contains("burn") || q.contains("faint") || q.contains("chok") ||
            q.contains("chest pain") || q.contains("fracture") || q.contains("accident") ||
            q.contains("रक्त") || q.contains("चोट") || q.contains("प्राथमिक") ||
            q.contains("রক্ত") || q.contains("প্রাথমিক")
        val isLegal = q.contains("zero-fir") || q.contains("zero fir") || Regex("\\bfir\\b").containsMatchIn(q) ||
            q.contains("legal") || q.contains("rights") || q.contains("law") || q.contains("police complaint") ||
            q.contains("अधिकार") || q.contains("আইনি")
        val isDomesticOrIndoor = q.contains("inside my house") || q.contains("at home") || q.contains("roommate") ||
            q.contains("husband") || q.contains("partner") || q.contains("locked inside") || q.contains("someone is outside") ||
            q.contains("door") || q.contains("coerce") || q.contains("harass")
        val isCab = q.contains("cab") || q.contains("taxi") || q.contains("auto") || q.contains("uber") ||
            q.contains("ola") || q.contains("driver") || q.contains("plate") || q.contains("trip") ||
            q.contains("bus") || q.contains("train") || q.contains("route") ||
            q.contains("कैब") || q.contains("टैक्सी") || q.contains("ক্যাব") || q.contains("ট্যাক্সি")
        val isStalk = q.contains("follow") || q.contains("stalk") || q.contains("chased") ||
            q.contains("trailing") || q.contains("creepy") || q.contains("someone behind") ||
            q.contains("night") || q.contains("alone") || q.contains("पीछा") || q.contains("পিছু")
        val isCyber = q.contains("blackmail") || q.contains("scam") || q.contains("extort") ||
            q.contains("hack") || q.contains("online") || q.contains("photo leak") || q.contains("stalking online")
        val isHazardOrStranded = q.contains("fire") || q.contains("smoke") || q.contains("flood") ||
            q.contains("earthquake") || q.contains("lost") || q.contains("stranded") || q.contains("battery") ||
            q.contains("dark") || q.contains("power cut")
        val isFeatures = q.contains("rakshaone") || q.contains("feature") || q.contains("what can you do") ||
            q.contains("how does this app")

        fun pickNonRepeating(variants: List<String>): String {
            val recent = previousAssistantReplies.takeLast(3).map { it.trim() }.toSet()
            return variants.firstOrNull { candidate ->
                recent.none { prev -> prev == candidate.trim() || prev.take(65) == candidate.trim().take(65) }
            } ?: variants[previousAssistantReplies.size % variants.size]
        }

        if (isHindi) {
            return when {
                isSafeNow -> pickNonRepeating(
                    listOf(
                        "यह सुनकर बहुत अच्छा लगा कि आप सुरक्षित हैं। अब थोड़ा आराम से पानी पी लें और गहरी साँस लें। जब भी ज़रूरत हो, मैं यहीं हूँ।",
                        "शुक्र है आप ठीक हैं। अगर आपने पहले कोई अलर्ट भेजा था, तो परिवार को छोटा सा 'मैं सुरक्षित पहुँच गया/गई हूँ' संदेश भेज दें।"
                    )
                )
                isEmotionalPanic -> pickNonRepeating(
                    listOf(
                        "गहरी साँस लें — मैं अभी आपके साथ हूँ। आप अकेले नहीं हैं। 4 सेकंड साँस अंदर लें, 4 सेकंड रोकें, और धीरे से छोड़ें। आस-पास कोई रोशनी वाली दुकान या सुरक्षित जगह दिख रही है तो उधर बढ़ें।",
                        "मैं सुन रहा हूँ, घबराएँ नहीं। अभी सबसे पहले किसी सुरक्षित व्यक्ति या भीड़-भाड़ वाले स्थान के पास रहें। चाहें तो Fake Call चालू कर लें ताकि लगे कि कोई आपसे लगातार बात कर रहा है, या ज़रूरत पड़ने पर $dialTarget पर कॉल करें।"
                    )
                )
                isFirstAid -> "[Hindi Mode] सुरक्षा AI चिकित्सा सहायता:\n" +
                    "1. गंभीर चोट या अधिक रक्तस्राव होने पर तुरंत $dialTarget पर कॉल करें।\n" +
                    "2. साफ कपड़े से घाव पर लगातार सीधा दबाव बनाए रखें।\n" +
                    "3. जलने पर 20 मिनट ठंडे साफ पानी से धोएं (बर्फ या टूथपेस्ट न लगाएं)।\n" +
                    "4. बेहोश लेकिन सांस ले रहे व्यक्ति को करवट से लिटाएं।"
                isLegal -> "[Hindi Mode] सुरक्षा AI कानूनी मार्गदर्शन:\n" +
                    "1. Zero-FIR (ज़ीरो-FIR) अधिकार (BNSS 173 / CrPC 154): किसी भी थाने में आपकी शिकायत दर्ज करना अनिवार्य है, चाहे घटना कहीं भी हुई हो।\n" +
                    "2. FIR की निःशुल्क हस्ताक्षरित प्रति अवश्य मांगें।\n" +
                    "3. Reports टैब से सत्यापित GPS, ऑडियो और वीडियो टाइमलाइन सुरक्षित रखें।"
                isCab -> pickNonRepeating(
                    listOf(
                        "सफ़र में सतर्क रहना बहुत सही है। बैठने से पहले गाड़ी का नंबर नोट या स्कैन करके परिवार को भेजें, और Trips में लाइव मॉनिटरिंग चालू रखें।",
                        "अगर ड्राइवर रास्ता बदले या बात न माने, तो तुरंत Fake Call दबाएँ ताकि ऐसा लगे कि परिवार आपकी लाइव लोकेशन देख रहा है, या भीड़-भाड़ वाली जगह पर उतर जाएँ।"
                    )
                )
                isStalk -> pickNonRepeating(
                    listOf(
                        "सुनें — सीधे किसी बंद गली या अकेले घर की तरफ न जाएँ। अभी किसी रोशनी वाली खुली दुकान, एटीएम गार्ड या मेट्रो स्टेशन की तरफ मुड़ें। चाहें तो अभी Fake Call चालू कर लें, और खतरा बढ़ने पर तुरंत $dialTarget डायल करें।",
                        "मैं आपके साथ हूँ। अपनी चाल सामान्य रखें, किसी भीड़ वाली जगह में प्रवेश करें और 4 बार Volume बटन या SOS से तुरंत परिवार को अलर्ट भेजें।"
                    )
                )
                else -> pickNonRepeating(
                    listOf(
                        "मैं आपकी बात सुन रहा हूँ। आप मुझे खुलकर बता सकते हैं कि अभी आपके आस-पास क्या हो रहा है — चाहे मन शांत करना हो, सफ़र की सुरक्षा देखनी हो या तुरंत मदद चाहिए हो।",
                        "आप सुरक्षित रहें, यही सबसे ज़रूरी है। अगर थोड़ा भी असहज लग रहा हो तो रोशनी वाली भीड़-भाड़ वाली जगह पर रहें और ज़रूरत पड़ने पर SOS या $dialTarget इस्तेमाल करें।"
                    )
                )
            }
        }

        if (isBengali) {
            return when {
                isSafeNow -> pickNonRepeating(
                    listOf(
                        "আপনি নিরাপদে আছেন শুনে খুব ভালো লাগলো। একটু জল খেয়ে শান্ত হয়ে নিন — প্রয়োজনে আমি সবসময় এখানেই আছি।",
                        "খুব স্বস্তি পেলাম। পরিবারের কাউকে একটা ছোট মেসেজ দিয়ে জানিয়ে দিন যে আপনি ঠিক আছেন।"
                    )
                )
                isEmotionalPanic -> pickNonRepeating(
                    listOf(
                        "শান্ত হোন, আমি আপনার সাথেই আছি। একসাথে ধীরে ধীরে ৪ সেকেন্ড শ্বাস নিন আর ছাড়ুন। আশেপাশে কোনো আলোকিত দোকান বা মানুষের ভিড় থাকলে সেখানে গিয়ে দাঁড়ান।",
                        "ভয় পাবেন না — আপনি একা নন। চাইলে এখনই Fake Call চালু করে কথা বলার ভান করতে পারেন, অথবা জরুরি হলে $dialTarget নম্বরে কল করুন।"
                    )
                )
                isFirstAid -> "[Bengali Mode] সুরক্ষা AI জরুরি প্রাথমিক চিকিৎসা:\n" +
                    "1. গুরুতর আঘাত বা অতিরিক্ত রক্তপাতের ক্ষেত্রে অবিলম্বে $dialTarget নম্বরে কল করুন।\n" +
                    "2. পরিষ্কার কাপড় দিয়ে ক্ষতস্থানে একটানা চাপ দিয়ে ধরে রাখুন।\n" +
                    "3. পুড়ে গেলে ২০ মিনিট পরিষ্কার ঠান্ডা জল ঢালুন (বরফ বা টুথপেস্ট দেবেন না)।"
                isLegal -> "[Bengali Mode] সুরক্ষা AI আইনি অধিকার:\n" +
                    "1. জিরো-FIR (BNSS 173): ঘটনাস্থল যেখানেই হোক, যেকোনো থানায় অভিযোগ নেওয়া বাধ্যতামূলক।\n" +
                    "2. স্বাক্ষরিত FIR কপি বিনামূল্যে সংগ্রহ করুন এবং Reports থেকে প্রমাণ সংরক্ষণ করুন।"
                isCab -> "গাড়িতে ওঠার আগে নম্বর প্লেট স্ক্যান করে পরিবারকে পাঠান এবং Trips চালু রাখুন। চালক অন্য পথে গেলে নির্দ্বিধায় ভিড় এলাকায় নেমে পড়ুন বা Fake Call ব্যবহার করুন।"
                isStalk -> pickNonRepeating(
                    listOf(
                        "সুরক্ষা AI: এখনই নির্জন রাস্তা বা একা বাড়ির দিকে যাবেন না। কাছাকাছি কোনো খোলা দোকান, এটিএম গার্ড বা জনবহুল জায়গায় যান। প্রয়োজনে Fake Call বা $dialTarget ব্যবহার করুন।",
                        "সুরক্ষা AI পাশে আছে — ব্যস্ত রাস্তায় থাকুন, ফোন হাতে প্রস্তুত রাখুন এবং বিপদ বাড়লে লাল SOS চাপুন।"
                    )
                )
                else -> pickNonRepeating(
                    listOf(
                        "সুরক্ষা AI শুনছি। আপনার চারপাশে কী হচ্ছে খুলে বলুন — শান্ত থাকতে সাহায্য করা থেকে শুরু করে জরুরি পদক্ষেপ, সব কিছুতে পাশে আছি।",
                        "আতঙ্কিত হবেন না। আলোকিত জনবহুল জায়গায় থাকার চেষ্টা করুন, আর কোনো ঝুঁকি দেখলে SOS বা $dialTarget কল করুন।"
                    )
                )
            }
        }

        if (isSpanish) {
            return pickNonRepeating(
                listOf(
                    "Estoy aquí contigo. Respira despacio: si percibes peligro inmediato, pulsa SOS o llama al $dialTarget y acércate a un lugar público con luz y gente.",
                    "No estás solo. Activa Llamada Falsa o comparte tu ubicación en vivo con tu círculo de confianza mientras buscas un sitio seguro."
                )
            )
        }

        if (isFrench) {
            return pickNonRepeating(
                listOf(
                    "Je suis là avec toi. Respire calmement : en cas de danger immédiat, appuie sur SOS ou appelle le $dialTarget et rejoins un lieu public éclairé.",
                    "Reste connecté. Utilise Faux Appel ou partage ta position en direct avec ta famille pendant que tu te mets en sécurité."
                )
            )
        }

        if (isArabic) {
            return pickNonRepeating(
                listOf(
                    "أنا معك الآن. تنفس بهدوء — إذا كان هناك خطر فوري، اضغط SOS أو اتصل بالرقم $dialTarget وتوجه إلى مكان عام مضاء ومزدحم.",
                    "ابقَ في مكان آمن ومضاء وشارك موقعك المباشر مع عائلتك أو فعّل المكالمة الوهمية فوراً."
                )
            )
        }

        // English — empathetic, warm, human & situation-aware
        val supporterTone = if (persona == GuardianVoicePersona.MALE) {
            "I'm right here with you — steady and listening."
        } else {
            "I'm right here with you. You don't have to handle this alone."
        }

        return when {
            isSafeNow -> pickNonRepeating(
                listOf(
                    "That's such a relief to hear. Take a moment to catch your breath, drink some water, and lock the door behind you. If you had any active location alert running, let your trusted circle know you're safe now.",
                    "Glad you made it through safely. You handled that well — rest up for a few minutes, and I'm right here whenever you want to check in again."
                )
            )
            isEmotionalPanic -> pickNonRepeating(
                listOf(
                    "$supporterTone Take one slow breath with me: inhale for 4 seconds, hold for 4, exhale slowly for 6. You're going to get through this moment. Tell me in one line — are you indoors, walking, or in a vehicle right now? If anything feels unsafe around you, Fake Call Shield or $dialTarget is one tap away.",
                    "Stay focused on my voice for a few seconds — you are not alone right now. Relax your shoulders, scan for the nearest bright shop, security desk, or trusted person, and keep moving gently toward safety. What's happening around you right this second?"
                )
            )
            isCompanionship -> pickNonRepeating(
                listOf(
                    "$supporterTone Tell me where you're heading or what's on your mind, and I'll stay right with you step by step.",
                    "Right here with you. Keep your head up, stay on well-lit paths where you can see ahead clearly, and talk to me for as long as you need."
                )
            )
            isDomesticOrIndoor -> pickNonRepeating(
                listOf(
                    "Stay as quiet and calm as possible. Move toward a room with an exit or solid lock if you can, and keep your phone muted — if someone forces you to stop an SOS alert, Decoy PIN 0000 hides the screen while keeping your guardians updated. Need silent protection? Tap Fake Call or Silent SOS anytime, or dial $dialTarget when safe.",
                    "Your safety comes first. Don't escalate an argument indoors — create physical distance near a doorway or window, silently share audio/GPS via Fake Call Guard or Silent SOS, and call $dialTarget as soon as you have a safe moment."
                )
            )
            isFirstAid -> pickNonRepeating(
                listOf(
                    "Focus on the basics first — call $dialTarget right away if anyone is unconscious, struggling to breathe, or bleeding heavily.\n• Heavy bleeding: apply firm direct pressure continuously with a clean cloth (elevate if limb).\n• Burns: run cool clean water for 20 minutes — never apply ice, butter, or toothpaste.\n• Fainting/unconscious but breathing: gently turn them onto their side recovery position.",
                    "Emergency care guide (dial $dialTarget immediately for severe trauma):\n• Choking & unable to cough: alternate 5 firm back blows and 5 upward abdominal thrusts.\n• Chest pain or sudden dizziness: have the person sit upright at rest and loosen tight collars while waiting for medical help.\nTell me the exact symptom and I'll narrow down the exact next step."
                )
            )
            isLegal -> pickNonRepeating(
                listOf(
                    "Here are your key Zero-FIR rights:\n1. Zero-FIR Mandate (Section 173 BNSS / CrPC 154): Any police station must register your complaint immediately regardless of where the incident took place.\n2. Free Signed Copy: You are entitled to a free copy of the recorded FIR right away.\n3. Chain of Custody: Open Reports -> Zero-FIR Timeline in RakshaOne to share time-stamped GPS, audio, photos, and video safely.",
                    "Important legal protection tips: never let anyone pressure you into dropping a complaint on jurisdiction grounds — insist on Zero-FIR registration, request a female officer if preferred, and keep medical/MLC reports plus your exported RakshaOne evidence timeline together."
                )
            )
            isCab -> pickNonRepeating(
                listOf(
                    "Good thinking checking in during your ride. Snap or log the vehicle plate using Cab Plate Scan so your family has the registration number, switch on Trip Monitoring, and check child locks near your door. If the driver takes an unexpected turn, trigger Fake Call Shield — bystanders and drivers immediately know someone is tracking your live GPS.",
                    "Stay alert in the back seat behind the passenger side when possible so you can see the driver and exit quickly. Don't disclose personal details, keep your route visible on Maps, and use Silent SOS or $dialTarget if the driver refuses to let you out at a busy illuminated spot."
                )
            )
            isStalk -> pickNonRepeating(
                listOf(
                    "Listen carefully — do not walk straight into an isolated lane or your empty home doorway if someone may be trailing you. Cross into a bright open store, pharmacy, ATM kiosk with a guard, or metro concourse. Trigger Fake Call Shield so it looks like someone is meeting you in two minutes, and keep $dialTarget or SOS ready if they follow you inside.",
                    "Stay steady and break line-of-sight inside a busy public place first. Speak to staff at the counter and let them know someone is following you. You can press 4x Volume or trigger SOS/Fake Call discreetly while keeping your distance."
                )
            )
            isCyber -> pickNonRepeating(
                listOf(
                    "Do not panic, delete anything, or pay money — blackmailers almost always ask for more if paid once. Take full screenshots (including URLs, usernames, timestamps, and payment handles), block further contact without replying, and report immediately via cybercrime.gov.in or helpline 1930 (or $dialTarget if physical threats are involved).",
                    "Protect your accounts right away: change passwords, enable 2-step verification, and preserve every message or call log untouched as digital evidence."
                )
            )
            isHazardOrStranded -> pickNonRepeating(
                listOf(
                    "Conserve your battery immediately: lower screen brightness, close background apps, and send one crisp pin + battery percentage to your Trusted Circle before power drops further. For fire or thick smoke, stay low to the floor and take stairs rather than lifts; if stranded outdoors at night, wait near lit security cameras or transit staff and dial $dialTarget if needed.",
                    "Even without internet, SOS SMS continues working over your carrier network. Pick a visible landmark around you, share it clearly with one family member, and stay put in a safe, bright area rather than wandering in the dark."
                )
            )
            isFeatures ->
                "Here is what I can protect you with inside RakshaOne:\n• Proactive TFLite Safety Brain & Pixel Fake Call (auto-records audio and alerts your circle discreetly).\n• Zero-interference Auto-Vault SOS (dual camera snapshots, encrypted audio, video, SMS & cloud push).\n• Decoy Cancel PIN (0000) to disguise SOS under coercion.\n• Cab Plate Scanner, Trip Deviation Guard, Good Citizen Mesh & Zero-FIR Timeline export."
            else -> pickNonRepeating(
                listOf(
                    "$supporterTone Tell me what's going on around you right now — whether you want company while walking, need help deciding what to do in an awkward situation, or want emergency $dialTarget / first-aid steps.",
                    "I'm listening closely. Give me a quick detail — where you are or what made you reach out — and I'll tailor the smartest next steps for you right away."
                )
            )
        }
    }
}

class AiService {
    private val tag = "AiService"

    private val systemPrompt = content {
        text(
            "You are Suraksha, the deeply empathetic, calm, sharp-minded personal safety companion inside the RakshaOne Android app. " +
                "Write like a warm, steady, trustworthy human protector — never sound robotic, scripted, or clinical.\n\n" +
                "STRICT CONVERSATION RULES:\n" +
                "1. ANTI-REPETITION: Read the conversation history carefully before writing a single word. NEVER repeat earlier greetings, headers, boilerplate tips, or phrases you already said in previous turns. Progress the conversation naturally based on what the user just told you.\n" +
                "2. EMOTIONAL ASSURANCE FIRST: If the user sounds scared, anxious, shaken, panicking, or just wants someone with them, immediately offer warm, calming reassurance and grounding ('I'm right here with you...') BEFORE giving clear, practical steps tailored to their exact situation.\n" +
                "3. ADAPT TO EVERY SITUATION: Handle late-night walks, being followed, suspicious cabs/drivers, indoor/domestic coercion, medical first-aid/accidents, cyber blackmail/scams, natural hazards/low battery, emotional distress, Zero-FIR rights, AND relieved/casual check-ins ('I'm safe now', 'Thank you'). Don't force emergency lectures into calm check-ins.\n" +
                "4. NO DEBUG TELEMETRY ECHO: The prompt begins with a silent runtime note `[SILENT_CONTEXT: ...]`. Use it silently to stay aware of language, persona, locality, and threat level, and NEVER repeat that bracketed block or debug fields (`ThreatScore`, `SilentMode`, `TargetLanguage`) in your reply.\n" +
                "5. REAL ACTION HONESTY: Never claim you dialed 112/911 yourself — remind the user to tap SOS, Fake Call Shield, or dial the local emergency number if in immediate physical danger.\n" +
                "6. LANGUAGE & FORMAT: Respond strictly in the user's TargetLanguage using natural conversational formatting (2–5 concise sentences or crisp bullets when tactical steps help)."
        )
    }

    // Zero-API-key Firebase AI Logic models (`gemini-3.8-flash` primary, `gemini-3.5-flash-lite` fallback)
    private val primaryModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            systemInstruction = systemPrompt
        )
    }

    private val fallbackModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-flash-lite",
            systemInstruction = systemPrompt
        )
    }

    private val emergencyKeywords = listOf(
        "threatening", "threat", "following me", "follow me", "stalking", "stalker",
        "attack", "attacker", "kidnap", "in danger", "help me", "emergency", "danger",
        "weapon", "knife", "gun", "scared", "someone is outside", "being chased", "hurt",
        "पीछा", "खतरा", "बचाओ", "মদত", "সাহায্য", "বিপদ", "পিছু"
    )

    fun isEmergencyIntent(query: String): Boolean {
        val lower = query.lowercase(Locale.US)
        return emergencyKeywords.any { lower.contains(it) }
    }

    private fun isAppCheckFailure(error: Throwable): Boolean {
        val msg = generateSequence(error) { it.cause }
            .mapNotNull { it.localizedMessage ?: it.message }
            .joinToString(" | ")
        return msg.contains("App Check", ignoreCase = true) ||
            msg.contains("token is invalid", ignoreCase = true) ||
            msg.contains("403", ignoreCase = true) ||
            msg.contains("attestation", ignoreCase = true)
    }

    private suspend fun forceRefreshAppCheckToken(): Boolean {
        return try {
            val tokenResult = withTimeout(8_000L) {
                FirebaseAppCheck.getInstance().getAppCheckToken(true).await()
            }
            AppCheckRuntimeState.lastTokenError = null
            tokenResult.token.isNotBlank()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppCheckRuntimeState.lastTokenError = e.localizedMessage
            false
        }
    }

    private fun buildChatHistory(history: List<AiMessage>): List<Content> {
        val mapped = history
            .filter { !it.isError && it.text.isNotBlank() && (it.sender == "user" || it.sender == "assistant") }
            .takeLast(14)
            .map { msg ->
                val role = if (msg.sender == "user") "user" else "model"
                content(role) { text(msg.text) }
            }
            .toMutableList()

        while (mapped.isNotEmpty() && mapped.first().role != "user") {
            mapped.removeAt(0)
        }
        val valid = mutableListOf<Content>()
        for (item in mapped) {
            if (valid.isEmpty() || valid.last().role != item.role) {
                valid.add(item)
            }
        }
        if (valid.lastOrNull()?.role == "user") {
            valid.removeAt(valid.lastIndex)
        }
        return valid
    }

    private fun cleanGeneratedReply(raw: String): String {
        return raw
            .replace(Regex("\\[SILENT_CONTEXT:[^\\]]*]"), "")
            .replace(Regex("\\[TargetLanguage:[^\\]]*]"), "")
            .trim()
    }

    @JvmOverloads
    fun streamAiResponse(
        userQuery: String,
        conversationHistory: List<AiMessage>,
        primarySosNumber: String = "112",
        runtimeContext: String = "",
        languageName: String = "English",
        persona: GuardianVoicePersona = GuardianVoicePersona.FEMALE
    ): Flow<SurakshaStreamEvent> = flow {
        val dialTarget = primarySosNumber.ifBlank { "112" }
        val isUrgent = isEmergencyIntent(userQuery)
        val priorAssistantReplies = conversationHistory
            .filter { it.sender == "assistant" && !it.isError }
            .map { it.text }

        val chatHistory = buildChatHistory(conversationHistory)
        val personaLabel = if (persona == GuardianVoicePersona.MALE) "Male Protector" else "Female Guardian"
        val silentFrame = buildString {
            append("[SILENT_CONTEXT: TargetLanguage=$languageName; EmergencyHotline=$dialTarget; CompanionPersona=$personaLabel; UrgentFlag=$isUrgent")
            if (runtimeContext.isNotBlank()) {
                append("; $runtimeContext")
            }
            append("]")
        }
        val promptWithContext = "$silentFrame\n$userQuery"

        suspend fun collectFromModel(
            useFallbackModel: Boolean,
            accumulated: StringBuilder
        ) {
            val model = if (useFallbackModel) fallbackModel else primaryModel
            withTimeout(45_000L) {
                val chat = model.startChat(history = chatHistory)
                chat.sendMessageStream(promptWithContext).collect { chunk ->
                    val chunkText = chunk.text.orEmpty()
                    if (chunkText.isNotEmpty()) {
                        accumulated.append(chunkText)
                        val cleanedPartial = cleanGeneratedReply(accumulated.toString())
                        if (cleanedPartial.isNotEmpty()) {
                            emit(SurakshaStreamEvent.Partial(cleanedPartial))
                        }
                    }
                }
            }
        }

        try {
            val accumulated = StringBuilder()
            try {
                collectFromModel(useFallbackModel = false, accumulated = accumulated)
            } catch (e: CancellationException) {
                throw e
            } catch (primaryEx: Exception) {
                if (accumulated.isNotEmpty()) throw primaryEx

                if (isAppCheckFailure(primaryEx)) {
                    val refreshed = forceRefreshAppCheckToken()
                    if (refreshed) {
                        try {
                            collectFromModel(useFallbackModel = false, accumulated = accumulated)
                        } catch (_: Exception) {
                            collectFromModel(useFallbackModel = true, accumulated = accumulated)
                        }
                    } else {
                        throw primaryEx
                    }
                } else {
                    collectFromModel(useFallbackModel = true, accumulated = accumulated)
                }
            }

            val finalText = cleanGeneratedReply(accumulated.toString())
            if (finalText.isNotBlank()) {
                emit(
                    SurakshaStreamEvent.Completed(
                        AiMessage(
                            sender = "assistant",
                            text = finalText,
                            isEmergencyAlert = isUrgent,
                            showSosAction = isUrgent
                        )
                    )
                )
            } else {
                val offlineText = SurakshaContextBuilder.buildLocalResponse(
                    query = userQuery,
                    primarySosNumber = dialTarget,
                    languageName = languageName,
                    runtimeContext = runtimeContext,
                    previousAssistantReplies = priorAssistantReplies,
                    persona = persona
                )
                emit(
                    SurakshaStreamEvent.Completed(
                        AiMessage(
                            sender = "assistant",
                            text = offlineText,
                            isEmergencyAlert = isUrgent,
                            showSosAction = isUrgent
                        )
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: TimeoutCancellationException) {
            Log.w(tag, "Cloud AI timed out — falling back to Smart Local Companion")
            val offlineText = SurakshaContextBuilder.buildLocalResponse(
                query = userQuery,
                primarySosNumber = dialTarget,
                languageName = languageName,
                runtimeContext = runtimeContext,
                previousAssistantReplies = priorAssistantReplies,
                persona = persona
            )
            emit(
                SurakshaStreamEvent.Completed(
                    AiMessage(
                        sender = "assistant",
                        text = offlineText,
                        isEmergencyAlert = isUrgent,
                        showSosAction = isUrgent
                    )
                )
            )
        } catch (e: Exception) {
            Log.w(tag, "Cloud AI unavailable (${e.localizedMessage}) — seamlessly using Smart Local Companion")
            val offlineText = SurakshaContextBuilder.buildLocalResponse(
                query = userQuery,
                primarySosNumber = dialTarget,
                languageName = languageName,
                runtimeContext = runtimeContext,
                previousAssistantReplies = priorAssistantReplies,
                persona = persona
            )
            emit(
                SurakshaStreamEvent.Completed(
                    AiMessage(
                        sender = "assistant",
                        text = offlineText,
                        isEmergencyAlert = isUrgent,
                        showSosAction = isUrgent
                    )
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    @JvmOverloads
    suspend fun getAiResponse(
        userQuery: String,
        primarySosNumber: String = "112",
        languageName: String = "English",
        runtimeContext: String = "",
        persona: GuardianVoicePersona = GuardianVoicePersona.FEMALE
    ): AiMessage = withContext(Dispatchers.IO) {
        val dialTarget = primarySosNumber.ifBlank { "112" }
        val isUrgent = isEmergencyIntent(userQuery)
        val personaLabel = if (persona == GuardianVoicePersona.MALE) "Male Protector" else "Female Guardian"
        val fullPrompt = "[SILENT_CONTEXT: TargetLanguage=$languageName; EmergencyHotline=$dialTarget; CompanionPersona=$personaLabel; UrgentFlag=$isUrgent; $runtimeContext]\n$userQuery"
        try {
            val result = withTimeout(45_000L) {
                try {
                    primaryModel.generateContent(fullPrompt)
                } catch (e: CancellationException) {
                    throw e
                } catch (primaryEx: Exception) {
                    if (isAppCheckFailure(primaryEx) && forceRefreshAppCheckToken()) {
                        primaryModel.generateContent(fullPrompt)
                    } else if (!isAppCheckFailure(primaryEx)) {
                        fallbackModel.generateContent(fullPrompt)
                    } else {
                        throw primaryEx
                    }
                }
            }
            val reply = cleanGeneratedReply(result.text.orEmpty())
            if (reply.isNotBlank()) {
                AiMessage(
                    sender = "assistant",
                    text = reply,
                    isEmergencyAlert = isUrgent,
                    showSosAction = isUrgent
                )
            } else {
                AiMessage(
                    sender = "assistant",
                    text = SurakshaContextBuilder.buildLocalResponse(
                        query = userQuery,
                        primarySosNumber = dialTarget,
                        languageName = languageName,
                        runtimeContext = runtimeContext,
                        persona = persona
                    ),
                    isEmergencyAlert = isUrgent,
                    showSosAction = isUrgent
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            AiMessage(
                sender = "assistant",
                text = SurakshaContextBuilder.buildLocalResponse(
                    query = userQuery,
                    primarySosNumber = dialTarget,
                    languageName = languageName,
                    runtimeContext = runtimeContext,
                    persona = persona
                ),
                isEmergencyAlert = isUrgent,
                showSosAction = isUrgent
            )
        }
    }
}
