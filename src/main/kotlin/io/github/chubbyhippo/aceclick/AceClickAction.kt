package io.github.chubbyhippo.aceclick

import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.TypedActionHandler
import com.intellij.openapi.editor.ex.EditorGutterComponentEx
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.InplaceButton
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.ui.tabs.impl.TabLabel
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Container
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Window
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.AbstractButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.JList
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JScrollBar
import javax.swing.JSpinner
import javax.swing.JTree
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent
import kotlin.math.ln

class AceClickAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val editor = CommonDataKeys.EDITOR.getData(event.dataContext) ?: return
        ApplicationManager.getApplication().getService(AceClickLifecycle::class.java)
        AceClick.start(editor)
    }
}

class AceClickTypedHandler(
    private val original: TypedActionHandler,
) : TypedActionHandler {
    override fun execute(
        editor: Editor,
        charTyped: Char,
        dataContext: DataContext,
    ) {
        if (!AceClick.handleTyped(charTyped)) original.execute(editor, charTyped, dataContext)
    }
}

@Service(Service.Level.APP)
class AceClickLifecycle : Disposable {
    init {
        IdeEventQueue.getInstance().addDispatcher(AceClick.dispatcher, this)
    }

    override fun dispose() {
        AceClick.cancel()
    }
}

@Suppress("TooManyFunctions")
object AceClick {
    private const val LABEL_KEYS = "asdfghjkl"
    private const val LABEL_EPSILON = 1e-6
    private const val ORDER_Y_MULTIPLIER = 100_000
    private const val LABEL_PADDING = 4
    private const val COLOR_RED = 0x2E
    private const val COLOR_GREEN = 0x34
    private const val COLOR_BLUE = 0x40

    internal class Target(
        val rect: Rectangle,
        val component: JComponent,
        val layer: JLayeredPane,
        val screen: Rectangle,
        val click: () -> Unit,
        val rightClick: () -> Unit,
    )

    private class Session(
        val targets: List<Target>,
    ) {
        var prefix = ""
        val canvases = mutableListOf<JComponent>()
    }

    private var session: Session? = null

    internal val dispatcher =
        object : IdeEventQueue.NonLockedEventDispatcher {
            override fun dispatch(e: AWTEvent): Boolean = this@AceClick.dispatch(e)
        }

    fun start(editor: Editor) {
        val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        val window = focus?.let(SwingUtilities::getWindowAncestor) ?: SwingUtilities.getWindowAncestor(editor.component)
        val targets = if (window != null) collect(window) else emptyList()
        begin(targets)
    }

    internal fun begin(targets: List<Target>) {
        cancel()
        targets.takeIf(List<Target>::isNotEmpty)?.let { candidates ->
            session = Session(candidates.sortedBy { target -> target.screen.x * ORDER_Y_MULTIPLIER + target.screen.y })
            paintLabels()
        }
    }

    fun cancel() {
        session?.canvases?.forEach(::detach)
        session = null
    }

    fun handleTyped(char: Char): Boolean = session?.let { active -> pick(active, char) } ?: false

    internal fun clicker(component: JComponent): (() -> Unit)? =
        when {
            !component.isEnabled -> null
            component is ActionButton -> ({ clickActionButton(component) })
            component is JMenuItem -> ({ clickMenuItem(component) })
            component is AbstractButton && !wrappedButtonChild(component.parent) -> ({ component.doClick() })
            component is InplaceButton -> ({ component.doClick() })
            component is LinkLabel<*> -> ({ component.doClick() })
            component is HyperlinkLabel -> ({ component.doClick() })
            component is JComboBox<*> -> ({ component.showPopup() })
            component is TabLabel -> ({ mouseClick(component) })
            component is EditorGutterComponentEx -> ({ mouseClick(component) })
            standaloneTextInput(component) -> ({ mouseClick(component) })
            else -> null
        }

    private fun dispatch(event: AWTEvent): Boolean = (event as? KeyEvent)?.let(::dispatchKey) ?: false

