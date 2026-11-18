package ma.darija.voicebot.tts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ma.darija.voicebot.ai.Http;
import ma.darija.voicebot.audio.AudioUtil;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google Gemini text-to-speech. The accent is controlled with a plain-language
 * style instruction, e.g. "speak Moroccan Darija with a Casablanca accent".
 * Returns Discord-ready PCM.
 */
public class GeminiTts {

    private static final Pattern RATE = Pattern.compile("rate=(\\d+)");

    private final String apiKey;
    private final String model;
    private final String voice;
    private final String style;

    public GeminiTts(String apiKey, String model, String voice, String style) {
        this.apiKey = apiKey;
        this.model = model;
        this.voice = voice;
        this.style = style;
    }

    public byte[] synthesizeToDiscordPcm(String text) throws IOException, InterruptedException {
        String prompt = style.isBlank() ? text : style.trim() + "\n" + text;

        JsonObject part = new JsonObject();
        part.addProperty("text", prompt);
        JsonArray parts = new JsonArray();
        parts.add(part);
        JsonObject content = new JsonObject();
        content.add("parts", parts);
        JsonArray contents = new JsonArray();
        contents.add(content);

        JsonObject prebuilt = new JsonObject();
        prebuilt.addProperty("voiceName", voice);
        JsonObject voiceConfig = new JsonObject();
        voiceConfig.add("prebuiltVoiceConfig", prebuilt);
        JsonObject speechConfig = new JsonObject();
        speechConfig.add("voiceConfig", voiceConfig);
        JsonArray modalities = new JsonArray();
        modalities.add("AUDIO");
        JsonObject gen = new JsonObject();
        gen.add("responseModalities", modalities);
        gen.add("speechConfig", speechConfig);

        JsonObject body = new JsonObject();
        body.add("contents", contents);
        body.add("generationConfig", gen);

        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + URLEncoder.encode(model, StandardCharsets.UTF_8) + ":generateContent";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("x-goog-api-key", apiKey)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = Http.CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Http.ensureOk(res, "Gemini voice");

        JsonObject json = JsonParser.parseString(res.body()).getAsJsonObject();
        JsonArray candidates = json.getAsJsonArray("candidates");
        if (candidates == null || candidates.isEmpty()) throw new IOException("Gemini voice returned no audio");
        JsonObject c = candidates.get(0).getAsJsonObject().getAsJsonObject("content");
        if (c == null || !c.has("parts")) throw new IOException("Gemini voice returned no audio");

        for (JsonElement p : c.getAsJsonArray("parts")) {
            JsonObject po = p.getAsJsonObject();
            JsonObject inline = po.has("inlineData") ? po.getAsJsonObject("inlineData")
                    : po.has("inline_data") ? po.getAsJsonObject("inline_data") : null;
            if (inline == null || !inline.has("data")) continue;
            String mime = inline.has("mimeType") ? inline.get("mimeType").getAsString()
                    : inline.has("mime_type") ? inline.get("mime_type").getAsString() : "";
            int rate = 24_000;
            Matcher m = RATE.matcher(mime);
            if (m.find()) rate = Integer.parseInt(m.group(1));

            byte[] pcmLe = Base64.getDecoder().decode(inline.get("data").getAsString());
            short[] samples = new short[pcmLe.length / 2];
            for (int i = 0; i < samples.length; i++) {
                samples[i] = (short) ((pcmLe[i * 2] & 0xFF) | (pcmLe[i * 2 + 1] << 8));
            }
            return AudioUtil.toDiscordPcm(samples, samples.length, rate, 1);
        }
        throw new IOException("Gemini voice returned no audio");
    }
}
