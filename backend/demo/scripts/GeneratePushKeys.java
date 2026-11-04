import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.*;

// Run once: java scripts/GeneratePushKeys.java. Keep the private key in deployment secrets.
class GeneratePushKeys {
    public static void main(String[] args) throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        var keys = generator.generateKeyPair();
        var point = ((ECPublicKey) keys.getPublic()).getW();
        byte[] raw = new byte[65]; raw[0] = 4;
        var coordinates = java.util.List.of(point.getAffineX(), point.getAffineY());
        for (int index = 0; index < 2; index++) {
            byte[] value = coordinates.get(index).toByteArray();
            int count = Math.min(32, value.length);
            System.arraycopy(value, value.length-count, raw, 1+index*32+32-count, count);
        }
        var encode = Base64.getUrlEncoder().withoutPadding();
        System.out.println("PUSH_PUBLIC_KEY=" + encode.encodeToString(raw));
        System.out.println("PUSH_PRIVATE_KEY=" + encode.encodeToString(keys.getPrivate().getEncoded()));
        System.out.println("Set PUSH_SUBJECT to a mailto: address for your service administrator.");
    }
}
