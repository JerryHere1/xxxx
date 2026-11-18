package ma.darija.voicebot.discord;

import ma.darija.voicebot.BotConfig;
import ma.darija.voicebot.ai.ChatBrain;
import ma.darija.voicebot.ai.SpeechToText;
import ma.darija.voicebot.audio.AudioUtil;
import ma.darija.voicebot.audio.ListenerHandler;
import ma.darija.voicebot.audio.SpeakerHandler;
import ma.darija.voicebot.tts.TextToSpeech;
import net.dv8tion.jda.api.entities.User;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Everything for one Discord server's voice connection: ears, brain, mouth. */
public class VoiceSession {

    private final long guildId;
    private final BotConfig cfg;
    private final DiscordBot bot;
    private final SpeechToText stt;
    private final TextToSpeech tts;
    private final ChatBrain brain;
    private final SpeakerHandler speaker = new SpeakerHandler();
    private final ListenerHandler listener;
    /** One job at a time, max 3 waiting, so the bot never falls far behind the conversation. */
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(3), r -> {
        Thread t = new Thread(r, "DarijaBot-Brain");
        t.setDaemon(true);
        return t;
    });

    public VoiceSession(long guildId, BotConfig cfg, DiscordBot bot) {
        this.guildId = guildId;
        this.cfg = cfg;
        this.bot = bot;
        this.stt = new SpeechToText(cfg);
        this.tts = new TextToSpeech(cfg, bot.log());
        this.brain = new ChatBrain(cfg);
        this.listener = new ListenerHandler(this::onUtterance,
                () -> cfg.ignoreWhileSpeaking() && speaker.isSpeaking(),
                cfg.silenceMs(), cfg.minSpeechMs(), cfg.maxUtteranceSeconds(), cfg.speechThreshold());
    }

    public SpeakerHandler speaker() {
        return speaker;
    }

    public ListenerHandler listener() {
        return listener;
    }

    public void resetMemory() {
        brain.reset();
    }

    // ------------------------------------------------------------------ voice -> answer

    private void onUtterance(User user, byte[] pcm) {
        String name = user.getEffectiveName();
        submit(() -> {
            try {
                long t0 = System.currentTimeMillis();
                String heard = stt.transcribe(AudioUtil.discordPcmToWav16kMono(pcm));
                if (!shouldAnswer(heard)) return;
                String answer = brain.reply(name, stripWakeWord(heard));
                long t1 = System.currentTimeMillis();
                byte[] audio = tts.speak(answer);
                speaker.enqueue(audio);
                bot.log().info("[" + name + "] " + heard + "  ->  " + answer
                        + "  (" + (t1 - t0) + " ms think, " + (System.currentTimeMillis() - t1) + " ms voice)");
                bot.report(guildId, name, heard, answer);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                bot.log().warning("Voice reply failed: " + e.getMessage());
            }
        });
    }

    private boolean shouldAnswer(String heard) {
        if (heard == null) return false;
        String t = heard.trim();
        if (t.length() < 2) return false;
        String low = t.toLowerCase(Locale.ROOT);
        for (String p : cfg.ignorePhrases()) {
            if (!p.isBlank() && low.contains(p.toLowerCase(Locale.ROOT))) return false;
        }
        if (cfg.requireWakeWord()) {
            for (String w : cfg.wakeWords()) {
                if (!w.isBlank() && low.contains(w.toLowerCase(Locale.ROOT))) return true;
            }
            return false;
        }
        return true;
    }

    private String stripWakeWord(String heard) {
        if (!cfg.requireWakeWord()) return heard;
        String out = heard;
        for (String w : cfg.wakeWords()) {
            if (!w.isBlank()) out = out.replaceAll("(?iu)" + java.util.regex.Pattern.quote(w), " ");
        }
        out = out.replaceAll("\\s+", " ").trim();
        return out.isEmpty() ? heard : out;
    }

    // ------------------------------------------------------------------ commands

    /** Read text out loud exactly as written. */
    public void say(String text, Consumer<String> onError) {
        if (!submit(() -> {
            try {
                speaker.enqueue(tts.speak(text));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                bot.log().warning("Say failed: " + e.getMessage());
                onError.accept(e.getMessage());
            }
        })) onError.accept("The bot is busy, try again in a few seconds.");
    }

    /** Ask the AI by text; the answer is returned AND spoken. */
    public void ask(String name, String question, Consumer<String> onAnswer, Consumer<String> onError) {
        if (!submit(() -> {
            try {
                String answer = brain.reply(name, question);
                onAnswer.accept(answer);
                speaker.enqueue(tts.speak(answer));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                bot.log().warning("Ask failed: " + e.getMessage());
                onError.accept(e.getMessage());
            }
        })) onError.accept("The bot is busy, try again in a few seconds.");
    }

    /** Queue a job; returns false if the queue is full (the bot is busy). */
    private boolean submit(Runnable job) {
        try {
            worker.execute(job);
            return true;
        } catch (java.util.concurrent.RejectedExecutionException e) {
            return false;
        }
    }

    public void shutdown() {
        listener.shutdown();
        speaker.clear();
        worker.shutdownNow();
    }
}
