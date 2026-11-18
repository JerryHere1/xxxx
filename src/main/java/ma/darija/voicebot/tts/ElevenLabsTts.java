package ma.darija.voicebot.tts;

import com.google.gson.JsonObject;
import ma.darija.voicebot.ai.Http;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** ElevenLabs voices (best quality, free plan = 10k credits per month). Returns MP3 bytes. */
public class ElevenLabsTts {

    private final String apiKey;
    private final String voiceId;
    private final String model;
    private final String language;

    public ElevenLabsTts(String apiKey, String voiceId, String model, String language) {
        this.apiKey = apiKey;
        this.voiceId = voiceId;
        this.model = model;
        this.language = language;
    }

    public byte[] synthesize(String text) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("text", text);
        body.addProperty("model_id", model);
        if (!language.isBlank()) body.addProperty("language_code", language);

        String url = "https://api.elevenlabs.io/v1/text-to-speech/"
                + URLEncoder.encode(voiceId, StandardCharsets.UTF_8)
                + "?output_format=mp3_44100_128";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(40))
                .header("xi-api-key", apiKey)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "audio/mpeg")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> res = Http.CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
        Http.ensureOk(res, "ElevenLabs voice");
        return res.body();
    }
}
