package com.eventconductor.intellij

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileTypes.ExtensionFileNameMatcher
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.jsonSchema.ide.JsonSchemaService
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.schema.YamlJsonSchemaHighlightingInspection

/**
 * What the user sees when opening a definition: the file is YAML (highlighting, PSI), the workflow
 * schema is associated with it, and a mistake in it is reported — for `.ec` and for the compound
 * `*.workflow.yaml` / `*.ec.yaml` names alike.
 */
class EcFileTypeTest : BasePlatformTestCase() {

    private val workflow = """
        name: Order
        steps:
          - id: start
            type: START
            name: Start
    """.trimIndent() + "\n"

    fun testDefinitionExtensionsAreOurYamlFileType() {
        for (name in listOf("order.ec", "contact.ecform", "pricing.ecrule", "send-email.ectask")) {
            val file = myFixture.configureByText(name, "name: x\n")
            assertEquals(name, EcFileType, file.virtualFile.fileType)
            assertTrue("$name should parse as YAML", file is YAMLFile)
        }
    }

    fun testCompoundWorkflowSuffixesStayYamlAndAreWorkflows() {
        for (name in listOf("order.workflow.yaml", "order.workflow.yml", "order.ec.yaml", "order.ec.yml")) {
            val file = myFixture.configureByText(name, workflow)
            assertEquals(name, YAMLFileType.YML, file.virtualFile.fileType)
            assertTrue(name, EcFiles.isWorkflow(file.virtualFile))
        }
        val plain = myFixture.configureByText("application.yaml", "a: 1\n")
        assertFalse(EcFiles.isWorkflow(plain.virtualFile))
    }

    /**
     * The user's IDE had `<removed_mapping ext="ec" type="YAML" approved="true"/>`: `*.ec` taken off
     * the YAML file type for good. That no longer reaches `.ec`, which is not YAML's to lose.
     */
    fun testRemovingEcFromTheYamlFileTypeDoesNotUnmapIt() {
        removeEcFromYaml()
        assertEquals(EcFileType, FileTypeManager.getInstance().getFileTypeByFileName("order.ec"))
    }

    // Not inline in the test: JUnit 3 would take the lambda's synthetic `test...$lambda$0` for a test.
    private fun removeEcFromYaml() {
        WriteAction.runAndWait<Throwable> {
            FileTypeManager.getInstance().removeAssociation(YAMLFileType.YML, ExtensionFileNameMatcher("ec"))
        }
    }

    fun testTheWorkflowSchemaIsAssociatedWithEveryWorkflowName() {
        for (name in listOf("order.ec", "order.workflow.yaml", "order.ec.yml")) {
            val file = myFixture.configureByText(name, workflow)
            val schemas = JsonSchemaService.Impl.get(project).getSchemaFilesForFile(file.virtualFile)
            assertTrue("$name: $schemas", schemas.any { it.name == "ec.schema.json" })
        }
        val other = myFixture.configureByText("application.yaml", "a: 1\n")
        val schemas = JsonSchemaService.Impl.get(project).getSchemaFilesForFile(other.virtualFile)
        assertFalse("$schemas", schemas.any { it.name == "ec.schema.json" })
    }

    fun testASchemaViolationIsReported() {
        myFixture.enableInspections(YamlJsonSchemaHighlightingInspection::class.java)
        for (name in listOf("bad.ec", "bad.workflow.yaml")) {
            // `steps` missing and an unknown top-level key: both are schema errors.
            myFixture.configureByText(name, "name: Order\nnotAProperty: 1\n")
            val problems = myFixture.doHighlighting().filter { it.severity >= HighlightSeverity.WEAK_WARNING }
            assertTrue("$name: expected schema problems, got $problems", problems.isNotEmpty())
        }
        myFixture.configureByText("good.ec", workflow)
        val problems = myFixture.doHighlighting().filter { it.severity >= HighlightSeverity.WEAK_WARNING }
        assertTrue("good.ec: $problems", problems.isEmpty())
    }
}
