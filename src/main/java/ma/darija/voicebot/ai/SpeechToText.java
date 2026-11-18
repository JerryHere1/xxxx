package ma.darija.voicebot.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ma.darija.voicebot.BotConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Turns a WAV clip into text using Whisper on Groq's free API. */
public class SpeechToText {

    private static final String URL = "https://api.groq.com/openai/v1/audio/transcriptions";
    private final BotConfig cfg;

    public SpeechToText(BotConfig cfg) {
        this.cfg = cfg;
    }

    public String transcribe(byte[] wav) throws IOException, InterruptedException {
        Http.Multipart form = new Http.Multipart()
                .field("model", cfg.sttModel())
                .field("response_format", "json")
                .field("temperature", "0");
        if (!cfg.sttLanguage().isBlank()) form.field("language", cfg.sttLanguage());
        if (!cfg.sttPrompt().isBlank()) form.field("prompt", cfg.sttPrompt());
        form.file("file", "speech.wav", "audio/wav", wav);

        HttpRequest req = HttpRequest.newBuilder(URI.create(URL))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + cfg.groqKey())
                .header("Content-Type", form.contentType())
                .POST(HttpRequest.BodyPublishers.ofByteArray(form.build()))
                .build();
        HttpResponse<String> res = Http.CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        Http.ensureOk(res, "Speech-to-text (Groq)");
        JsonObject json = JsonParser.parseString(res.body()).getAsJsonObject();
        return json.has("text") ? json.get("text").getAsString().trim() : "";
    }
}
