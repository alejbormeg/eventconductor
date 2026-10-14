package io.mateu.workflow.analysis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduleRulesTest {

    @Test
    void acceptsSixFieldSpringCronAndMacros() {
        assertThat(ScheduleRules.isValidCron("0 0 9 * * MON-FRI")).isTrue();
        assertThat(ScheduleRules.isValidCron("  * * * * * *  ")).isTrue();
        assertThat(ScheduleRules.isValidCron("@daily")).isTrue();
    }

    @Test
    void rejectsFiveFieldUnixCronGarbageAndBlank() {
        assertThat(ScheduleRules.isValidCron("0 9 * * *")).isFalse();
        assertThat(ScheduleRules.isValidCron("every day")).isFalse();
        assertThat(ScheduleRules.isValidCron(" ")).isFalse();
        assertThat(ScheduleRules.isValidCron(null)).isFalse();
    }

    @Test
    void zoneIsOptionalButMustBeReadableWhenGiven() {
        assertThat(ScheduleRules.isValidZone(null)).isTrue();
        assertThat(ScheduleRules.isValidZone("")).isTrue();
        assertThat(ScheduleRules.isValidZone("Europe/Madrid")).isTrue();
        assertThat(ScheduleRules.isValidZone("UTC")).isTrue();
        assertThat(ScheduleRules.isValidZone("Mars/Olympus")).isFalse();
        assertThat(ScheduleRules.isValidZone("not a zone!")).isFalse();
    }

    @Test
    void violationsNameWhereTheyAre() {
        assertThat(ScheduleRules.violations("0 0 9 * * *", "Europe/Madrid", "schedules[0]")).isEmpty();
        assertThat(ScheduleRules.violations("0 9 * * *", "Mars/Olympus", "schedules[1]"))
                .containsExactly(
                        "schedules[1]: '0 9 * * *' is not a valid cron expression (six fields, seconds first, e.g. \"0 0 9 * * MON-FRI\")",
                        "schedules[1]: 'Mars/Olympus' is not a valid time zone (an IANA id such as Europe/Madrid)");
        assertThat(ScheduleRules.violations(null, null, null))
                .containsExactly("a schedule needs a cron expression");
    }

    @Test
    void recognisesScheduleFilesByTheirLastSegmentOnly() {
        assertThat(ScheduleRules.isCronFileName("cron.yaml")).isTrue();
        assertThat(ScheduleRules.isCronFileName("CRON.YML")).isTrue();
        assertThat(ScheduleRules.isCronFileName("/repo/workflows/cron.yaml")).isTrue();
        assertThat(ScheduleRules.isCronFileName("C:\\repo\\cron.yml")).isTrue();
        assertThat(ScheduleRules.isCronFileName("nightly-cron.yaml")).isFalse();
        assertThat(ScheduleRules.isCronFileName("cron.yaml/order.ec")).isFalse();
        assertThat(ScheduleRules.isCronFileName("cron.json")).isFalse();
        assertThat(ScheduleRules.isCronFileName(null)).isFalse();
    }

    @Test
    void anIdIsOptionalAndASlugWhenGiven() {
        assertThat(ScheduleRules.isValidId(null)).isTrue();
        assertThat(ScheduleRules.isValidId(" ")).isTrue();
        assertThat(ScheduleRules.isValidId("nightly-report")).isTrue();
        assertThat(ScheduleRules.isValidId("Billing.EOM_close-2")).isTrue();
        assertThat(ScheduleRules.isValidId("-leading-dash")).isFalse();
        assertThat(ScheduleRules.isValidId("with space")).isFalse();
        assertThat(ScheduleRules.isValidId("daily~f1234")).isFalse();
        assertThat(ScheduleRules.isValidId("a/b")).isFalse();
        assertThat(ScheduleRules.isValidId("x".repeat(ScheduleRules.MAX_ID_LENGTH))).isTrue();
        assertThat(ScheduleRules.isValidId("x".repeat(ScheduleRules.MAX_ID_LENGTH + 1))).isFalse();
        assertThat(ScheduleRules.violations("bad id", "0 0 9 * * *", null, "entry 0"))
                .containsExactly("entry 0: 'bad id' is not a valid schedule id (letters, digits, '.', '_' and '-',"
                        + " starting with a letter or digit, at most 100 characters)");
    }

    @Test
    void anIdDeclaredTwiceInOneListIsReportedOnce() {
        assertThat(ScheduleRules.duplicateIdViolations(java.util.Arrays.asList("a", null, "b", "a", "a", " "), "cron.yaml"))
                .containsExactly("cron.yaml: schedule id 'a' is declared more than once");
        assertThat(ScheduleRules.duplicateIdViolations(java.util.List.of("a", "b"), null)).isEmpty();
    }
}
