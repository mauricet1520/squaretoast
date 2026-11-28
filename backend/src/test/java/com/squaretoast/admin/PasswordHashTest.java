package com.squaretoast.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Slice B1: Argon2id at or above the OWASP floor (m=19 MiB, t=2, p=1), salted, PHC-encoded. */
class PasswordHashTest {

    @Test
    void argon2idParamsMeetFloor() {
        String h = Passwords.hash("hunter2 hunter2");
        Matcher m = Pattern.compile("^\\$argon2id\\$v=19\\$m=(\\d+),t=(\\d+),p=(\\d+)\\$.+\\$.+$").matcher(h);
        assertThat(m.matches()).as(h).isTrue();
        assertThat(Integer.parseInt(m.group(1))).isGreaterThanOrEqualTo(19_456);
        assertThat(Integer.parseInt(m.group(2))).isGreaterThanOrEqualTo(2);
        assertThat(Integer.parseInt(m.group(3))).isEqualTo(1);
    }

    @Test
    void samePasswordGetsANewSaltAndOnlyItMatches() {
        String a = Passwords.hash("hunter2 hunter2");
        assertThat(Passwords.hash("hunter2 hunter2")).isNotEqualTo(a);
        assertThat(Passwords.matches("hunter2 hunter2", a)).isTrue();
        assertThat(Passwords.matches("hunter3 hunter2", a)).isFalse();
        assertThat(Passwords.matches("hunter2 hunter2", "not a hash")).isFalse();
    }
}
