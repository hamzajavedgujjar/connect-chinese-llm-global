package ai.norwix.platform

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import ai.norwix.domain.Provider
import ai.norwix.domain.UrlPolicy
import ai.norwix.network.UserFacingException

object ProviderBrowser {
    fun openProvider(activity: Activity, provider: Provider, conversationUrl: String = "") {
        val target = conversationUrl.ifBlank { provider.home }
        if (!UrlPolicy.validConversation(provider, target)) throw UserFacingException("Use an HTTPS conversation link from this provider's exact official website, without query parameters.")
        open(activity, target)
    }

    fun openAuth(activity: Activity, url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" || uri.host != "auth.openai.com" ||
            uri.path != "/api/accounts/authorize") throw UserFacingException("The sign-in address could not be verified.")
        open(activity, url)
    }

    private fun open(activity: Activity, url: String) {
        val tab = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setDefaultColorSchemeParams(CustomTabColorSchemeParams.Builder().setToolbarColor(Color.rgb(15, 18, 30)).build())
            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            .build()
        try { tab.launchUrl(activity, Uri.parse(url)) }
        catch (_: ActivityNotFoundException) {
            try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            catch (_: ActivityNotFoundException) { throw UserFacingException("Install a browser that supports secure HTTPS sign-in, such as Chrome or Firefox.") }
        }
    }
}