    private fun dispatchKey(event: KeyEvent): Boolean =
        when (event.id) {
            KeyEvent.KEY_TYPED -> {
                session?.let { consume(event) } ?: false
            }

            KeyEvent.KEY_PRESSED -> {
                session?.let { dispatchPressed(event) } ?: false
            }

            else -> {
                false
            }
        }

    private fun dispatchPressed(event: KeyEvent): Boolean =
        event
            .takeIf { it.keyCode == KeyEvent.VK_ESCAPE && it.modifiersEx == 0 }
            ?.let { consume(it) { cancel() } }
            ?: consume(event) { keyChar(event)?.let(::handleTyped) }

    private fun keyChar(event: KeyEvent): Char? =
        event
            .takeIf { it.keyCode in KeyEvent.VK_A..KeyEvent.VK_Z }
            ?.takeIf { it.modifiersEx == 0 || it.modifiersEx == KeyEvent.SHIFT_DOWN_MASK }
            ?.let {
                val char = ('a'.code + it.keyCode - KeyEvent.VK_A).toChar()
                if (it.modifiersEx == KeyEvent.SHIFT_DOWN_MASK) char.uppercaseChar() else char
            }

    private fun consume(
        event: KeyEvent,
        action: () -> Unit = {},
    ): Boolean {
        event.consume()
        action()
        return true
    }

    private fun pick(
        active: Session,
        char: Char,
    ): Boolean {
        val candidate = active.prefix + char.lowercaseChar()
        val labels = labelsFor(active.targets.size)
        val matches = labels.filterValues { label -> label.startsWith(candidate) }
        if (matches.isEmpty()) return true
        val index = matches.entries.singleOrNull { (_, label) -> label == candidate }?.key
        if (index == null) {
            active.prefix = candidate
            paintLabels()
        } else {
            val target = active.targets[index]
            val secondary = char.isUpperCase()
            cancel()
            ApplicationManager.getApplication().invokeLater {
                focus(target)
                click(target, secondary)
            }
        }
        return true
    }

    private fun focus(target: Target) {
        val project = (target.component as? EditorGutterComponentEx)?.editor?.project
        if (project != null) {
            IdeFocusManager.getInstance(project).requestFocus(target.component, true)
        } else {
            target.component.requestFocusInWindow()
        }
    }

    private fun click(
        target: Target,
        secondary: Boolean,
    ) {
        if (target.component.parent == null) return
        if (target.component !is JMenuItem) {
            javax.swing.MenuSelectionManager
                .defaultManager()
                .clearSelectedPath()
        }
        if (secondary) target.rightClick() else target.click()
    }

    private fun collect(root: Component): List<Target> =
        visibleComponents(root).flatMap { component ->
            componentTargets(component)
        }

    private fun componentTargets(component: Component): List<Target> {
        val layer =
            (SwingUtilities.getWindowAncestor(component) as? RootPaneContainer)?.rootPane?.layeredPane
                ?: return emptyList()
        return when (component) {
            is JTree -> treeTargets(component, layer)
            is JList<*> -> listTargets(component, layer)
            else -> listOfNotNull((component as? JComponent)?.let { targetOf(it, layer) })
        }
    }

    private fun treeTargets(
        tree: JTree,
        layer: JLayeredPane,
    ): List<Target> =
        visibleRows(tree.rowCount, tree.visibleRect) { row -> tree.getRowBounds(row) }
            .map { (row, rect) -> rowTarget(tree, rect, layer) { tree.setSelectionRow(row) } }

    private fun listTargets(
        list: JList<*>,
        layer: JLayeredPane,
    ): List<Target> =
        visibleRows(list.model.size, list.visibleRect) { index -> list.getCellBounds(index, index) }
            .map { (index, rect) -> rowTarget(list, rect, layer) { list.selectedIndex = index } }

    private fun visibleRows(
        count: Int,
        visible: Rectangle,
        bounds: (Int) -> Rectangle?,
    ): List<Pair<Int, Rectangle>> =
        (0 until count).mapNotNull { index ->
            bounds(index)?.intersection(visible)?.takeIf { !it.isEmpty }?.let { index to it }
        }

