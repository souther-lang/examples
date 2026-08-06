// The two password behaviors identity.sou injects. The domain states what a password must be —
// Password's invariant — and never learns how one is stored, so bcrypt lives here and the choice of
// algorithm is a boundary decision that can change without touching a .sou.
package app.realworld;

import example.identity.HashPassword;
import example.identity.Password;
import example.identity.PasswordHash;
import example.identity.VerifyPassword;

import org.springframework.security.crypto.password.PasswordEncoder;

import static app.realworld.souther.Decoding.decodeOrFail;

public final class BcryptPasswords {

    private BcryptPasswords() {
    }

    /** hashPassword: the plaintext in, the stored form out. The hash is built through the decoder. */
    public static final class Hash extends HashPassword {

        private final PasswordEncoder encoder;

        public Hash(PasswordEncoder encoder) {
            this.encoder = encoder;
        }

        @Override
        public PasswordHash apply(Password password) {
            String hashed = encoder.encode(Password.encoder().encode(password));
            return decodeOrFail(PasswordHash.decoder(), hashed);
        }
    }

    /**
     * verifyPassword: whether a plaintext matches a stored hash. It answers a Bool rather than a
     * case because the domain decides what a mismatch means — loginUser turns it into
     * InvalidCredentials, and it turns an unknown address into the same one.
     */
    public static final class Verify extends VerifyPassword {

        private final PasswordEncoder encoder;

        public Verify(PasswordEncoder encoder) {
            this.encoder = encoder;
        }

        @Override
        public Boolean apply(Password password, PasswordHash hash) {
            return encoder.matches(Password.encoder().encode(password),
                    PasswordHash.encoder().encode(hash));
        }
    }
}
