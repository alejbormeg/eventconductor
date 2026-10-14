package com.eventconductor.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType

/**
 * Associates the bundled workflow-definition JSON schema with every workflow file — `.ec` (JSON or
 * YAML), `*.workflow.yaml` / `*.ec.yaml` and their `.yml` twins (see [EcFiles]).
 */
class EcJsonSchemaProviderFactory : JsonSchemaProviderFactory {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> = listOf(EcSchemaProvider())
}

class EcSchemaProvider : JsonSchemaFileProvider {

    override fun isAvailable(file: VirtualFile): Boolean =
        EcFiles.isWorkflow(file)

    override fun getName(): String = "EventConductor"

    override fun getSchemaFile(): VirtualFile? =
        JsonSchemaProviderFactory.getResourceFile(EcSchemaProvider::class.java, "/schema/ec.schema.json")

    override fun getSchemaType(): SchemaType = SchemaType.embeddedSchema
}
