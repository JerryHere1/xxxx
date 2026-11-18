package ma.darija.voicebot.tts;

import ma.darija.voicebot.ai.Http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Free Microsoft Edge "Read aloud" voices (same service the edge-tts Python tool uses).
 * Returns MP3 bytes.
 */
public class EdgeTts {

    private static final String CHROMIUM_FULL_VERSION = "143.0.3650.75";
    private static final String CHROMIUM_MAJOR = CHROMIUM_FULL_VERSION.split("\\.")[0];
    private static final String WSS_URL = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
            + "?TrustedClientToken=" + EdgeToken.TRUSTED_CLIENT_TOKEN;
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            + " (KHTML, like Gecko) Chrome/" + CHROMIUM_MAJOR + ".0.0.0 Safari/537.36 Edg/" + CHROMIUM_MAJOR + ".0.0.0";
    private static final Pattern SHORT_NAME = Pattern.compile("^([a-z]{2,})-([A-Z]{2,})-(.+Neural)$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String voice;
    private final String rate;
    private final String pitch;

    public EdgeTts(String voice, String rate, String pitch) {
        this.voice = longVoiceName(voice);
        this.rate = rate;
        this.pitch = pitch;
    }

    static String longVoiceName(String v) {
        Matcher m = SHORT_NAME.matcher(v);
        if (m.matches()) {
            return "Microsoft Server Speech Text to Speech Voice (" + m.group(1) + "-" + m.group(2) + ", " + m.group(3) + ")";
        }
        return v;
    }

    public byte[] synthesize(String text) throws IOException {
        String url = WSS_URL
                + "&ConnectionId=" + uuid()
                + "&Sec-MS-GEC=" + EdgeToken.generateNow(0)
                + "&Sec-MS-GEC-Version=2-" + CHROMIUM_FULL_VERSION;

        Collector collector = new Collector();
        WebSocket ws;
        try {
            ws = Http.CLIENT.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .header("Pragma", "no-cache")
                    .header("Cache-Control", "no-cache")
                    .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                    .header("User-Agent", USER_AGENT)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Cookie", "muid=" + muid() + ";")
                    .buildAsync(URI.create(url), collector)
                    .get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IOException("Edge TTS connection failed: " + rootMessage(e), e);
        }

        try {
            String date = jsDate();
            ws.sendText("X-Timestamp:" + date + "\r\n"
                    + "Content-Type:application/json; charset=utf-8\r\n"
                    + "Path:speech.config\r\n\r\n"
                    + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{"
                    + "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},"
                    + "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n", true).get(10, TimeUnit.SECONDS);

            String ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>"
                    + "<voice name='" + voice + "'>"
                    + "<prosody pitch='" + pitch + "' rate='" + rate + "' volume='+0%'>"
                    + escapeXml(text)
                    + "</prosody></voice></speak>";
            ws.sendText("X-RequestId:" + uuid() + "\r\n"
                    + "Content-Type:application/ssml+xml\r\n"
                    + "X-Timestamp:" + date + "Z\r\n"
                    + "Path:ssml\r\n\r\n"
                    + ssml, true).get(10, TimeUnit.SECONDS);

            byte[] audio = collector.done.get(30, TimeUnit.SECONDS);
            if (audio.length == 0) throw new IOException("Edge TTS returned no audio");
            return audio;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Edge TTS failed: " + rootMessage(e), e);
        } finally {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "");
            } catch (Exception ignored) {
            }
            ws.abort();
        }
    }

    /** Receives the websocket messages and collects the MP3 data. */
    private static final class Collector implements WebSocket.Listener {
        final CompletableFuture<byte[]> done = new CompletableFuture<>();
        private final ByteArrayOutputStream audio = new ByteArrayOutputStream();
        private final ByteArrayOutputStream binPart = new ByteArrayOutputStream();
        private final StringBuilder textPart = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textPart.append(data);
            if (last) {
                String msg = textPart.toString();
                textPart.setLength(0);
                if (msg.contains("Path:turn.end")) {
                    done.complete(audio.toByteArray());
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            binPart.writeBytes(chunk);
            if (last) {
                byte[] msg = binPart.toByteArray();
                binPart.reset();
                if (msg.length >= 2) {
                    int headerLen = ((msg[0] & 0xFF) << 8) | (msg[1] & 0xFF);
                    if (2 + headerLen <= msg.length) {
                        String headers = new String(msg, 2, headerLen, StandardCharsets.UTF_8);
                        if (headers.contains("Path:audio")) {
                            audio.write(msg, 2 + headerLen, msg.length - 2 - headerLen);
                        }
                    }
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (!done.isDone()) {
                if (audio.size() > 0) done.complete(audio.toByteArray());
                else done.completeExceptionally(new IOException("Edge TTS closed: " + statusCode + " " + reason));
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            done.completeExceptionally(error);
        }
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String muid() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    private static String jsDate() {
        SimpleDateFormat f = new SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
