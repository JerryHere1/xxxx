package ma.darija.voicebot.audio;

import java.io.ByteArrayOutputStream;

/**
 * PCM helpers.
 * Discord (JDA) uses 48 kHz, 16-bit, stereo, signed BIG-endian PCM for both receiving and sending.
 */
public final class AudioUtil {

    public static final int DISCORD_RATE = 48_000;
    /** Bytes per second of Discord PCM: 48000 samples * 2 channels * 2 bytes. */
    public static final int DISCORD_BYTES_PER_SECOND = DISCORD_RATE * 4;
    /** One 20 ms Discord frame. */
    public static final int FRAME_BYTES = DISCORD_BYTES_PER_SECOND / 50; // 3840

    private AudioUtil() {
    }

    public static int millisOf(int discordPcmBytes) {
        return (int) (discordPcmBytes * 1000L / DISCORD_BYTES_PER_SECOND);
    }

    /** Counts how many milliseconds of the clip are louder than the threshold (in 20 ms windows). */
    public static int voicedMillis(byte[] pcm48StereoBe, int threshold) {
        int voicedFrames = 0;
        for (int off = 0; off + FRAME_BYTES <= pcm48StereoBe.length; off += FRAME_BYTES) {
            long sum = 0;
            int n = 0;
            for (int i = off; i < off + FRAME_BYTES; i += 4) {
                int l = (short) ((pcm48StereoBe[i] << 8) | (pcm48StereoBe[i + 1] & 0xFF));
                int r = (short) ((pcm48StereoBe[i + 2] << 8) | (pcm48StereoBe[i + 3] & 0xFF));
                int m = (l + r) / 2;
                sum += (long) m * m;
                n++;
            }
            double rms = Math.sqrt((double) sum / Math.max(1, n));
            if (rms >= threshold) voicedFrames++;
        }
        return voicedFrames * 20;
    }

    /** Discord PCM (48k stereo BE) -> WAV file bytes (16k mono LE), the format speech-to-text likes. */
    public static byte[] discordPcmToWav16kMono(byte[] pcm) {
        int stereoSamples = pcm.length / 4;
        int outSamples = stereoSamples / 3; // 48k -> 16k
        byte[] out = new byte[outSamples * 2];
        for (int o = 0; o < outSamples; o++) {
            int acc = 0;
            for (int k = 0; k < 3; k++) {
                int i = (o * 3 + k) * 4;
                int l = (short) ((pcm[i] << 8) | (pcm[i + 1] & 0xFF));
                int r = (short) ((pcm[i + 2] << 8) | (pcm[i + 3] & 0xFF));
                acc += (l + r) / 2;
            }
            int s = acc / 3;
            out[o * 2] = (byte) s;
            out[o * 2 + 1] = (byte) (s >> 8);
        }
        return wav(out, 16_000, 1);
    }

    /** Wraps 16-bit little-endian PCM in a WAV header. */
    public static byte[] wav(byte[] pcmLe, int sampleRate, int channels) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(44 + pcmLe.length);
        int byteRate = sampleRate * channels * 2;
        writeAscii(b, "RIFF");
        writeIntLe(b, 36 + pcmLe.length);
        writeAscii(b, "WAVE");
        writeAscii(b, "fmt ");
        writeIntLe(b, 16);
        writeShortLe(b, 1); // PCM
        writeShortLe(b, channels);
        writeIntLe(b, sampleRate);
        writeIntLe(b, byteRate);
        writeShortLe(b, channels * 2);
        writeShortLe(b, 16);
        writeAscii(b, "data");
        writeIntLe(b, pcmLe.length);
        b.writeBytes(pcmLe);
        return b.toByteArray();
    }

    /**
     * Converts decoded audio (16-bit samples, any rate, mono or interleaved stereo)
     * into Discord PCM (48k stereo BE) with linear resampling.
     */
    public static byte[] toDiscordPcm(short[] samples, int sampleCount, int sampleRate, int channels) {
        int frames = sampleCount / channels;
        if (frames <= 0) return new byte[0];
        float[] mono = new float[frames];
        for (int f = 0; f < frames; f++) {
            if (channels == 1) {
                mono[f] = samples[f];
            } else {
                int s = 0;
                for (int c = 0; c < channels; c++) s += samples[f * channels + c];
                mono[f] = (float) s / channels;
            }
        }
        double ratio = (double) sampleRate / DISCORD_RATE;
        int outFrames = (int) Math.floor(frames / ratio);
        byte[] out = new byte[outFrames * 4];
        for (int o = 0; o < outFrames; o++) {
            double src = o * ratio;
            int i0 = (int) src;
            int i1 = Math.min(i0 + 1, frames - 1);
            double frac = src - i0;
            int v = (int) Math.round(mono[i0] * (1 - frac) + mono[i1] * frac);
            if (v > Short.MAX_VALUE) v = Short.MAX_VALUE;
            if (v < Short.MIN_VALUE) v = Short.MIN_VALUE;
            int p = o * 4;
            out[p] = (byte) (v >> 8);
            out[p + 1] = (byte) v;
            out[p + 2] = (byte) (v >> 8);
            out[p + 3] = (byte) v;
        }
        return out;
    }

    private static void writeAscii(ByteArrayOutputStream b, String s) {
        for (int i = 0; i < s.length(); i++) b.write(s.charAt(i));
    }

    private static void writeIntLe(ByteArrayOutputStream b, int v) {
        b.write(v);
        b.write(v >> 8);
        b.write(v >> 16);
        b.write(v >> 24);
    }

    private static void writeShortLe(ByteArrayOutputStream b, int v) {
        b.write(v);
        b.write(v >> 8);
    }
}
