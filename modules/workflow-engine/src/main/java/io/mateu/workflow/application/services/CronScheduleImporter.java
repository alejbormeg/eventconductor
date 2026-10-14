package io.mateu.workflow.application.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.mateu.workflow.analysis.ScheduleRules;
import io.mateu.workflow.application.out.CronScheduleRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.CronFileSchedule;
import io.mateu.workflow.domain.aggregates.CronSchedule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Reads {@code cron.yaml} files and makes their entries the schedules of the source they came from.
 *
 * <p>A cron file is YAML (or JSON), either a bare list of entries or an object whose only key is
 * {@code schedules} holding that list:
 *
 * <pre>
 * - cron: "0 0 9 * * MON-FRI"
 *   workflow: daily-report
 *   zone: Europe/Madrid          # optional, default workflow.time.zone
 *   variables:                   # optional, the started process's initial variables
 *     region: EMEA
 * </pre>
 *
 * <p>An entry that is malformed, has an invalid cron or zone, or names a workflow the engine does not
 * know is reported and left out; the rest of the file still applies. A file that cannot be read at
 * all leaves the source's previous schedules in place rather than silently clearing them.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CronScheduleImporter {

    private static final YAMLMapper YAML = new YAMLMapper();
    private static final Set<String> ENTRY_FIELDS = Set.of("cron", "workflow", "zone", "variables");

    final WorkflowDefinitionRepository workflowDefinitionRepository;
    final CronScheduleRepository cronScheduleRepository;

    /** One cron file of a source, as read off disk or the classpath. */
    public record CronFile(String name, byte[] content) {
    }

    /** One valid entry of a cron file. */
    public record Entry(String workflow, CronSchedule schedule) {
    }

    /** A file's valid entries and what was wrong with the rest; {@code unreadable} when nothing could be read. */
    public record Parsed(List<Entry> entries, List<String> errors, boolean unreadable) {
    }

    /** What an import of one source did. */
    public record Result(List<CronFileSchedule> schedules, List<String> errors) {
    }

    /**
     * Replaces the schedules of {@code source} with the entries of {@code files} (empty clears it).
     * Entries naming an unknown workflow are reported and skipped.
     */
    public Result importSource(String source, List<CronFile> files) {
        var errors = new ArrayList<String>();
        var schedules = new ArrayList<CronFileSchedule>();
        for (var file : files) {
            var parsed = parse(file.name(), file.content());
            errors.addAll(parsed.errors());
            if (parsed.unreadable()) {
                errors.forEach(e -> log.error("{}", e));
                log.error("Schedules from {} left unchanged: {} could not be read", source, file.name());
                return new Result(List.of(), errors);
            }
            for (var entry : parsed.entries()) {
                if (workflowDefinitionRepository.findById(entry.workflow()).isEmpty()) {
                    errors.add(file.name() + ": schedule '" + entry.schedule().cron() + "' targets unknown workflow '"
                            + entry.workflow() + "' — skipped");
                    continue;
                }
                schedules.add(new CronFileSchedule(source, schedules.size(), entry.workflow(), entry.schedule()));
            }
        }
        errors.forEach(e -> log.error("{}", e));
        cronScheduleRepository.replace(source, schedules);
        if (!schedules.isEmpty()) {
            log.info("Loaded {} schedule(s) from {}", schedules.size(), source);
        }
        return new Result(List.copyOf(schedules), errors);
    }

    /** Parses one cron file into its valid entries and the errors of the others. Pure. */
    public static Parsed parse(String fileName, byte[] content) {
        JsonNode root;
        try {
            // YAML reads JSON too.
            root = content == null || content.length == 0 ? null : YAML.readTree(content);
        } catch (Exception e) {
            return new Parsed(List.of(), List.of(fileName + ": could not be parsed (" + e.getMessage() + ")"), true);
        }
        if (root == null || root.isNull() || root.isMissingNode()) {
            return new Parsed(List.of(), List.of(), false);   // an empty file declares no schedules
        }
        JsonNode list = root;
        if (root.isObject()) {
            if (root.size() != 1 || !root.has("schedules")) {
                return unreadable(fileName);
            }
            list = root.get("schedules");
            if (list.isNull()) {
                return new Parsed(List.of(), List.of(), false);
            }
        }
        if (!list.isArray()) {
            return unreadable(fileName);
        }
        var entries = new ArrayList<Entry>();
        var errors = new ArrayList<String>();
        for (int i = 0; i < list.size(); i++) {
            var where = fileName + " entry " + i;
            var node = list.get(i);
            var entryErrors = new ArrayList<String>();
            if (!node.isObject()) {
                errors.add(where + ": expected an object with cron and workflow");
                continue;
            }
            node.fieldNames().forEachRemaining(field -> {
                if (!ENTRY_FIELDS.contains(field)) {
                    entryErrors.add(where + ": unknown field '" + field + "' (allowed: cron, workflow, zone, variables)");
                }
            });
            var cron = text(node, "cron", where, entryErrors);
            var workflow = text(node, "workflow", where, entryErrors);
            var zone = text(node, "zone", where, entryErrors);
            if (workflow == null || workflow.isBlank()) {
                entryErrors.add(where + ": a schedule needs the id of the workflow it starts ('workflow')");
            }
            entryErrors.addAll(ScheduleRules.violations(cron, zone, where));
            var variables = variables(node.get("variables"), where, entryErrors);
            if (entryErrors.isEmpty()) {
                entries.add(new Entry(workflow.trim(), new CronSchedule(cron, zone, variables)));
            } else {
                errors.addAll(entryErrors);
            }
        }
        return new Parsed(entries, errors, false);
    }

    private static Parsed unreadable(String fileName) {
        return new Parsed(List.of(), List.of(fileName
                + ": expected a list of schedules ({cron, workflow, zone?, variables?}) or an object with a single 'schedules' key"),
                true);
    }

    private static String text(JsonNode node, String field, String where, List<String> errors) {
        var value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            errors.add(where + ": '" + field + "' must be a string");
            return null;
        }
        return value.asText();
    }

    private static LinkedHashMap<String, String> variables(JsonNode node, String where, List<String> errors) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            errors.add(where + ": 'variables' must be a map of name: value");
            return null;
        }
        var variables = new LinkedHashMap<String, String>();
        node.fields().forEachRemaining(field -> {
            var value = field.getValue();
            if (value.isValueNode() && !value.isNull()) {
                variables.put(field.getKey(), value.asText());
            } else {
                errors.add(where + ": variable '" + field.getKey() + "' must be a string, number or boolean");
            }
        });
        return variables;
    }
}
