package com.ctd.demo.chat;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WebPushTransportTests {
    @Test
    void encryptionMatchesThePublishedRfc8291Example() throws Exception {
        var decode = Base64.getUrlDecoder();
        byte[] recipient = decode.decode("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4");
        var senderPublic = (ECPublicKey) WebPushTransport.decodePublic(decode.decode("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8"));
        var senderPrivate = KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new java.math.BigInteger(1,
                decode.decode("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw")), senderPublic.getParams()));
        byte[] encrypted = WebPushTransport.encrypt("When I grow up, I want to be a watermelon".getBytes(StandardCharsets.US_ASCII), recipient,
                decode.decode("BTBZMqHH6r4Tts7J_aSIgg"), new KeyPair(senderPublic,senderPrivate),decode.decode("DGv6ra1nlYgDCS1FRnbzlw"));
        assertThat(Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted)).isEqualTo(
                "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN");
    }
    @Test
    void keysAndEndpointsAreValidatedWithoutNetworkRequests() throws Exception {
        var pair = WebPushTransport.keyPair();
        var encode = Base64.getUrlEncoder().withoutPadding();
        String pub = encode.encodeToString(WebPushTransport.rawPublic(pair.getPublic()));
        String priv = encode.encodeToString(pair.getPrivate().getEncoded());
        assertThat(new WebPushTransport(pub,priv,"mailto:admin@example.com").enabled()).isTrue();
        assertThat(new WebPushTransport("","","").enabled()).isFalse();
        assertThatThrownBy(() -> new WebPushTransport(pub,priv,"")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebPushTransport.endpoint("https://127.0.0.1/push")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebPushTransport.endpoint("https://fcm.googleapis.com.attacker.example/push")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebPushTransport.decodePublic(new byte[65])).isInstanceOf(InvalidKeyException.class);
    }
}
