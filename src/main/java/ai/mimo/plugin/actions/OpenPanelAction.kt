package ai.mimo.plugin.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager

class OpenPanelAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("MiMoCode") ?: return
        toolWindow.activate(null)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
