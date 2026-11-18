package ma.darija.voicebot.tts;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;

/**
 * Generates the "Sec-MS-GEC" token that Microsoft Edge's free read-aloud
 * text-to-speech service requires. Ported 1:1 from the open-source
 * edge-tts Python package (drm.py, generate_sec_ms_gec_v2).
 */
public final class EdgeToken {

    public static final String TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static final double WIN_EPOCH = 11644473600.0;

    private static final int[] SHA256_INITIAL = {
            0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A,
            0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19};
    private static final int[] SHA256_STATE = {
            0xFD9FBEA2, 0x91243CE7, 0xBE499094, 0xCD2E1E0A,
            0x6C006F49, 0x04BD35AB, 0xFEB0A7E4, 0x03EFFBD2};
    private static final int[] SHA256_SCHEDULE = {
            0xEB24BF9E, 0x1558691F, 0xA0C09EE2, 0x0CFA32BA, 0xA622EE8C,
            0xD1DD9491, 0xC0DD25A0, 0xF9E70AB6, 0xF0C31573, 0x7058CE4E,
            0x7188E606, 0x00E5AB33, 0xD258B060, 0x72766564};
    private static final int[] K = {
            0x428A2F98, 0x71374491, 0xB5C0FBCF, 0xE9B5DBA5, 0x3956C25B, 0x59F111F1, 0x923F82A4, 0xAB1C5ED5,
            0xD807AA98, 0x12835B01, 0x243185BE, 0x550C7DC3, 0x72BE5D74, 0x80DEB1FE, 0x9BDC06A7, 0xC19BF174,
            0xE49B69C1, 0xEFBE4786, 0x0FC19DC6, 0x240CA1CC, 0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
            0x983E5152, 0xA831C66D, 0xB00327C8, 0xBF597FC7, 0xC6E00BF3, 0xD5A79147, 0x06CA6351, 0x14292967,
            0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13, 0x650A7354, 0x766A0ABB, 0x81C2C92E, 0x92722C85,
            0xA2BFE8A1, 0xA81A664B, 0xC24B8B70, 0xC76C51A3, 0xD192E819, 0xD6990624, 0xF40E3585, 0x106AA070,
            0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5, 0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
            0x748F82EE, 0x78A5636F, 0x84C87814, 0x8CC70208, 0x90BEFFFA, 0xA4506CEB, 0xBEF9A3F7, 0xC67178F2};
    private static final int SHA256_ADJUSTMENT = 0xC9DC06A7;
    private static final int[] K_BLOCK;
    private static final int SIGMA0_ADJ = 0x059C0B80;
    private static final int SIGMA1_ADJ = 0x000B92C0;

    private static final int[][] CS64_COEFFICIENTS = {
            {0xE82E91B1, 0xB32700D0}, {0x44DE24C6, 0x88DFF51D}, {0x3BEB65BC, 0x29514038},
            {0xC89C5933, 0xB328CBDF}, {0x8D1EC211, 0xD405B4D5}, {0x314EF5D5, 0x26E55F3C},
            {0xE92491B1, 0x607801C6}, {0xF0A5B9D5, 0x82B2C1FB}, {0x06064CF3, 0xFA51A3DA},
            {0xDA79FA60, 0x207CC464}, {0xBED20560, 0x3C3C74F3}, {0x89F9C9F8, 0xD2D14B61},
            {0x35FA38C6, 0xBB04A2C1}, {0xCB49E3C6, 0xC5626167}};
    private static final int CS64_FACTOR = 0x82893933;
    private static final int CS64_ADJ0 = 0xF2F90B1A;
    private static final int CS64_ADJ1 = 0x0B1A0000;

    static {
        K_BLOCK = K.clone();
        K_BLOCK[14] = SHA256_ADJUSTMENT;
    }

    private EdgeToken() {
    }

    /** Token for the current time, corrected by a clock skew in seconds. */
    public static String generateNow(double clockSkewSeconds) {
        return generateAt(System.currentTimeMillis() / 1000.0 + clockSkewSeconds);
    }

