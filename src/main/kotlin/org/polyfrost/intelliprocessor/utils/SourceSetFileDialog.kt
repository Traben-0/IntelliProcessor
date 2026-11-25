package org.polyfrost.intelliprocessor.utils

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.CheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.polyfrost.intelliprocessor.config.PluginSettings
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.awt.GridLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

class SourceSetFileDialog(
    project: Project,
    sourceFilesUnsorted: List<SourceSetFile>,
    private val hadConditions: Boolean,
    private val onFileChosen: (SourceSetFile) -> Unit,
) : DialogWrapper(project, true, IdeModalityType.MODELESS) {

    // Sort entries via int version as 1.8.9 will order before 1.12.2 otherwise
    private val sourceFiles = sourceFilesUnsorted.sortedBy { it.version.mc }

    private val listModel = CollectionListModel(sourceFiles)
    private val list = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = ListCellRenderer { list, value, index, isSelected, cellHasFocus ->
            // Use DefaultListCellRenderer to get selection colors etc
            fun String.label() = DefaultListCellRenderer().getListCellRendererComponent(
                list,
                this,
                index,
                isSelected,
                cellHasFocus
            ).apply {
                @Suppress("UseJBColor")
                fun Color.fadeTo(other: Color, strength: Float) = Color(
                    red + ((other.red - red) / strength).toInt(),
                    green + ((other.green - green) / strength).toInt(),
                    blue + ((other.blue - blue) / strength).toInt(),
                )

                // Further differentiate preprocessed generated files with font style
                if (value.isNonGenerated) {
                    font = font.deriveFont(Font.BOLD)
                    foreground = if (value.isSrc) JBColor.GREEN else JBColor.CYAN
                }
                if (!value.exists || value.hasMixinErrors()) {
                    foreground = JBColor.RED.fadeTo(foreground, 2f)
                }
                if (!value.metOpeningCondition) {
                    foreground = foreground.fadeTo(JBColor.GRAY, 1.2f)
                }
                if (value.hasMixinErrors()) {
                    background = JBColor.RED.fadeTo(background, 1.2f)
                }
            }

            JPanel(GridLayout(1, 2)).apply {
                val stringParts = value.toString().split("|")
                add(JPanel(GridLayout(1, 2)).apply {
                    add(" ${stringParts[0]}".label())
                    add("| ${stringParts[1]}".label())
                })
                if (value.exists) {
                    add("| ${stringParts[2]}".label())
                    if (value.hasMixinErrors()) toolTipText = "File has Mixin inspection errors, likely caused by version differences."
                } else {
                    add("| (File not found)".label())
                    toolTipText = "File doesn't exist (but should), run a 'preprocessCode' or 'build' task to generate."
                }
            }
        }

        // Double click also triggers doOKAction()
        addMouseListener(object : MouseAdapter() {
            private var lastClickTime = 0L
            private var lastSelectedIndex = -1

            override fun mouseClicked(e: MouseEvent?) {
                if (e?.button != MouseEvent.BUTTON1) return

                val clickTime = System.currentTimeMillis()
                if (selectedIndex == lastSelectedIndex && clickTime - lastClickTime < 1000) { // 1 second threshold
                    doOKAction()
                }
                lastClickTime = clickTime
                lastSelectedIndex = selectedIndex
            }
        })
    }

    private val search = SearchTextField().apply {
        // Keyboard navigation: Down arrow focuses into the list from the search field
        textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent?) {
                if (e?.keyCode == KeyEvent.VK_DOWN || e?.keyCode == KeyEvent.VK_KP_DOWN) {
                    if (list.selectedIndex == 0 && listModel.size > 1) {
                        // Set to 1 as the first index (0) should already be auto-selected meaning we can treat the down
                        // arrow like moving down as if we were in the list already, (which we kind of are since pressing
                        // enter will already open the selected item at 0 before this down press)
                        list.selectedIndex = 1
                    }
                    list.requestFocusInWindow()
                    e.consume()
                }
            }
        })
    }

    private val messagePanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val errorLabel =
        " - Some files contained mixin parsing issues (ones that do not fail builds), and have been highlighted RED.".messageLabel()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        title = "Select Preprocessed Source File"
        init()
        Disposer.register(disposable) { scope.cancel() }

        scope.launch {
            withBackgroundProgress(project, "Finding Mixin Errors") {
                // Check files concurrently, but limit how many inspection passes run at once
                val permits = Semaphore(MAX_CONCURRENT_MIXIN_SCANS)
                sourceFiles.filter { it.exists }.map { file ->
                    launch {
                        permits.withPermit { file.findMixinErrors(project, disposable) }
                        if (file.hasMixinErrors()) {
                            ApplicationManager.getApplication().invokeLater({ onMixinErrorsFound(file) }, ModalityState.any())
                        }
                    }
                }.joinAll()
            }
        }

        window.addWindowListener(object : WindowAdapter() {
            override fun windowDeactivated(e: WindowEvent?) {
                doCancelAction()
            }
        })
    }

    private fun onMixinErrorsFound(file: SourceSetFile) {
        if (isDisposed) return
        listModel.contentsChanged(file)
        if (errorLabel.parent == null) {
            messagePanel.add(errorLabel, 0)
            messagePanel.isVisible = true
            messagePanel.revalidate()
            messagePanel.repaint()
            // Grow the dialog if the new message doesn't fit
            if (window.height < window.preferredSize.height) pack()
        }
    }

    // Focus the search field when the dialog is first opened, streamlines keyboard navigation
    override fun getPreferredFocusedComponent(): JComponent? = search

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())

        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                filterList()
            }
        })

        panel.add(search, BorderLayout.NORTH)
        panel.add(JBScrollPane(list), BorderLayout.CENTER)
        panel.add(populateMessagePanel(), BorderLayout.SOUTH)
        filterList()
        return panel
    }

    private fun String.messageLabel(): JLabel = JLabel(this).apply {
        font = font.deriveFont(Font.ITALIC, 12f)
        foreground = JBColor.GRAY
    }

    private fun populateMessagePanel(): JPanel {
        return messagePanel.apply {
            if (sourceFiles.any { it.hasMixinErrors() }) {
                add(errorLabel)
            }

            if (sourceFiles.any { !it.isSrc && it.isNonGenerated }) {
                add(" - (override src) files are, non-generated, override files present in the versions/<version>/src/ directory.".messageLabel())
            }

            if (sourceFiles.any { !it.exists }) {
                val hide = PluginSettings.instance.hideMissingFiles
                val label = " - Some files were not found in the expected directory, run a 'preprocessCode' or 'build' task to generate them.".messageLabel()
                add(label)
                add(CheckBox("Hide (File not found) results.", hide).apply {
                    addActionListener {
                        PluginSettings.modify { hideMissingFiles = isSelected }
                        filterList()
                    }
                })
            }

            if (!hadConditions) {
                add(" - No valid preprocessor conditions were found to apply at the caret position to test results with. Showing all.".messageLabel())
            } else if (sourceFiles.any { !it.metOpeningCondition }) {
                val hide = PluginSettings.instance.hideUnmatchedVersions
                val hideText =
                    " - Results that do not meet the preprocessor conditions at the caret position have been hidden."
                val showText =
                    " - Faded results are those that do not meet the preprocessor conditions at the caret position."
                val label = (if (hide) hideText else showText).messageLabel()
                add(label)
                add(CheckBox("Hide results that do not meet preprocessor conditions at caret.", hide).apply {
                    addActionListener {
                        PluginSettings.modify { hideUnmatchedVersions = isSelected }
                        label.text = if (isSelected) hideText else showText
                        filterList()
                    }
                })
            }

            // Hidden until there is something to show, the mixin error message may still be added later
            isVisible = componentCount > 0
        }
    }

    private fun filterList() {
        val filter = search.text.lowercase()
        listModel.replaceAll(sourceFiles.filter {
            it.toString().lowercase().contains(filter)
                    // Hide entries that do not meet the opening condition if the setting is enabled
                    && (!PluginSettings.instance.hideUnmatchedVersions || it.metOpeningCondition)
                    // Hide entries that do not exist if the setting is enabled
                    && (!PluginSettings.instance.hideMissingFiles || it.exists)
        })

        if (listModel.isEmpty) {
            list.setSelectedValue(null, false)
        } else {
            // Improve keyboard navigation by auto-selecting the first result
            list.setSelectedValue(listModel.getElementAt(0), false)
        }
    }

    override fun doOKAction() {
        val selected = list.selectedValue ?: return
        onFileChosen(selected)
        super.doOKAction()
    }

    private companion object {
        const val MAX_CONCURRENT_MIXIN_SCANS = 4
    }

}
