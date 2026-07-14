package ai.mimo.plugin.bridge

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.JBColor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.event.ChangeListener
import java.awt.Color
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class IdeContextService(private val project: Project) : Disposable {

    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var caretTask: ScheduledFuture<*>? = null

    init {
        subscribeToFileEditorEvents()
        subscribeToCaretEvents()
        subscribeToThemeEvents()
    }

    fun sendCurrentState() {
        sendTheme()
        sendTabs()
        sendActiveEditor()
        sendProjectInfo()
    }

    fun addCurrentSelectionToContext() {
        val mgr = FileEditorManager.getInstance(project)
        val editor = (mgr.selectedEditor as? TextEditor)?.editor ?: return
        val selectionModel = editor.selectionModel
        if (!selectionModel.hasSelection()) return

        val selectedText = selectionModel.selectedText ?: return
        val file = mgr.selectedFiles.firstOrNull() ?: return
        val startLine = selectionModel.selectionStart
        val endLine = selectionModel.selectionEnd

        // Calculate line numbers
        val doc = editor.document
        val startLineNumber = doc.getLineNumber(startLine) + 1
        val endLineNumber = doc.getLineNumber(endLine) + 1

        project.service<BrowserBridge>().sendSelection(
            file.path,
            startLineNumber,
            endLineNumber,
            selectedText,
            file.fileType.name
        )
    }

    fun addActiveFileToContext() {
        val mgr = FileEditorManager.getInstance(project)
        val editor = (mgr.selectedEditor as? TextEditor)?.editor ?: return
        val file = mgr.selectedFiles.firstOrNull() ?: return
        val doc = editor.document
        val caret = editor.caretModel.primaryCaret
        val caretLine = doc.getLineNumber(caret.offset) + 1

        // Send the entire active file as context
        val content = doc.text
        val lineCount = doc.lineCount

        project.service<BrowserBridge>().sendSelection(
            file.path,
            1,
            lineCount,
            content,
            file.fileType.name
        )
    }

    private fun subscribeToFileEditorEvents() {
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    sendTabs()
                    sendActiveEditor()
                }
                override fun fileOpened(src: FileEditorManager, file: VirtualFile) {
                    sendTabs()
                }
                override fun fileClosed(src: FileEditorManager, file: VirtualFile) {
                    sendTabs()
                }
            }
        )
    }

    private fun subscribeToCaretEvents() {
        EditorFactory.getInstance().addEditorFactoryListener(
            object : EditorFactoryListener {
                override fun editorCreated(event: EditorFactoryEvent) {
                    val editor = event.editor
                    if (editor.project != project) return
                    editor.caretModel.addCaretListener(object : CaretListener {
                        override fun caretPositionChanged(e: CaretEvent) {
                            scheduleCaretUpdate(editor)
                        }
                    })
                }
            },
            this
        )
    }

    private fun subscribeToThemeEvents() {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            EditorColorsManager.TOPIC,
            EditorColorsListener { sendTheme() }
        )
    }

    private fun sendActiveEditor() {
        val mgr = FileEditorManager.getInstance(project)
        val editor = (mgr.selectedEditor as? TextEditor)?.editor ?: return
        val file = mgr.selectedFiles.firstOrNull() ?: return

        ApplicationManager.getApplication().runReadAction {
            dispatchEditorState(file, editor)
        }
    }

    private fun scheduleCaretUpdate(editor: Editor) {
        caretTask?.cancel(false)
        caretTask = executor.schedule({
            ApplicationManager.getApplication().runReadAction {
                val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
                if (file != null && editor.project == project) {
                    dispatchEditorState(file, editor)
                }
            }
        }, 500, TimeUnit.MILLISECONDS)
    }

    private fun getSurrounding(editor: Editor, caretLine: Int, radius: Int): String {
        val doc = editor.document
        val start = maxOf(0, caretLine - radius)
        val end = minOf(doc.lineCount - 1, caretLine + radius)
        return buildString {
            for (l in start..end) {
                val s = doc.getLineStartOffset(l)
                val e = doc.getLineEndOffset(l)
                append(doc.getText(com.intellij.openapi.util.TextRange(s, e)))
                append('\n')
            }
        }
    }

    private fun dispatchEditorState(file: VirtualFile, editor: Editor) {
        val caret = editor.caretModel.primaryCaret
        val pos = caret.logicalPosition
        val line = pos.line + 1
        val col = pos.column + 1
        val doc = editor.document
        val lineCount = doc.lineCount
        val surrounding = getSurrounding(editor, pos.line, 50)
        val lang = file.fileType.name

        project.service<BrowserBridge>().sendActiveEditor(file.path, lang, line, col, lineCount, surrounding)
    }

    private fun sendTabs() {
        val mgr = FileEditorManager.getInstance(project)
        val active = mgr.selectedFiles.firstOrNull()
        val tabs = mgr.openFiles.map { f ->
            BrowserBridge.TabPayload(
                path = f.path,
                name = f.name,
                active = f == active,
                modified = false,
            )
        }
        project.service<BrowserBridge>().sendTabs(tabs)
    }

    private fun sendTheme() {
        val dark = !JBColor.isBright()
        val bg = JBColor.background()
        val fg = JBColor.foreground()
        val accent = JBColor.namedColor("Link.activeForeground", JBColor.BLUE)
        val border = JBColor.namedColor("Component.borderColor", JBColor.GRAY)

        project.service<BrowserBridge>().sendTheme(
            dark,
            colorToHex(bg),
            colorToHex(fg),
            colorToHex(accent),
            colorToHex(border),
        )
    }

    private fun colorToHex(c: Color): String {
        val rgb = c.rgb and 0xFFFFFF
        return "#${Integer.toString(rgb, 16).padStart(6, '0')}"
    }

    private fun sendProjectInfo() {
        val dir = project.basePath ?: return
        val name = project.name
        val branch = try {
            readGitBranch(dir)
        } catch (_: Exception) {
            null
        }
        project.service<BrowserBridge>().sendProjectInfo(dir, name, branch)
    }

    private fun readGitBranch(dir: String): String? {
        val head = File("$dir/.git/HEAD")
        if (!head.exists()) return null
        val raw = head.readText().trim()
        return if (raw.startsWith("ref: refs/heads/")) {
            raw.removePrefix("ref: refs/heads/")
        } else {
            null
        }
    }

    override fun dispose() {
        executor.shutdownNow()
    }
}
