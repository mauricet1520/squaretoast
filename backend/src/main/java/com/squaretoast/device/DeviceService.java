package com.squaretoast.device;

import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceService {

    /** Every enrollment failure looks the same to the caller: no hint whether the code or the key was wrong. */
    static final class EnrollmentRejected extends RuntimeException {
        private static final long serialVersionUID = 1L;

        EnrollmentRejected() {
            super("enrollment rejected");
        }
    }

    record Enrolled(UUID deviceId, UUID locationId, String deviceToken) {}

    record Me(UUID deviceId, UUID locationId, @Nullable String terminalLocationId) {}

    private static final ECParameterSpec P256 = p256();
    private static final int MAX_LABEL = 64;

    private final JdbcClient db;

    DeviceService(JdbcClient db) {
        this.db = db;
    }

    /** Validates the key before touching the code, so a bad key never burns a one-time code. */
    @Transactional
    Enrolled enroll(String code, String publicKeySpki, String label) {
        byte[] spki = p256Spki(publicKeySpki).orElseThrow(EnrollmentRejected::new);
        byte[] codeHash = Tokens.sha256(code);
        UUID location = db.sql("""
                select location_id from device_enrollment
                where code_hash = ? and used_at is null and expires_at > now() for update""")
                .params(codeHash).query(UUID.class).optional().orElseThrow(EnrollmentRejected::new);
        UUID device = UUID.randomUUID();
        String token = Tokens.newToken();
        String name = label.strip();
        db.sql("insert into device(id, location_id, public_key_spki, token_hash, label) values (?, ?, ?, ?, ?)")
                .params(device, location, spki, Tokens.sha256(token), name.substring(0, Math.min(name.length(), MAX_LABEL)))
                .update();
        db.sql("update device_enrollment set used_at = now(), device_id = ? where code_hash = ?")
                .params(device, codeHash).update();
        return new Enrolled(device, location, token);
    }

    Me me(DevicePrincipal device) {
        String terminal = db.sql("select stripe_terminal_location_id from location where id = ?")
                .params(device.locationId()).query(String.class).optional().orElse(null);
        return new Me(device.deviceId(), device.locationId(), terminal);
    }

    private static Optional<byte[]> p256Spki(String base64) {
        try {
            byte[] spki = Base64.getDecoder().decode(base64);
            if (KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(spki)) instanceof ECPublicKey k
                    && isP256(k.getParams())) {
                return Optional.of(spki);
            }
            return Optional.empty();
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            return Optional.empty();
        }
    }

    private static boolean isP256(ECParameterSpec p) {
        return p.getCurve().equals(P256.getCurve()) && p.getGenerator().equals(P256.getGenerator())
                && p.getOrder().equals(P256.getOrder()) && p.getCofactor() == P256.getCofactor();
    }

    private static ECParameterSpec p256() {
        try {
            AlgorithmParameters p = AlgorithmParameters.getInstance("EC");
            p.init(new ECGenParameterSpec("secp256r1"));
            return p.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
