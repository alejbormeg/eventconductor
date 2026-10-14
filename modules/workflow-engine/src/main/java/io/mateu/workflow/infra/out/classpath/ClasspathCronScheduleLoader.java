package io.mateu.workflow.infra.out.classpath;

import io.mateu.workflow.application.services.CronScheduleImporter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Loads {@code classpath:/workflows/cron.yaml} (or {@code cron.yml}) at startup, in both persistence
 * modes, after the classpath definitions are in place ({@code @Order(1)}; the JPA classpath importer is
 * {@code @Order(0)}, the memory repository loads in its constructor) so its entries can be checked
 * against them.
 *
 * <p>Always replaces the classpath source, so a deployment that removed an entry — or the whole file
 * — also removes the schedule a previous deployment left in the database.
 */
@Service
@RequiredArgsConstructor
@Order(1)
@Slf4j
public class ClasspathCronScheduleLoader implements ApplicationRunner {

    /** The source key classpath schedules are recorded under. */
    public static final String SOURCE = "classpath:/workflows/cron.yaml";

    private static final String BASE = "classpath:/workflows/";

    final CronScheduleImporter cronScheduleImporter;

    @Override
    public void run(ApplicationArguments args) {
        load();
    }

    /** Reads the classpath cron file(s) and replaces the classpath source's schedules. */
    public CronScheduleImporter.Result load() {
        return load(BASE);
    }

    /** As {@link #load()}, reading the cron file(s) under {@code base} (tests point it elsewhere). */
    CronScheduleImporter.Result load(String base) {
        var resolver = new PathMatchingResourcePatternResolver();
        var files = new ArrayList<CronScheduleImporter.CronFile>();
        for (var name : List.of("cron.yaml", "cron.yml")) {
            var location = base + name;
            var resource = resolver.getResource(location);
            if (!resource.exists()) {
                continue;
            }
            try (var in = resource.getInputStream()) {
                files.add(new CronScheduleImporter.CronFile(location, in.readAllBytes()));
            } catch (Exception e) {
                log.error("Could not read {}: {}", location, e.getMessage());
                return new CronScheduleImporter.Result(List.of(), List.of(location + ": " + e.getMessage()));
            }
        }
        return cronScheduleImporter.importSource(SOURCE, files);
    }
}
