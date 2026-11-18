package ma.darija.voicebot.audio;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.JavaLayerException;
import javazoom.jl.decoder.SampleBuffer;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/** Decodes MP3 bytes (from text-to-speech) straight into Discord PCM. */
public final class Mp3Decoder {

    private Mp3Decoder() {
    }

    public static byte[] decodeToDiscordPcm(byte[] mp3) throws IOException {
        Bitstream bitstream = new Bitstream(new ByteArrayInputStream(mp3));
        Decoder decoder = new Decoder();
        short[] all = new short[1 << 16];
        int count = 0;
        int sampleRate = 24_000;
        int channels = 1;
        try {
            Header header;
            while ((header = bitstream.readFrame()) != null) {
                try {
                    SampleBuffer buf = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                    sampleRate = buf.getSampleFrequency();
                    channels = buf.getChannelCount();
                    short[] data = buf.getBuffer();
                    int len = Math.min(buf.getBufferLength(), data.length);
                    if (count + len > all.length) {
                        short[] bigger = new short[Math.max(all.length * 2, count + len)];
                        System.arraycopy(all, 0, bigger, 0, count);
                        all = bigger;
                    }
                    System.arraycopy(data, 0, all, count, len);
                    count += len;
                } catch (JavaLayerException frameError) {
                    // skip a broken frame
                } finally {
                    bitstream.closeFrame();
                }
            }
        } catch (JavaLayerException e) {
            if (count == 0) throw new IOException("Could not decode MP3 audio", e);
        } finally {
            try {
                bitstream.close();
            } catch (JavaLayerException ignored) {
            }
        }
        return AudioUtil.toDiscordPcm(all, count, sampleRate, channels);
    }
}
