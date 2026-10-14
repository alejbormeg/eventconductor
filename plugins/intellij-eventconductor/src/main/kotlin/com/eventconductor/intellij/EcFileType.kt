package com.eventconductor.intellij

import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.YAMLLanguage
import javax.swing.Icon

/**
 * The file type of `.ec`, `.ecform`, `.ecrule` and `.ectask`: YAML (a JSON superset), under a name
 * of its own.
 *
 * These extensions used to be added to the YAML plugin's own file type
 * (`<fileType name="YAML" extensions="ec;..."/>`). That works until the IDE records a
 * `<removed_mapping ext="ec" type="YAML" approved="true"/>` in its `filetypes.xml`. The platform
 * writes one for every mapping that is missing when the File Types settings page is applied (so a
 * page whose snapshot predates the plugin's mapping drops it), and when a conflict between two file
 * types claiming the extension is resolved against YAML. From then on the IDE removes `*.ec` from YAML on every
 * start, whatever the plugin declares and whatever version is installed: the file is plain text,
 * so it has no YAML PSI, the YAML JSON-schema enabler (which wants a [LanguageFileType] whose
 * language is YAML) rejects it, and there is neither highlighting nor validation.
 *
 * A file type of our own is keyed by its own name, so that stale YAML entry no longer touches it,
 * and a user who really wants `.ec` elsewhere removes it from this type — which the IDE then
 * respects. Its language is YAML, so highlighting, formatting, the YAML inspections and the
 * JSON-schema providers all apply exactly as before. It is a *secondary* file type of the language,
 * so YAML's own file type stays the one the language is associated with.
 *
 * The compound workflow suffixes (`*.workflow.yaml`, `*.ec.yaml`, ...) are not claimed here: they
 * end in `.yaml`, so they already are YAML files, and the schema and editor providers recognise them
 * by name ([EcFiles]).
 */
object EcFileType : LanguageFileType(YAMLLanguage.INSTANCE, true) {
    override fun getName(): String = "EventConductor"

    override fun getDescription(): String = "EventConductor definition (YAML or JSON)"

    override fun getDefaultExtension(): String = "ec"

    override fun getIcon(): Icon? = YAMLFileType.YML.icon
}

/** Which files hold which kind of EventConductor definition, by name. */
object EcFiles {

    /**
     * The workflow-definition file names: the `.ec` extension and the compound suffixes that say
     * "workflow" while staying `.yaml` files. Must match the engine's `DefinitionFileFormat` and the
     * VS Code extension's `customEditors` / `yamlValidation` entries.
     */
    val WORKFLOW_SUFFIXES = listOf(".ec", ".workflow.yaml", ".workflow.yml", ".ec.yaml", ".ec.yml")

    fun isWorkflow(file: VirtualFile): Boolean {
        val name = file.name.lowercase()
        return WORKFLOW_SUFFIXES.any { name.endsWith(it) }
    }
}
