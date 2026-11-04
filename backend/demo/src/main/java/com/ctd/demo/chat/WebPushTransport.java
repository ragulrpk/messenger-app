package com.ctd.demo.chat;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** RFC 8291/8292 Web Push using the JDK's P-256, HMAC, AES-GCM and ES256 implementations. */
@Component
public class WebPushTransport {
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODE = Base64.getUrlDecoder();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();
    private volatile HttpClient http;
    private synchronized HttpClient http() {
        if (http == null) http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return http;
    }
    @jakarta.annotation.PreDestroy
    public void close() { if (http != null) http.close(); }
    private final String publicKey;
    private final PrivateKey privateKey;
    private final String subject;
    public WebPushTransport(@Value("${app.push.public-key:}") String publicKey,
            @Value("${app.push.private-key:}") String privateKey, @Value("${app.push.subject:}") String subject) throws GeneralSecurityException {
        this.publicKey = publicKey;
        this.subject = subject;
        if (publicKey.isBlank() && privateKey.isBlank()) { this.privateKey = null; return; }
        if (publicKey.isBlank() || privateKey.isBlank() || !(subject.startsWith("mailto:") || subject.startsWith("https://")))
            throw new IllegalArgumentException("Push requires public/private keys and a mailto: or https: contact subject");
        this.privateKey = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(DECODE.decode(privateKey)));
        var signature = Signature.getInstance("SHA256withECDSAinP1363Format");
        signature.initSign(this.privateKey); signature.update(new byte[]{1}); byte[] check = signature.sign();
        signature.initVerify(decodePublic(DECODE.decode(publicKey))); signature.update(new byte[]{1});
        if (!signature.verify(check)) throw new IllegalArgumentException("Push public and private keys do not match");
    }
    public boolean enabled() { return privateKey != null; }
    public String publicKey() { return publicKey; }
    public static URI endpoint(String value) {
        URI uri = URI.create(value);
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || host == null || uri.getUserInfo() != null || uri.getFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || !(host.equals("fcm.googleapis.com") || host.equals("updates.push.services.mozilla.com")
                     || host.equals("web.push.apple.com") || host.endsWith(".notify.windows.com")))
            throw new IllegalArgumentException("Unsupported browser push endpoint");
        return uri;
    }
    public static void validate(String endpoint, String key, String auth) throws GeneralSecurityException {
        endpoint(endpoint);
        decodePublic(DECODE.decode(key));
        if (DECODE.decode(auth).length != 16) throw new IllegalArgumentException("Invalid push auth secret");
    }
    public int send(String endpoint, String key, String auth, byte[] payload) throws Exception {
        URI uri = endpoint(endpoint);
        byte[] salt = new byte[16]; RANDOM.nextBytes(salt);
        byte[] body = encrypt(payload, DECODE.decode(key), DECODE.decode(auth), keyPair(), salt);
        String audience = "https://" + uri.getHost();
        String header = B64.encodeToString("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = B64.encodeToString(JSON.writeValueAsBytes(Map.of("aud", audience, "exp", Instant.now().plusSeconds(3600).getEpochSecond(), "sub", subject)));
        String signing = header + "." + claims;
        var signature = Signature.getInstance("SHA256withECDSAinP1363Format");
        signature.initSign(privateKey); signature.update(signing.getBytes(StandardCharsets.US_ASCII));
        String jwt = signing + "." + B64.encodeToString(signature.sign());
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                .header("Authorization", "vapid t=" + jwt + ", k=" + publicKey).header("TTL", "300")
                .header("Content-Encoding", "aes128gcm").header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        return http().send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
    static KeyPair keyPair() throws GeneralSecurityException {
        var generator = KeyPairGenerator.getInstance("EC"); generator.initialize(new ECGenParameterSpec("secp256r1")); return generator.generateKeyPair();
    }
    static byte[] rawPublic(PublicKey key) {
        var point = ((ECPublicKey) key).getW();
        byte[] raw = new byte[65]; raw[0] = 4;
        copyCoordinate(point.getAffineX(), raw, 1); copyCoordinate(point.getAffineY(), raw, 33); return raw;
    }
    private static void copyCoordinate(BigInteger value, byte[] target, int offset) {
        byte[] bytes = value.toByteArray();
        int count = Math.min(32, bytes.length); System.arraycopy(bytes, bytes.length - count, target, offset + 32 - count, count);
    }
    static PublicKey decodePublic(byte[] raw) throws GeneralSecurityException {
        if (raw.length != 65 || raw[0] != 4) throw new InvalidKeyException("Invalid P-256 public key");
        var parameters = AlgorithmParameters.getInstance("EC"); parameters.init(new ECGenParameterSpec("secp256r1"));
        var spec = parameters.getParameterSpec(ECParameterSpec.class);
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(raw, 1, 33)), y = new BigInteger(1, Arrays.copyOfRange(raw, 33, 65));
        BigInteger prime = ((ECFieldFp) spec.getCurve().getField()).getP();
        if (x.compareTo(prime) >= 0 || y.compareTo(prime) >= 0 || !y.multiply(y).mod(prime).equals(
                x.pow(3).add(spec.getCurve().getA().multiply(x)).add(spec.getCurve().getB()).mod(prime)))
            throw new InvalidKeyException("Point is not on the P-256 curve");
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), spec));
    }
    static byte[] encrypt(byte[] payload, byte[] recipient, byte[] auth, KeyPair sender, byte[] salt) throws GeneralSecurityException {
        if (payload.length > 3993 || auth.length != 16 || salt.length != 16) throw new IllegalArgumentException("Invalid push payload");
        byte[] senderPublic = rawPublic(sender.getPublic());
        var agreement = KeyAgreement.getInstance("ECDH"); agreement.init(sender.getPrivate()); agreement.doPhase(decodePublic(recipient), true);
        byte[] ikm = hmac(hmac(auth, agreement.generateSecret()), join("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), recipient, senderPublic, new byte[]{1}));
        byte[] prk = hmac(salt, ikm);
        byte[] cek = Arrays.copyOf(hmac(prk, "Content-Encoding: aes128gcm\0\1".getBytes(StandardCharsets.US_ASCII)), 16);
        byte[] nonce = Arrays.copyOf(hmac(prk, "Content-Encoding: nonce\0\1".getBytes(StandardCharsets.US_ASCII)), 12);
        var cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] encrypted = cipher.doFinal(join(payload, new byte[]{2}));
        return ByteBuffer.allocate(86 + encrypted.length).put(salt).putInt(4096).put((byte)65).put(senderPublic).put(encrypted).array();
    }
    private static byte[] hmac(byte[] key, byte[] value) throws GeneralSecurityException {
        var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(value);
    }
    private static byte[] join(byte[]... values) {
        int length = Arrays.stream(values).mapToInt(v -> v.length).sum(); var result = ByteBuffer.allocate(length);
        for (byte[] value : values) result.put(value); return result.array();
    }
}
