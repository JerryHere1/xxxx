package ma.darija.voicebot.ai;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/** Shared HTTP client + a tiny multipart/form-data builder. */
public final class Http {

    public static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private Http() {
    }

    public static void ensureOk(HttpResponse<?> res, String what) throws IOException {
        if (res.statusCode() / 100 != 2) {
            Object body = res.body();
            String text = body instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(body);
            if (text.length() > 400) text = text.substring(0, 400) + "...";
            throw new IOException(what + " failed (HTTP " + res.statusCode() + "): " + text);
        }
    }

    public static final class Multipart {
        public final String boundary = "----DarijaBot" + UUID.randomUUID().toString().replace("-", "");
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        public Multipart field(String name, String value) {
            write("--" + boundary + "\r\n");
            write("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
            write(value + "\r\n");
            return this;
        }

        public Multipart file(String name, String filename, String contentType, byte[] data) {
            write("--" + boundary + "\r\n");
            write("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n");
            write("Content-Type: " + contentType + "\r\n\r\n");
            out.writeBytes(data);
            write("\r\n");
            return this;
        }

        public byte[] build() {
            write("--" + boundary + "--\r\n");
            return out.toByteArray();
        }

        public String contentType() {
            return "multipart/form-data; boundary=" + boundary;
        }

        private void write(String s) {
            out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
        }
    }
}
