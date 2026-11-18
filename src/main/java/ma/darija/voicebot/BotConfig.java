package ma.darija.voicebot;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/** All settings from config.yml, read once on (re)load. */
public record BotConfig(
        String discordToken,
        String guildId,
        String logChannelId,

        String groqKey,
        String sttModel,
        String sttLanguage,
        String sttPrompt,

        String aiProvider,
        String geminiKey,
        String geminiModel,
        String groqModel,
        String botName,
        int memoryMessages,
        String systemPrompt,

        String ttsProvider,
        String ttsVoice,
        String ttsRate,
        String ttsPitch,
        boolean ttsFallbackGoogle,
        String elevenKey,
        String elevenVoiceId,
        String elevenModel,
        String elevenLanguage,
        String geminiTtsModel,
        String geminiTtsVoice,
        String geminiTtsStyle,

        boolean requireWakeWord,
        List<String> wakeWords,
        int silenceMs,
        int minSpeechMs,
        int maxUtteranceSeconds,
        int speechThreshold,
        boolean ignoreWhileSpeaking,
        boolean autoLeaveWhenAlone,
        List<String> ignorePhrases,

        boolean broadcastToChat
) {

    public static BotConfig from(FileConfiguration c) {
        return new BotConfig(
                c.getString("discord.token", "").trim(),
                c.getString("discord.guild-id", "").trim(),
                c.getString("discord.log-channel-id", "").trim(),

                c.getString("speech-to-text.groq-api-key", "").trim(),
                c.getString("speech-to-text.model", "whisper-large-v3"),
                c.getString("speech-to-text.language", "ar").trim(),
                c.getString("speech-to-text.prompt", ""),

                c.getString("ai.provider", "gemini").trim().toLowerCase(),
                c.getString("ai.gemini-api-key", "").trim(),
                c.getString("ai.gemini-model", "gemini-flash-latest").trim(),
                c.getString("ai.groq-model", "llama-3.3-70b-versatile").trim(),
                c.getString("ai.bot-name", "Bot"),
                Math.max(0, c.getInt("ai.memory-messages", 12)),
                c.getString("ai.system-prompt", ""),

                c.getString("text-to-speech.provider", "edge").trim().toLowerCase(),
                c.getString("text-to-speech.voice", "ar-MA-JamalNeural").trim(),
                c.getString("text-to-speech.rate", "+0%").trim(),
                c.getString("text-to-speech.pitch", "+0Hz").trim(),
                c.getBoolean("text-to-speech.fallback-to-google", true),
                c.getString("text-to-speech.elevenlabs.api-key", "").trim(),
                c.getString("text-to-speech.elevenlabs.voice-id", "").trim(),
                c.getString("text-to-speech.elevenlabs.model", "eleven_v3").trim(),
                c.getString("text-to-speech.elevenlabs.language", "").trim(),
                c.getString("text-to-speech.gemini.model", "gemini-3.1-flash-tts-preview").trim(),
                c.getString("text-to-speech.gemini.voice", "Puck").trim(),
                c.getString("text-to-speech.gemini.style", ""),

                c.getBoolean("voice.require-wake-word", false),
                c.getStringList("voice.wake-words"),
                Math.max(200, c.getInt("voice.silence-ms", 900)),
                Math.max(0, c.getInt("voice.min-speech-ms", 500)),
                Math.max(3, c.getInt("voice.max-utterance-seconds", 25)),
                Math.max(0, c.getInt("voice.speech-threshold", 500)),
                c.getBoolean("voice.ignore-while-speaking", true),
                c.getBoolean("voice.auto-leave-when-alone", true),
                c.getStringList("voice.ignore-phrases"),

                c.getBoolean("minecraft.broadcast-to-chat", false)
        );
    }

    public static boolean isPlaceholder(String value) {
        return value == null || value.isBlank() || value.startsWith("PUT_YOUR_");
    }

    /** Returns a list of problems, empty if the config looks usable. */
    public List<String> problems() {
        java.util.ArrayList<String> p = new java.util.ArrayList<>();
        if (isPlaceholder(discordToken)) p.add("discord.token is not set");
        if (isPlaceholder(groqKey)) p.add("speech-to-text.groq-api-key is not set");
        if ((aiProvider.equals("gemini") || ttsProvider.equals("gemini")) && isPlaceholder(geminiKey)) p.add("ai.gemini-api-key is not set");
        if (!aiProvider.equals("gemini") && !aiProvider.equals("groq")) p.add("ai.provider must be gemini or groq");
        if (ttsProvider.equals("elevenlabs") && (isPlaceholder(elevenKey) || isPlaceholder(elevenVoiceId))) {
            p.add("text-to-speech.elevenlabs.api-key and voice-id must be set when provider is elevenlabs");
        }
        return p;
    }
}
