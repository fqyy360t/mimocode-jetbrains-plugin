package ai.mimo.plugin.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

class MiMoConfigurable : Configurable {

    override fun getDisplayName(): String = "MiMoCode"

    override fun createComponent(): JComponent {
        return FormBuilder.createFormBuilder()
            .addComponent(JBLabel("MiMoCode uses the bundled server binary. No configuration needed."))
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    override fun isModified(): Boolean = false

    override fun apply() {}
}
