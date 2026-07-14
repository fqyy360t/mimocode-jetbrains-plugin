package ai.mimo.plugin.actions

import ai.mimo.plugin.bridge.BrowserBridge
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager

class AddToContextAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selectionModel = editor.selectionModel
        if (!selectionModel.hasSelection()) return

        val startLine = selectionModel.selectionStart.let { editor.document.getLineNumber(it) + 1 }
        val endLine = selectionModel.selectionEnd.let { editor.document.getLineNumber(it) + 1 }
        val code = selectionModel.selectedText ?: return

        val virtualFile = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val language = virtualFile.fileType.name

        project.service<BrowserBridge>().sendSelection(
            virtualFile.path,
            startLine,
            endLine,
            code,
            language,
        )
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor?.selectionModel?.hasSelection() == true
    }
}
