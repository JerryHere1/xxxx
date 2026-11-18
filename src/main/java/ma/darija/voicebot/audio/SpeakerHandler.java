package ma.darija.voicebot.audio;

import net.dv8tion.jda.api.audio.AudioSendHandler;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Feeds queued PCM to Discord in 20 ms frames: this is the bot's "mouth". */
public class SpeakerHandler implements AudioSendHandler {

    private final ConcurrentLinkedQueue<byte[]> frames = new ConcurrentLinkedQueue<>();
    private volatile long lastFrameSentAt = 0;

    public void enqueue(byte[] discordPcm) {
        for (int off = 0; off < discordPcm.length; off += AudioUtil.FRAME_BYTES) {
            byte[] frame = new byte[AudioUtil.FRAME_BYTES];
            int len = Math.min(AudioUtil.FRAME_BYTES, discordPcm.length - off);
            System.arraycopy(discordPcm, off, frame, 0, len);
            frames.add(frame);
        }
    }

    public void clear() {
        frames.clear();
    }

    /** True while audio is queued, plus a short tail so we don't hear our own echo end. */
    public boolean isSpeaking() {
        return !frames.isEmpty() || System.currentTimeMillis() - lastFrameSentAt < 400;
    }

    @Override
    public boolean canProvide() {
        return !frames.isEmpty();
    }

    @Override
    public ByteBuffer provide20MsAudio() {
        byte[] frame = frames.poll();
        if (frame == null) return null;
        lastFrameSentAt = System.currentTimeMillis();
        return ByteBuffer.wrap(frame);
    }

    @Override
    public boolean isOpus() {
        return false;
    }
}
