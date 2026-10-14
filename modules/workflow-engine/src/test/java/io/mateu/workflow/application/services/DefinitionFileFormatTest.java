package io.mateu.workflow.application.services;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DefinitionFileFormatTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void recognisesEveryDefinitionExtension() {
        assertThat(DefinitionFileFormat.isDefinitionFileName("order.ec")).isTrue();
        assertThat(DefinitionFileFormat.isDefinitionFileName("order.json")).isTrue();
        assertThat(DefinitionFileFormat.isDefinitionFileName("order.yaml")).isTrue();
        assertThat(DefinitionFileFormat.isDefinitionFileName("order.yml")).isTrue();
        assertThat(DefinitionFileFormat.isDefinitionFileName("ORDER.EC")).isTrue(); // case-insensitive
        assertThat(DefinitionFileFormat.isDefinitionFileName("readme.md")).isFalse();
        assertThat(DefinitionFileFormat.isDefinitionFileName(null)).isFalse();
    }

    @Test
    void recognisesTheCompoundWorkflowSuffixes() {
        assertThat(DefinitionFileFormat.YAML_WORKFLOW_SUFFIXES)
                .containsExactly(".workflow.yaml", ".workflow.yml", ".ec.yaml", ".ec.yml");
        for (var suffix : DefinitionFileFormat.YAML_WORKFLOW_SUFFIXES) {
            assertThat(DefinitionFileFormat.isDefinitionFileName("order" + suffix)).as(suffix).isTrue();
            assertThat(DefinitionFileFormat.isDefinitionFileName("ORDER" + suffix.toUpperCase())).as(suffix).isTrue();
        }
    }

    @Test
    void theCompoundSuffixesAreAlwaysYaml() {
        // They end in .yaml/.yml, so the content is never sniffed — even when it is a JSON document
        // (which YAML reads anyway).
        assertThat(DefinitionFileFormat.isYaml("order.workflow.yaml", bytes("{\"name\":\"a\"}"))).isTrue();
        assertThat(DefinitionFileFormat.isYaml("order.workflow.yml", bytes("name: a"))).isTrue();
        assertThat(DefinitionFileFormat.isYaml("order.ec.yaml", bytes("[1]"))).isTrue();
        assertThat(DefinitionFileFormat.isYaml("order.ec.yml", bytes("name: a"))).isTrue();
    }

    @Test
    void theClasspathPatternsDoNotOverlap() {
        // *.yaml / *.yml already cover the compound suffixes; a pattern of their own would load each
        // such file twice. (And no *.{yaml,yml}: AntPathMatcher reads the braces as a variable that
        // matches any extension — ClasspathDefinitionSuffixesTest catches that.)
        assertThat(DefinitionFileFormat.CLASSPATH_PATTERNS).containsExactly("*.json", "*.yaml", "*.yml", "*.ec");
    }

    @Test
    void picksParserByExtensionRegardlessOfContent() {
        assertThat(DefinitionFileFormat.isYaml("x.yaml", bytes("{}"))).isTrue();
        assertThat(DefinitionFileFormat.isYaml("x.yml", bytes("name: a"))).isTrue();
        assertThat(DefinitionFileFormat.isYaml("x.json", bytes("name: a"))).isFalse();
    }

    @Test
    void sniffsEcContentToChooseJsonOrYaml() {
        assertThat(DefinitionFileFormat.isYaml("order.ec", bytes("  \n { \"name\": \"a\" }"))).isFalse(); // JSON object
        assertThat(DefinitionFileFormat.isYaml("order.ec", bytes("[1,2]"))).isFalse();                    // JSON array
        assertThat(DefinitionFileFormat.isYaml("order.ec", bytes("name: a\nsteps: []"))).isTrue();         // YAML
        assertThat(DefinitionFileFormat.isYaml("order.ec", bytes("   "))).isTrue();                        // blank → YAML default
        assertThat(DefinitionFileFormat.isYaml("order.ec", null)).isTrue();
    }
}
