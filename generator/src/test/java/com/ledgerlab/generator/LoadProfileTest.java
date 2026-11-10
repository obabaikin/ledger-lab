package com.ledgerlab.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LoadProfileTest {

    private static List<PlannedTransfer> next(LoadProfile profile, int count) {
        return Stream.generate(profile::next).limit(count).toList();
    }

    @Test
    void never_sends_from_a_blocked_account_or_to_the_same_account() {
        var profile = TestData.seededProfile(50, 5, 3, LoadProfile.DEFAULT_HOT_SHARE, 1);

        var commands = next(profile, 10_000);

        assertThat(commands).allSatisfy(it -> assertThat(it.fromAccount()).isBetween(1, 45));
        assertThat(commands).allSatisfy(it -> assertThat(it.toAccount()).isNotEqualTo(it.fromAccount()));
        assertThat(commands).allSatisfy(it -> assertThat(it.amountCents()).isPositive());
    }

    @Test
    void needs_at_least_two_active_accounts() {
        assertThatThrownBy(() -> TestData.profile(2, 1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void skews_traffic_towards_hot_accounts() {
        var profile = TestData.seededProfile(100, 2, 5, LoadProfile.DEFAULT_HOT_SHARE, 7);

        var commands = next(profile, 20_000);

        var toHot = commands.stream().filter(it -> it.toAccount() >= 1 && it.toAccount() <= 5).count() / (double) commands.size();
        assertThat(toHot).isBetween(0.30, 0.40);
        assertThat(commands).filteredOn(it -> it.amountCents() != LoadProfile.OVERSIZED_AMOUNT_CENTS)
            .allSatisfy(it -> assertThat(it.amountCents()).isBetween(1L, 2_000L));
    }

    @Test
    @DisplayName("invalid transfers come by count, exactly the share of a run and never both on one transfer")
    void invalid_transfers_come_by_count_exactly_the_share_of_a_run_and_never_both_on_one_transfer() {
        var profile = TestData.seededProfile(100, 2, 5, LoadProfile.DEFAULT_HOT_SHARE, 3);

        var commands = next(profile, 1_000);

        var oversized = new ArrayList<Integer>();
        var toBlocked = new ArrayList<Integer>();
        for (var i = 0; i < commands.size(); i++) {
            if (commands.get(i).amountCents() == LoadProfile.OVERSIZED_AMOUNT_CENTS) {
                oversized.add(i + 1);
            }
            if (commands.get(i).toAccount() >= 99) {
                toBlocked.add(i + 1);
            }
        }
        assertThat(oversized).containsExactly(200, 400, 600, 800, 1_000);
        assertThat(toBlocked).containsExactly(100, 300, 500, 700, 900);
    }

    @Test
    void a_single_hot_account_that_pays_does_not_pay_itself() {
        var profile = TestData.seededProfile(3, 0, 1, 1.0, 5);

        var commands = next(profile, 1_000);

        assertThat(commands).allSatisfy(it -> assertThat(it.toAccount()).isNotEqualTo(it.fromAccount()));
        assertThat(commands).filteredOn(it -> it.fromAccount() == 1).isNotEmpty();
    }
}
