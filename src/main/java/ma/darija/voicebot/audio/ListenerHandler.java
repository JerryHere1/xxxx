package ma.darija.voicebot.audio;

import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.UserAudio;
import net.dv8tion.jda.api.entities.User;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * The bot's "ears". Collects each person's audio separately and, once that person
 * has been quiet for a moment, hands the whole sentence to the callback.
 */
public class ListenerHandler implements AudioReceiveHandler {

    public interface UtteranceCallback {
        void onUtterance(User user, byte[] discordPcm);
    }

    private static final class Buffer {
        final User user;
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        long lastPacketAt;

        Buffer(User user) {
            this.user = user;
        }
    }

    private final Map<Long, Buffer> buffers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService ticker;
    private final UtteranceCallback callback;
    private final BooleanSupplier muted;
    private final int silenceMs;
    private final int minSpeechMs;
    private final int maxBytes;
    private final int threshold;

    public ListenerHandler(UtteranceCallback callback, BooleanSupplier muted,
                           int silenceMs, int minSpeechMs, int maxSeconds, int threshold) {
        this.callback = callback;
        this.muted = muted;
        this.silenceMs = silenceMs;
        this.minSpeechMs = minSpeechMs;
        this.maxBytes = maxSeconds * AudioUtil.DISCORD_BYTES_PER_SECOND;
        this.threshold = threshold;
        this.ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DarijaBot-Ears");
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleAtFixedRate(this::checkSilence, 100, 100, TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean canReceiveUser() {
        return true;
    }

    @Override
    public void handleUserAudio(UserAudio userAudio) {
        User user = userAudio.getUser();
        if (user.isBot()) return;
        if (muted.getAsBoolean()) {
            buffers.remove(user.getIdLong());
            return;
        }
        byte[] pcm = userAudio.getAudioData(1.0);
        Buffer b = buffers.computeIfAbsent(user.getIdLong(), id -> new Buffer(user));
        byte[] full = null;
        synchronized (b) {
            b.data.writeBytes(pcm);
            b.lastPacketAt = System.currentTimeMillis();
            if (b.data.size() >= maxBytes) {
                full = b.data.toByteArray();
                b.data.reset();
            }
        }
        if (full != null) deliver(user, full);
    }

    private void checkSilence() {
        try {
            long now = System.currentTimeMillis();
            for (Map.Entry<Long, Buffer> e : buffers.entrySet()) {
                Buffer b = e.getValue();
                byte[] clip = null;
                synchronized (b) {
                    if (b.data.size() > 0 && now - b.lastPacketAt >= silenceMs) {
                        clip = b.data.toByteArray();
                        b.data.reset();
                    }
                }
                if (clip != null) deliver(b.user, clip);
            }
        } catch (Throwable ignored) {
            // never let the ticker die
        }
    }

    private void deliver(User user, byte[] clip) {
        if (AudioUtil.voicedMillis(clip, threshold) < minSpeechMs) return;
        callback.onUtterance(user, clip);
    }

    public void shutdown() {
        ticker.shutdownNow();
        buffers.clear();
    }
}
