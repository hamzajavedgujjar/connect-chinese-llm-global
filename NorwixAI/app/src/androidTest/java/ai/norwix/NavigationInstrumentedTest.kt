package ai.norwix

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class NavigationInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun drawerExposesOfficialSignInWithoutAnyProviderPasswordForm() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as NorwixApplication
        compose.waitUntil(15_000) { app.repository.data.value?.activeChat != null }
        val originalPreferences = app.repository.data.value!!.preferences
        // Only the isolated test installation permits screenshots. The shipped
        // application keeps screen protection enabled by default.
        runBlocking { app.repository.update { it.copy(preferences = it.preferences.copy(protectScreen = false, reduceMotion = true)) } }
        try {
            compose.waitForIdle()
            screenshot("01-midnight-chat.png")
            navigate("ACCOUNTS")
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Continue with ChatGPT").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Continue with ChatGPT").assertIsDisplayed()
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            screenshot("02-login-accounts.png")

            navigate("CHATS")
            compose.onNodeWithText("Rename").performClick()
            compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement("Draft isolation test")
            compose.onNodeWithText("Save").performClick()
            compose.waitUntil(10_000) { app.repository.data.value!!.chats.any { it.title == "Draft isolation test" } }
            compose.onNodeWithText("Draft isolation test").performClick()
            compose.onNodeWithContentDescription("Message composer").performTextInput("A private draft that must stay in this chat")
            compose.waitUntil(10_000) { app.repository.data.value!!.activeChat!!.draft == "A private draft that must stay in this chat" }
            val originalChat = app.repository.data.value!!.activeChatId
            compose.onNodeWithContentDescription("New conversation").performClick()
            compose.waitUntil(10_000) { app.repository.data.value!!.activeChatId != originalChat }
            assertNotEquals(originalChat, app.repository.data.value!!.activeChatId)
            compose.onNodeWithContentDescription("Message composer").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            navigate("CHATS")
            compose.onNode(hasSetTextAction()).performTextInput("Draft isolation")
            compose.onNodeWithText("Draft isolation test").performClick()
            compose.onNodeWithContentDescription("Message composer").assertTextEquals("A private draft that must stay in this chat")
            compose.onNodeWithContentDescription("Message composer").performTextClearance()

            navigate("THEMES")
            compose.onNodeWithText("Pearl").performClick()
            compose.onNodeWithContentDescription("Back to conversation").performClick()
            compose.waitForIdle()
            screenshot("03-pearl-chat.png")
        } finally {
            runBlocking { app.repository.update { it.copy(preferences = originalPreferences) } }
        }
    }

    private fun navigate(destination: String) {
        compose.onNodeWithContentDescription("Open navigation menu").performClick()
        compose.onNodeWithTag("nav-$destination").performClick()
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(instrumentation.targetContext.filesDir, "ui-smoke").apply { mkdirs() }
        File(directory, name).outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        bitmap.recycle()
    }
}
