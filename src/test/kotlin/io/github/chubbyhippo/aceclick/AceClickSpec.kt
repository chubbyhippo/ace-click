package io.github.chubbyhippo.aceclick

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.TypedActionHandler
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.awt.Rectangle
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

class AceClickSpec : BasePlatformTestCase() {
    private fun target(
        index: Int,
        clicks: MutableList<Int>,
    ): AceClick.Target {
        val parent = JPanel()
        val button = JButton()
        parent.add(button)
        return AceClick.Target(
            Rectangle(index * 10, 0, 10, 10),
            button,
            javax.swing.JLayeredPane(),
            Rectangle(index * 10, 0, 10, 10),
            { clicks += index },
            {},
        )
    }

    fun `test given buttons combos and editable text then clicker classifies them`() {
        var hits = 0
        val button = JButton().apply { addActionListener { hits++ } }

        AceClick.clicker(button)!!.invoke()

        assertEquals(1, hits)
        assertNotNull(AceClick.clicker(JComboBox<String>()))
        assertNotNull(AceClick.clicker(JTextField("find")))
    }

    fun `test given labels panels disabled buttons and wrapped controls then clicker skips them`() {
        assertNull(AceClick.clicker(JPanel()))
        assertNull(AceClick.clicker(JLabel("label")))
        assertNull(AceClick.clicker(JButton().apply { isEnabled = false }))
        assertNull(AceClick.clicker(JButton().also { JComboBox<String>().add(it) }))
        assertNull(AceClick.clicker(JTextField().apply { isEditable = false }))
    }

    fun `test given ace click targets then labels select the matching target`() {
        val clicks = mutableListOf<Int>()
        AceClick.begin((0 until 3).map { target(it, clicks) })

        assertTrue(AceClick.handleTyped('s'))
        UIUtil.dispatchAllInvocationEvents()

        assertEquals(listOf(1), clicks)
    }

    fun `test given multiple label depths then a short label cannot shadow a longer label`() {
        val clicks = mutableListOf<Int>()
        AceClick.begin((0 until 12).map { target(it, clicks) })

        assertTrue(AceClick.handleTyped('l'))
        assertTrue(AceClick.handleTyped('s'))
        UIUtil.dispatchAllInvocationEvents()

        assertEquals(listOf(9), clicks)
    }

    fun `test given an invalid click label then the session remains active`() {
        AceClick.begin(listOf(target(0, mutableListOf())))

        assertTrue(AceClick.handleTyped('x'))
        assertTrue(AceClick.handleTyped('a'))
    }

    fun `test given an active session then non-label keys are consumed without selecting a target`() {
        val clicks = mutableListOf<Int>()
        AceClick.begin(listOf(target(0, clicks)))

        assertTrue(AceClick.handleTyped('x'))

        assertTrue(clicks.isEmpty())
    }

    fun `test given an uppercase label then the target receives its secondary click`() {
        val leftClicks = mutableListOf<Int>()
        val rightClicks = mutableListOf<Int>()
        val parent = JPanel()
        val button = JButton()
        parent.add(button)
        AceClick.begin(
            listOf(
                AceClick.Target(
                    Rectangle(0, 0, 10, 10),
                    button,
                    javax.swing.JLayeredPane(),
                    Rectangle(0, 0, 10, 10),
                    { leftClicks += 0 },
                    { rightClicks += 0 },
                ),
            ),
        )

        AceClick.handleTyped('A')
        UIUtil.dispatchAllInvocationEvents()

        assertTrue(leftClicks.isEmpty())
        assertEquals(listOf(0), rightClicks)
    }

    fun `test given no active session then typed handler delegates`() {
        myFixture.configureByText("ace-click.txt", "text")
        var delegated = false
        val handler =
            AceClickTypedHandler(
                object : TypedActionHandler {
                    override fun execute(
                        editor: Editor,
                        charTyped: Char,
                        dataContext: DataContext,
                    ) {
                        delegated = true
                    }
                },
            )

        AceClick.cancel()
        handler.execute(myFixture.editor, 'a', DataContext.EMPTY_CONTEXT)

        assertTrue(delegated)
    }

    override fun tearDown() {
        try {
            AceClick.cancel()
        } finally {
            super.tearDown()
        }
    }
}