    /** Token for a given unix timestamp (seconds). */
    public static String generateAt(double unixSeconds) {
        double ticks = unixSeconds + WIN_EPOCH;
        ticks -= ticks % 300;
        ticks *= 1e9 / 100;
        String ticksStr = new BigDecimal(ticks).setScale(0, RoundingMode.HALF_EVEN).toPlainString();
        byte[] data = (ticksStr + TRUSTED_CLIENT_TOKEN).getBytes(StandardCharsets.US_ASCII);
        byte[] digest = sha256Digest(data);
        int[] cs = cs64Digest(data, digest);
        return hex(leBytes(cs[0], cs[1])) + "-" + hex(digest);
    }

    // ---------------------------------------------------------------- SHA-256 variant

    private static int s0(int v) {
        return Integer.rotateRight(v, 7) ^ Integer.rotateRight(v, 18) ^ (v >>> 3);
    }

    private static int s1(int v) {
        return Integer.rotateRight(v, 17) ^ Integer.rotateRight(v, 19) ^ (v >>> 10);
    }

    private static void rounds(int[] state, int[] w, int firstRound, int[] out) {
        int[] k = firstRound == 14 ? K_BLOCK : K;
        int a = state[0], b = state[1], c = state[2], d = state[3];
        int e = state[4], f = state[5], g = state[6], h = state[7];
        for (int i = firstRound; i < 64; i++) {
            int t1 = h + (Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11) ^ Integer.rotateRight(e, 25))
                    + (g ^ (e & (f ^ g))) + k[i] + w[i];
            int t2 = (Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13) ^ Integer.rotateRight(a, 22))
                    + ((a & b) | (c & (a | b)));
            h = g;
            g = f;
            f = e;
            e = d + t1;
            d = c;
            c = b;
            b = a;
            a = t1 + t2;
        }
        out[0] = a; out[1] = b; out[2] = c; out[3] = d;
        out[4] = e; out[5] = f; out[6] = g; out[7] = h;
    }

    private static void expand(int[] w, int firstWord) {
        for (int i = firstWord; i < 64; i++) {
            w[i] = s1(w[i - 2]) + w[i - 7] + s0(w[i - 15]) + w[i - 16];
        }
    }

    private static int be3(byte[] d, int off) {
        return ((d[off] & 0xFF) << 16) | ((d[off + 1] & 0xFF) << 8) | (d[off + 2] & 0xFF);
    }

    private static int be4(byte[] d, int off) {
        return ((d[off] & 0xFF) << 24) | ((d[off + 1] & 0xFF) << 16) | ((d[off + 2] & 0xFF) << 8) | (d[off + 3] & 0xFF);
    }

    private static int[] sha256Init(byte[] data) {
        int[] w = new int[64];
        w[14] = be3(data, 0);
        w[15] = be4(data, 3);
        for (int i = 16; i < 30; i++) {
            int v = SHA256_SCHEDULE[i - 16];
            if (i - 2 >= 14) {
                v += (i == 16) ? (s1(w[i - 2]) ^ SIGMA1_ADJ) : s1(w[i - 2]);
            }
            if (i - 7 >= 14) {
                v += w[i - 7];
            }
            if (i - 15 >= 14) {
                v += (i == 29) ? (s0(w[i - 15]) ^ SIGMA0_ADJ) : s0(w[i - 15]);
            }
            w[i] = v;
        }
        w[30] = s1(w[28]) + w[23] + s0(w[15]) + w[14] + SHA256_ADJUSTMENT - K[14];
        expand(w, 31);
        int[] working = new int[8];
        rounds(SHA256_STATE, w, 14, working);
        int[] res = new int[8];
        for (int i = 0; i < 8; i++) {
            res[i] = SHA256_INITIAL[i] + working[i];
        }
        return res;
    }

    static byte[] sha256Digest(byte[] data) {
        if (data.length < 7) {
            throw new IllegalArgumentException("Token salt must contain at least seven bytes");
        }
        int length = 57 + data.length;
        int zeros = Math.floorMod(55 - length, 64);
        int restLen = data.length - 7;
        byte[] padded = new byte[restLen + 1 + zeros + 8];
        System.arraycopy(data, 7, padded, 0, restLen);
        padded[restLen] = (byte) 0x80;
        long bits = (long) length * 8L;
        for (int i = 0; i < 8; i++) {
            padded[padded.length - 1 - i] = (byte) (bits >>> (8 * i));
        }
        int[] state = sha256Init(data);
        int[] w = new int[64];
        int[] working = new int[8];
        for (int off = 0; off < padded.length; off += 64) {
            for (int i = 0; i < 16; i++) {
                w[i] = be4(padded, off + i * 4);
            }
            expand(w, 16);
            rounds(state, w, 0, working);
            for (int i = 0; i < 8; i++) {
                state[i] += working[i];
            }
        }
        byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) {
            out[i * 4] = (byte) (state[i] >>> 24);
            out[i * 4 + 1] = (byte) (state[i] >>> 16);
            out[i * 4 + 2] = (byte) (state[i] >>> 8);
            out[i * 4 + 3] = (byte) state[i];
        }
        return out;
    }

    // ---------------------------------------------------------------- CS64

    private static int rol16(int v) {
        return Integer.rotateLeft(v, 16);
    }

    private static int le4(byte[] d, int off) {
        return (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8) | ((d[off + 2] & 0xFF) << 16) | ((d[off + 3] & 0xFF) << 24);
    }

    private static int[] wordsLe4Even(byte[] data) {
        int padLen = data.length + Math.floorMod(-data.length, 4);
        if (padLen == 0) {
            return new int[0];
        }
        byte[] p = new byte[padLen];
        System.arraycopy(data, 0, p, 0, data.length);
        int n = padLen / 4;
        int[] words = new int[n % 2 == 1 ? n + 1 : n];
        for (int i = 0; i < n; i++) {
            words[i] = le4(p, i * 4);
        }
        return words;
    }

    private static int[] cs64Digest(byte[] data, byte[] digest) {
        int keyA = le4(digest, 0) | 1;
        int keyB = le4(digest, 4) | 1;
        int[] factors = {keyA * CS64_FACTOR, keyB * CS64_FACTOR};

        byte[] prefixed = new byte[data.length + 1];
        System.arraycopy(data, 0, prefixed, 1, data.length);
        int[] words = wordsLe4Even(prefixed);

        int total = CS64_COEFFICIENTS.length + words.length;
        int[] terms = new int[total];
        int[] offsets = new int[total];
        for (int i = 0; i < CS64_COEFFICIENTS.length; i++) {
            terms[i] = CS64_COEFFICIENTS[i][0] * factors[i % 2];
            offsets[i] = CS64_COEFFICIENTS[i][1];
        }
        for (int i = 0; i < words.length; i++) {
            int word = words[i];
            int term = word * (i % 2 == 0 ? keyA : keyB);
            int offset = rol16(word) * (i % 2 == 0 ? 0xEF0569FB : 0xC31713DB);
            if (i == 0) {
                term += CS64_ADJ0 * factors[0];
                offset += CS64_ADJ1;
            }
            terms[CS64_COEFFICIENTS.length + i] = term;
            offsets[CS64_COEFFICIENTS.length + i] = offset;
        }

        int swapAcc = 0, swapLast = 0, state = 0, acc = 0;
        for (int i = 0; i < total; i += 2) {
            int x = terms[i], y = terms[i + 1];
            int t = rol16(offsets[i] + x);
            int q = t * 0x76F50F34;
            swapAcc += rol16(q) * 0xC3EFEA97 + q;
            t = rol16(offsets[i + 1] + y);
            q = t * 0x1881CE3C;
            swapLast = rol16(q) * 0x35BD1EC9 + q;
            swapAcc += swapLast;

            state = state * keyA + x;
            state = rol16(state) * 0xCF98B111;
            state = rol16(state) * 0x87085B9F;
            state = rol16(state) * 0x12CEB96D;
            state = rol16(state) * 0x257E1D83;
            acc += state;
            t = state * keyB + y;
            t = rol16(t) * 0xA27416F5;
            t = rol16(t) * 0xD38396FF;
            state = rol16(rol16(t) * 0x7C932B89) * 0xBFA49F69;
            acc += state;
        }
        return new int[]{swapLast ^ state, swapAcc ^ acc};
    }

    private static byte[] leBytes(int a, int b) {
        return new byte[]{
                (byte) a, (byte) (a >>> 8), (byte) (a >>> 16), (byte) (a >>> 24),
                (byte) b, (byte) (b >>> 8), (byte) (b >>> 16), (byte) (b >>> 24)};
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte x : bytes) {
            sb.append(Character.toUpperCase(Character.forDigit((x >> 4) & 0xF, 16)));
            sb.append(Character.toUpperCase(Character.forDigit(x & 0xF, 16)));
        }
        return sb.toString();
    }
}
