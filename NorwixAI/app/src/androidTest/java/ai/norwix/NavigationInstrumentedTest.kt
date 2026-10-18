package ai.norwix

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class NavigationInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun drawerExposesOfficialSignInWithoutAnyProviderPasswordForm() {
        compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("Open navigation menu").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Open navigation menu").performClick()
        compose.onNodeWithText("Login Accounts").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Continue with ChatGPT").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Continue with ChatGPT").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }
}