    private fun rowTarget(
        component: JComponent,
        rect: Rectangle,
        layer: JLayeredPane,
        click: () -> Unit,
    ): Target {
        val screen = Rectangle(rect)
        SwingUtilities.convertPointToScreen(screen.location, component)
        val point = Point(rect.x + rect.width / 2, rect.y + rect.height / 2)
        return Target(
            SwingUtilities.convertRectangle(component, rect, layer),
            component,
            layer,
            screen,
            click,
            {
                click()
                popupClick(component, point)
            },
        )
    }

    private fun targetOf(
        component: JComponent,
        layer: JLayeredPane,
    ): Target? =
        clicker(component)?.let { click ->
            component.visibleRect.takeIf { visible -> visible.width > 0 && visible.height > 0 }?.let { visible ->
                target(component, layer, visible, click)
            }
        }

    private fun target(
        component: JComponent,
        layer: JLayeredPane,
        visible: Rectangle,
        click: () -> Unit,
    ): Target {
        val screen = Rectangle(visible)
        SwingUtilities.convertPointToScreen(screen.location, component)
        val point = Point(visible.x + visible.width / 2, visible.y + visible.height / 2)
        return Target(
            SwingUtilities.convertRectangle(component, visible, layer),
            component,
            layer,
            screen,
            click,
            { popupClick(component, point) },
        )
    }

    private fun paintLabels() {
        val active = session ?: return
        active.canvases.forEach(::detach)
        active.canvases.clear()
        labelsFor(active.targets.size).forEach { (index, label) ->
            if (label.startsWith(active.prefix)) {
                val target = active.targets[index]
                active.canvases += layerCanvas(target.layer, ClickBadge(target.rect, label.removePrefix(active.prefix)))
            }
        }
    }

    private fun labelsFor(count: Int): Map<Int, String> = labels(tree((0 until count).toList()))

    private fun tree(candidates: List<Int>): LabelBranch {
        if (candidates.size < LABEL_KEYS.length) {
            return LabelBranch(LABEL_KEYS.toList().zip(candidates.map(::LabelLeaf)))
        }
        var remaining = candidates
        val children = mutableListOf<Pair<Char, LabelNode>>()
        for ((index, size) in subdiv(candidates.size).withIndex()) {
            val taken = remaining.take(size)
            remaining = remaining.drop(size)
            children += LABEL_KEYS[index] to if (size == 1) LabelLeaf(taken.first()) else tree(taken)
        }
        return LabelBranch(children)
    }

    private fun subdiv(count: Int): List<Int> {
        val base = LABEL_KEYS.length
        val depth = kotlin.math.floor(ln(count.toDouble()) / ln(base.toDouble()) + LABEL_EPSILON).toInt() - 1
        var shallowWidth = 1
        repeat(depth) { shallowWidth *= base }
        val deepWidth = base * shallowWidth
        val delta = count - deepWidth
        val deepBuckets = delta / (deepWidth - shallowWidth)
        val shallowBuckets = base - deepBuckets - 1
        return List(shallowBuckets) { shallowWidth } +
            listOf(count - shallowBuckets * shallowWidth - deepBuckets * deepWidth) +
            List(deepBuckets) { deepWidth }
    }

    private fun labels(root: LabelBranch): Map<Int, String> {
        val labels = mutableMapOf<Int, String>()

        fun visit(
            node: LabelNode,
            prefix: String,
        ) {
            when (node) {
                is LabelLeaf -> labels[node.index] = prefix
                is LabelBranch -> node.children.forEach { (key, child) -> visit(child, prefix + key) }
            }
        }
        visit(root, "")
        return labels
    }

    private sealed class LabelNode

    private class LabelLeaf(
        val index: Int,
    ) : LabelNode()

    private class LabelBranch(
        val children: List<Pair<Char, LabelNode>>,
    ) : LabelNode()

