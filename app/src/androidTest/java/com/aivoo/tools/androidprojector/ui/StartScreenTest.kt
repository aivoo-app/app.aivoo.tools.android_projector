package app.aivoo.tools.androidprojector.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun startButtonIsDisplayed() {
        composeTestRule.setContent {
            MaterialTheme {
                StartScreen(onStart = {})
            }
        }
        composeTestRule.onNodeWithText("Start Server").assertIsDisplayed()
    }
}
