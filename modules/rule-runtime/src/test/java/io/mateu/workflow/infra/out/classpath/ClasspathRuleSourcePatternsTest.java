package io.mateu.workflow.infra.out.classpath;

import io.mateu.workflow.application.services.RuleJsonMapper;
import io.mateu.workflow.domain.Rule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The classpath rule source reads {@code .ecrule}, {@code .json}, {@code .yaml} and {@code .yml},
 * each file exactly once, and nothing else. The classpath is only a throwaway directory (platform
 * loader as parent, so the test resources' own rules/ stay out), set as the context class loader.
 */
class ClasspathRuleSourcePatternsTest {

    @TempDir
    Path root;

    private static String yaml(String id) {
        return """
                id: %s
                name: %s
                type: expression
                when: "true"
                then:
                  - name: ok
                    expression: "true"
                """.formatted(id, id);
    }

    @Test
    void readsEveryRuleExtensionOnceAndNothingElse() throws Exception {
        var rules = Files.createDirectories(root.resolve("rules"));
        Files.writeString(rules.resolve("a.ecrule"), yaml("from-ecrule"));
        Files.writeString(rules.resolve("b.yaml"), yaml("from-yaml"));
        Files.writeString(rules.resolve("c.yml"), yaml("from-yml"));
        Files.writeString(rules.resolve("d.json"), """
                {"id":"from-json","name":"from-json","type":"expression","when":"true",
                 "then":[{"name":"ok","expression":"true"}]}""");
        Files.writeString(rules.resolve("notes.md"), "not a rule");

        var mapper = spy(new RuleJsonMapper());
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        ClasspathRuleSource source;
        try (var loader = new URLClassLoader(new URL[]{root.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            thread.setContextClassLoader(loader);
            source = new ClasspathRuleSource(mapper);
        } finally {
            thread.setContextClassLoader(previous);
        }

        assertThat(source.findAll()).extracting(Rule::id)
                .containsExactlyInAnyOrder("from-ecrule", "from-yaml", "from-yml", "from-json");
        // Four files parsed, four times: no file read twice, notes.md never tried.
        verify(mapper, times(4)).toRule(anyString());
    }
}
