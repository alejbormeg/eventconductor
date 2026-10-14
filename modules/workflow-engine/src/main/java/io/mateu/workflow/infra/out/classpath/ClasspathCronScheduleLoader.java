package io.mateu.workflow.infra.out.classpath;

import io.mateu.workflow.application.services.CronScheduleImporter;
import jakarta.annotation.PreDestroy;
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
 *
 * <p>A database that is down at startup does not stop the boot (the engine is meant to come up and
 * wait for it, DIST-08): the failure is logged and the load retried in the background, with backoff,
 * until it goes through. Until then the schedules a previous deployment stored keep firing.
 */
@Service
@RequiredArgsConstructor
@Order(1)
@Slf4j
public class ClasspathCronScheduleLoader implements ApplicationRunner {

    /** The source key classpath schedules are recorded under. */
    public static final String SOURCE = "classpath:/workflows/cron.yaml";

    private static final String BASE = "classpath:/workflows/";

    static final long FIRST_RETRY_DELAY_MS = 5_000;
    static final long MAX_RETRY_DELAY_MS = 60_000;

    final CronScheduleImporter cronScheduleImporter;

    private volatile Thread retryThread;

    @Override
    public void run(ApplicationArguments args) {
        loadOrRetryInBackground(FIRST_RETRY_DELAY_MS);
    }

    /** Loads now; if storing fails (database down), keeps retrying on a daemon thread until it works. */
    void loadOrRetryInBackground(long firstDelayMs) {
        try {
            load();
        } catch (RuntimeException e) {
            log.warn("Could not store the classpath cron schedules ({}); retrying in the background", e.getMessage());
            var thread = new Thread(() -> retryUntilLoaded(firstDelayMs), "workflow-cron-classpath-retry");
            thread.setDaemon(true);
            retryThread = thread;
            thread.start();
        }
    }

    private void retryUntilLoaded(long firstDelayMs) {
        var delay = firstDelayMs;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(delay);
                load();
                log.info("Stored the classpath cron schedules after an earlier failure");
                return;
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                log.debug("Classpath cron schedules still not stored: {}", e.getMessage());
                delay = Math.min(delay * 2, MAX_RETRY_DELAY_MS);
            }
        }
    }

    @PreDestroy
    void stop() {
        var thread = retryThread;
        if (thread != null) {
            thread.interrupt();
        }
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
