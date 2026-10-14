package io.mateu.workflow.domain.services;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CronDescriptionsTest {

    private static String describe(String cron) {
        return CronDescriptions.describe(cron).orElse(null);
    }

    @Test
    void describesTheShapesPeopleWrite() {
        assertThat(describe("0 0 9 * * *")).isEqualTo("Every day at 09:00");
        assertThat(describe("30 15 9 * * ?")).isEqualTo("Every day at 09:15:30");
        assertThat(describe("0 30 8 * * MON-FRI")).isEqualTo("MON-FRI at 08:30");
        assertThat(describe("0 0 18 * * mon,wed")).isEqualTo("MON,WED at 18:00");
        assertThat(describe("0 0 6 1 * ?")).isEqualTo("Day 1 of every month at 06:00");
        assertThat(describe("0 0 23 L * ?")).isEqualTo("Last day of every month at 23:00");
        assertThat(describe("0 */15 * * * *")).isEqualTo("Every 15 minutes");
        assertThat(describe("*/10 * * * * *")).isEqualTo("Every 10 seconds");
        assertThat(describe("0 * * * * *")).isEqualTo("Every minute");
        assertThat(describe("* * * * * *")).isEqualTo("Every second");
        assertThat(describe("0 5 * * * *")).isEqualTo("Every hour at minute 5");
        assertThat(describe("0 0 */2 * * *")).isEqualTo("Every 2 hours at minute 0");
        assertThat(describe("@daily")).isEqualTo("Every day at 00:00");
        assertThat(describe("@HOURLY")).isEqualTo("Every hour, on the hour");
    }

    @Test
    void saysNothingRatherThanSomethingWrong() {
        assertThat(describe("0 0 9 * JAN *")).isNull();
        assertThat(describe("0 0 9-17 * * *")).isNull();
        assertThat(describe("0 0 9 * * 1-5")).isNull();
        assertThat(describe("0 0 9 * *")).isNull();
        assertThat(describe("@reboot")).isNull();
        assertThat(describe(null)).isNull();
        assertThat(describe(" ")).isNull();
    }
}
