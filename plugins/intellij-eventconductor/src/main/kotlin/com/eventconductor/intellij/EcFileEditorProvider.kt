package com.eventconductor.intellij

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Opens `.ec` files in a split editor: the text (YAML/JSON) on one side and the interactive
 * EventConductor graph on the other. The toolbar's editor/split/preview toggles give the
 * "edit as text or as graph" experience for free, and both sides share the same document.
 */
class EcFileEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean =
        file.extension?.equals("ec", ignoreCase = true) == true

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val textEditor = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
        val graphEditor = EcGraphFileEditor(project, file)
        return EcSplitEditor(textEditor, graphEditor)
    }

    override fun getEditorTypeId(): String = "eventconductor-ec-editor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/**
 * The split editor, plus "Export Graph as SVG" in its toolbar — beside the editor / split / graph
 * toggles, wherever the IDE puts those: in the editor's own toolbar (classic UI, or the new UI's
 * floating one) or in the tab bar (new UI with tabs on top).
 */
private class EcSplitEditor(
    textEditor: TextEditor,
    private val graphEditor: EcGraphFileEditor,
) : TextEditorWithPreview(
    textEditor,
    graphEditor,
    "EventConductor",
    Layout.SHOW_EDITOR_AND_PREVIEW,
) {
    // Two instances: the toolbar one steps aside when the toggles live in the tab bar, where the
    // tab one is shown instead — otherwise the button would appear twice.
    override fun createRightToolbarActionGroup(): ActionGroup =
        DefaultActionGroup(ExportGraphAction(graphEditor) { !isShowActionsInTabs })

    override fun createTabActions(): Array<AnAction> =
        arrayOf(ExportGraphAction(graphEditor) { true }, *super.createTabActions())
}

/** Exports the graph as a standalone SVG through the page, then a save dialog (see [EcGraphFileEditor]). */
private class ExportGraphAction(
    private val graphEditor: EcGraphFileEditor,
    private val visible: () -> Boolean,
) : DumbAwareAction(
    "Export Graph as SVG",
    "Save the workflow graph as a standalone SVG image",
    AllIcons.ToolbarDecorator.Export,
) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = visible()
    }

    override fun actionPerformed(e: AnActionEvent) = graphEditor.requestExport()
}
