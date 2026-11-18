package ma.darija.voicebot.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ma.darija.voicebot.BotConfig;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** The AI that writes the Darija answers (Gemini or Groq), with a short memory. */
public class ChatBrain {

    /** One remembered message. role = "user" or "assistant". */
    public record Msg(String role, String text) {
    }

    private final BotConfig cfg;
    private final Deque<Msg> memory = new ArrayDeque<>();

    public ChatBrain(BotConfig cfg) {
        this.cfg = cfg;
    }

    public synchronized void reset() {
        memory.clear();
    }

    /** Ask the AI. speaker = name of the person talking. */
    public String reply(String speaker, String text) throws IOException, InterruptedException {
        List<Msg> history;
        synchronized (this) {
            history = List.copyOf(memory);
        }
        String userLine = speaker + ": " + text;
        String answer = cfg.aiProvider().equals("groq")
                ? askGroq(history, userLine)
                : askGemini(history, userLine);
        answer = answer.trim();
        synchronized (this) {
            memory.addLast(new Msg("user", userLine));
            memory.addLast(new Msg("assistant", answer));
            while (memory.size() > cfg.memoryMessages()) memory.removeFirst();
        }
        return answer;
    }

    private String systemPrompt() {
        return cfg.systemPrompt().replace("{name}", cfg.botName());
    }

    private String askGemini(List<Msg> history, String userLine) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();

        JsonObject sys = new JsonObject();
        sys.add("parts", parts(systemPrompt()));
        body.add("system_instruction", sys);

        JsonArray contents = new JsonArray();
        for (Msg m : history) contents.add(geminiTurn(m.role().equals("user") ? "user" : "model", m.text()));
        contents.add(geminiTurn("user", userLine));
        body.add("contents", contents);

        JsonObject gen = new JsonObject();
        gen.addProperty("temperature", 0.9);
        gen.addProperty("maxOutputTokens", 1024);
        body.add("generationConfig", gen);

        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + URLEncoder.encode(cfg.geminiModel(), StandardCharsets.UTF_8) + ":generateContent";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(40))
                .header("x-goog-api-key", cfg.geminiKey())
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = Http.CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Http.ensureOk(res, "AI (Gemini)");

        JsonObject json = JsonParser.parseString(res.body()).getAsJsonObject();
        StringBuilder sb = new StringBuilder();
        JsonArray candidates = json.getAsJsonArray("candidates");
        if (candidates != null && !candidates.isEmpty()) {
            JsonObject content = candidates.get(0).getAsJsonObject().getAsJsonObject("content");
            if (content != null && content.has("parts")) {
                for (JsonElement p : content.getAsJsonArray("parts")) {
                    JsonObject po = p.getAsJsonObject();
                    if (po.has("thought") && po.get("thought").getAsBoolean()) continue;
                    if (po.has("text")) sb.append(po.get("text").getAsString());
                }
            }
        }
        if (sb.isEmpty()) throw new IOException("AI (Gemini) returned no text: " + truncate(res.body()));
        return sb.toString();
    }

    private String askGroq(List<Msg> history, String userLine) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.groqModel());
        body.addProperty("temperature", 0.9);
        body.addProperty("max_tokens", 400);
        JsonArray messages = new JsonArray();
        messages.add(openAiMsg("system", systemPrompt()));
        for (Msg m : history) messages.add(openAiMsg(m.role(), m.text()));
        messages.add(openAiMsg("user", userLine));
        body.add("messages", messages);

        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.groq.com/openai/v1/chat/completions"))
                .timeout(Duration.ofSeconds(40))
                .header("Authorization", "Bearer " + cfg.groqKey())
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = Http.CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Http.ensureOk(res, "AI (Groq)");
        JsonObject json = JsonParser.parseString(res.body()).getAsJsonObject();
        JsonArray choices = json.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) throw new IOException("AI (Groq) returned no answer: " + truncate(res.body()));
        JsonElement content = choices.get(0).getAsJsonObject().getAsJsonObject("message").get("content");
        if (content == null || content.isJsonNull()) throw new IOException("AI (Groq) returned empty answer");
        return content.getAsString();
    }

    private static JsonArray parts(String text) {
        JsonArray arr = new JsonArray();
        JsonObject p = new JsonObject();
        p.addProperty("text", text);
        arr.add(p);
        return arr;
    }

    private static JsonObject geminiTurn(String role, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.add("parts", parts(text));
        return o;
    }

    private static JsonObject openAiMsg(String role, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", text);
        return o;
    }

    private static String truncate(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