    private fun visibleComponents(root: Component): List<Component> {
        val found = mutableListOf<Component>()
        val queue = ArrayDeque<Component>()
        queue += root
        while (queue.isNotEmpty()) {
            val component = queue.removeFirst()
            if (!component.isVisible) continue
            found += component
            if (component is Container) queue += component.components
        }
        return found
    }

    private fun wrappedButtonChild(parent: Container?): Boolean =
        parent is JComboBox<*> || parent is JSpinner || parent is JScrollBar

    private fun standaloneTextInput(component: JComponent): Boolean =
        component is JTextComponent &&
            component.isEditable &&
            SwingUtilities.getAncestorOfClass(JComboBox::class.java, component) == null &&
            SwingUtilities.getAncestorOfClass(JSpinner::class.java, component) == null

    @Suppress("UnstableApiUsage")
    private fun clickActionButton(button: ActionButton) {
        if (button.isShowing) {
            button.click()
            return
        }
        val stamped = mutableListOf<JComponent>()
        var ancestor: Component? = button
        while (ancestor is JComponent) {
            stamped += ancestor
            ancestor.putClientProperty(ActionUtil.ALLOW_ACTION_PERFORM_WHEN_HIDDEN, true)
            if (ancestor is ActionToolbar) break
            ancestor = ancestor.parent
        }
        try {
            button.click()
        } finally {
            stamped.forEach { component ->
                component.putClientProperty(ActionUtil.ALLOW_ACTION_PERFORM_WHEN_HIDDEN, null)
            }
        }
    }

    private fun clickMenuItem(menuItem: JMenuItem) {
        if (menuItem is JMenu) {
            menuItem.doClick()
        } else {
            javax.swing.MenuSelectionManager
                .defaultManager()
                .clearSelectedPath()
            menuItem.doClick(0)
        }
    }

    private fun mouseClick(component: JComponent) {
        val point = Point(component.width / 2, component.height / 2)
        for (id in intArrayOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
            component.dispatchEvent(
                MouseEvent(component, id, System.currentTimeMillis(), 0, point.x, point.y, 1, false),
            )
        }
    }

    private fun popupClick(
        component: JComponent,
        point: Point,
    ) {
        for (id in intArrayOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED)) {
            component.dispatchEvent(
                MouseEvent(component, id, System.currentTimeMillis(), 0, point.x, point.y, 1, true, MouseEvent.BUTTON3),
            )
        }
    }

    private fun layerCanvas(
        layer: JLayeredPane,
        canvas: JComponent,
    ): JComponent {
        canvas.isOpaque = false
        canvas.bounds = Rectangle(0, 0, layer.width, layer.height)
        layer.setLayer(canvas, JLayeredPane.DRAG_LAYER)
        layer.add(canvas)
        layer.repaint()
        return canvas
    }

    private fun detach(canvas: JComponent) {
        canvas.parent?.let { parent ->
            parent.remove(canvas)
            parent.repaint()
        }
    }

    private class ClickBadge(
        private val rect: Rectangle,
        private val label: String,
    ) : JComponent() {
        override fun contains(
            x: Int,
            y: Int,
        ): Boolean = false

        override fun paintComponent(graphics: Graphics) {
            val g = graphics as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.font = g.font.deriveFont(Font.BOLD)
            val bounds = g.font.createGlyphVector(g.fontRenderContext, label).visualBounds
            val width = kotlin.math.ceil(bounds.width).toInt() + LABEL_PADDING * 2
            val height = kotlin.math.ceil(bounds.height).toInt() + LABEL_PADDING * 2
            val x = rect.x + rect.width / 2 - width / 2
            val y = rect.y + rect.height / 2 - height / 2
            g.color = java.awt.Color(COLOR_RED, COLOR_GREEN, COLOR_BLUE)
            g.fillRect(x, y, width, height)
            g.color = java.awt.Color.WHITE
            g.drawString(label, (x + LABEL_PADDING - bounds.x).toFloat(), (y + LABEL_PADDING - bounds.y).toFloat())
        }
    }
}
