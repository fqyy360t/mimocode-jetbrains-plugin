package ai.mimo.plugin.toolwindow

import ai.mimo.plugin.bridge.BrowserBridge
import ai.mimo.plugin.bridge.IdeContextService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import javax.swing.JLabel

class MiMoToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val factory = ContentFactory.getInstance()

        if (!JBCefApp.isSupported()) {
            val label = JLabel("JCEF is not available in this IDE environment.")
            toolWindow.contentManager.addContent(factory.createContent(label, "", false))
            return
        }

        val bridge = project.service<BrowserBridge>()
        project.service<IdeContextService>() // initialize
        bridge.load()

        val content = factory.createContent(bridge.browser.component, "", false)
        content.setDisposer(bridge)
        toolWindow.contentManager.addContent(content)
    }
}
