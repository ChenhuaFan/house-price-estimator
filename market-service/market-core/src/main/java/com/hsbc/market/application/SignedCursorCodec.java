package com.hsbc.market.application;

import com.hsbc.market.domain.MarketDomain.CursorPosition;
import com.hsbc.market.domain.MarketDomain.ResolvedSelection;
import com.hsbc.market.domain.MarketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class SignedCursorCodec {
  private final byte[] secret;

  public SignedCursorCodec(String secret) {
    if (secret == null || secret.length() < 32) {
      throw new IllegalArgumentException("cursor HMAC secret must contain at least 32 characters");
    }
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
  }

  public String encode(ResolvedSelection selection, CursorPosition position) {
    String payload =
        String.join(
            "|",
            "2",
            selection.datasetVersion().id().toString(),
            selection.queryFingerprint(),
            selection.sort().field().apiName(),
            selection.sort().direction().name(),
            position.sortValue(),
            Long.toString(position.sourceId()));
    byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        + '.'
        + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(bytes));
  }

  public CursorPosition decode(String cursor, ResolvedSelection selection) {
    try {
      String[] encoded = cursor.split("\\.", -1);
      if (encoded.length != 2) throw new IllegalArgumentException("invalid cursor parts");
      byte[] payloadBytes = Base64.getUrlDecoder().decode(encoded[0]);
      byte[] suppliedSignature = Base64.getUrlDecoder().decode(encoded[1]);
      if (!MessageDigest.isEqual(sign(payloadBytes), suppliedSignature)) {
        throw new IllegalArgumentException("invalid cursor signature");
      }
      String[] parts = new String(payloadBytes, StandardCharsets.UTF_8).split("\\|", -1);
      if (parts.length != 7
          || !"2".equals(parts[0])
          || !selection.datasetVersion().id().toString().equals(parts[1])
          || !selection.queryFingerprint().equals(parts[2])
          || !selection.sort().field().apiName().equals(parts[3])
          || !selection.sort().direction().name().equals(parts[4])) {
        throw MarketException.conflict(
            "QUERY_STATE_MISMATCH", "cursor does not match the dataset, filters, or sort");
      }
      return new CursorPosition(parts[5], Long.parseLong(parts[6]));
    } catch (MarketException error) {
      throw error;
    } catch (RuntimeException error) {
      throw MarketException.conflict("QUERY_STATE_MISMATCH", "cursor is invalid");
    }
  }

  private byte[] sign(byte[] payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return mac.doFinal(payload);
    } catch (Exception error) {
      throw new IllegalStateException("cannot sign cursor", error);
    }
  }
}
