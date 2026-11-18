package ma.darija.voicebot.tts;

import ma.darija.voicebot.BotConfig;
import ma.darija.voicebot.audio.Mp3Decoder;

import java.io.IOException;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Picks the voice service, cleans the text and returns Discord-ready PCM. */
public class TextToSpeech {

    // Emojis, markdown symbols and links don't sound good when read out loud
    private static final Pattern EMOJI = Pattern.compile("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]");
    private static final Pattern MARKDOWN = Pattern.compile("[*_#`~>|]+");
    private static final Pattern LINK = Pattern.compile("https?://\\S+");

    private final BotConfig cfg;
    private final Logger log;
    private final EdgeTts edge;
    private final GoogleTts google = new GoogleTts();
    private final ElevenLabsTts eleven;
    private final GeminiTts gemini;

    public TextToSpeech(BotConfig cfg, Logger log) {
        this.cfg = cfg;
        this.log = log;
        this.edge = new EdgeTts(cfg.ttsVoice(), cfg.ttsRate(), cfg.ttsPitch());
        this.gemini = new GeminiTts(cfg.geminiKey(), cfg.geminiTtsModel(), cfg.geminiTtsVoice(), cfg.geminiTtsStyle());
        this.eleven = new ElevenLabsTts(cfg.elevenKey(), cfg.elevenVoiceId(), cfg.elevenModel(), cfg.elevenLanguage());
    }

    public static String clean(String text) {
        String t = LINK.matcher(text).replaceAll("");
        t = EMOJI.matcher(t).replaceAll("");
        t = MARKDOWN.matcher(t).replaceAll("");
        return t.replaceAll("\\s+", " ").trim();
    }

    /** Returns 48 kHz stereo PCM ready for Discord, or an empty array if there is nothing to say. */
    public byte[] speak(String text) throws IOException, InterruptedException {
        String clean = clean(text);
        if (clean.isEmpty()) return new byte[0];
        if (cfg.ttsProvider().equals("gemini")) {
            try {
                return gemini.synthesizeToDiscordPcm(clean);
            } catch (IOException e) {
                // busy / free limit reached -> keep talking with the Edge voice
                log.warning("Gemini voice failed, using Edge voice instead: " + e.getMessage());
                return Mp3Decoder.decodeToDiscordPcm(edgeOrGoogle(clean));
            }
        }
        byte[] mp3;
        switch (cfg.ttsProvider()) {
            case "google" -> mp3 = google.synthesize(clean);
            case "elevenlabs" -> {
                try {
                    mp3 = eleven.synthesize(clean);
                } catch (IOException e) {
                    // e.g. monthly free credits used up -> keep talking with the free Edge voice
                    log.warning("ElevenLabs voice failed, using Edge voice instead: " + e.getMessage());
                    mp3 = edgeOrGoogle(clean);
                }
            }
            default -> mp3 = edgeOrGoogle(clean);
        }
        return Mp3Decoder.decodeToDiscordPcm(mp3);
    }

    private byte[] edgeOrGoogle(String clean) throws IOException, InterruptedException {
        try {
            return edge.synthesize(clean);
        } catch (IOException e) {
            if (!cfg.ttsFallbackGoogle()) throw e;
            log.warning("Edge voice failed, using Google voice instead: " + e.getMessage());
            return google.synthesize(clean);
        }
    }
}
