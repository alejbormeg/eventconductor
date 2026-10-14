package com.eventconductor.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType

/**
 * Associates the bundled cron schema with `cron.yaml` / `cron.yml` — the schedule file that sits next
 * to the workflow definitions (a list of `{cron, workflow, zone?, variables?}`), validated by the
 * engine and the Maven plugin against the very same schema.
 */
class CronJsonSchemaProviderFactory : JsonSchemaProviderFactory {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> = listOf(CronSchemaProvider())
}

class CronSchemaProvider : JsonSchemaFileProvider {

    override fun isAvailable(file: VirtualFile): Boolean =
        file.name.equals("cron.yaml", ignoreCase = true) || file.name.equals("cron.yml", ignoreCase = true)

    override fun getName(): String = "EventConductor schedules (cron.yaml)"

    override fun getSchemaFile(): VirtualFile? =
        JsonSchemaProviderFactory.getResourceFile(CronSchemaProvider::class.java, "/schema/cron.schema.json")

    override fun getSchemaType(): SchemaType = SchemaType.embeddedSchema
}
