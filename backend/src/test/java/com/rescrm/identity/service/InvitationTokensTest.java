package com.rescrm.identity.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Invitation tokens")
class InvitationTokensTest {

    @Test
    @DisplayName("issues a distinct token every time")
    void tokens_are_distinct() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            seen.add(InvitationTokens.issue().rawToken());
        }
        assertThat(seen).hasSize(1000);
    }

    @Test
    @DisplayName("the hash is deterministic and is not the token")
    void hash_is_deterministic_and_opaque() {
        InvitationTokens.IssuedToken issued = InvitationTokens.issue();
        assertThat(InvitationTokens.hash(issued.rawToken())).isEqualTo(issued.tokenHash());
        assertThat(issued.tokenHash()).isNotEqualTo(issued.rawToken());
        assertThat(issued.tokenHash()).hasSize(64);
    }

    @Test
    @DisplayName("a different token hashes differently")
    void different_tokens_differ() {
        assertThat(InvitationTokens.hash("a")).isNotEqualTo(InvitationTokens.hash("b"));
    }

    @Test
    @DisplayName("refuses a blank token")
    void refuses_blank() {
        assertThatThrownBy(() -> InvitationTokens.hash(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
