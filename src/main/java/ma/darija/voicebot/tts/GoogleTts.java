package ma.darija.voicebot.tts;

import ma.darija.voicebot.ai.Http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Backup voice: Google Translate's free Arabic voice. Returns MP3 bytes. */
public class GoogleTts {

    private static final int MAX_CHARS = 180;

    public byte[] synthesize(String text) throws IOException, InterruptedException {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (String part : split(text)) {
            String url = "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=ar&q="
                    + URLEncoder.encode(part, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
                    .GET().build();
            HttpResponse<byte[]> res = Http.CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
            Http.ensureOk(res, "Google TTS");
            all.writeBytes(res.body());
        }
        return all.toByteArray();
    }

    /** Google only accepts short text, so cut it at spaces / punctuation. */
    static List<String> split(String text) {
        List<String> parts = new ArrayList<>();
        String rest = text.trim();
        while (rest.length() > MAX_CHARS) {
            int cut = -1;
            for (int i = MAX_CHARS; i > MAX_CHARS / 2; i--) {
                char c = rest.charAt(i);
                if (c == '.' || c == '،' || c == ',' || c == '؟' || c == '?' || c == '!') {
                    cut = i + 1;
                    break;
                }
            }
            if (cut < 0) cut = rest.lastIndexOf(' ', MAX_CHARS);
            if (cut <= 0) cut = MAX_CHARS;
            parts.add(rest.substring(0, cut).trim());
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty()) parts.add(rest);
        return parts;
    }
}
