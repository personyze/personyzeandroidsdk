package com.personyze.android

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class HostActivity : Activity()
{	lateinit var root: FrameLayout

	override fun onCreate(savedInstanceState: Bundle?)
	{	super.onCreate(savedInstanceState)
		root = FrameLayout(this)
		setContentView(root)
	}
}

/**	The view in a real WebView: the bridge under the assets origin (both paths), its height, blocked
	navigation, and Personyze's real `web-view.js` turning a link tap into a `target` click.
 **/
@RunWith(AndroidJUnit4::class)
class ActionViewTest
{	private val clicks = LinkedBlockingQueue<PersonyzeClick>()
	private var scenario: ActivityScenario<HostActivity>? = null

	@After fun close()
	{	scenario?.onActivity { a -> (a.root.getChildAt(0) as? PersonyzeActionView)?.destroy() }
		scenario?.close()
	}

	private fun action(html: String): PersonyzeAction =
		PersonyzeAction(88, mapOf("data" to html), "Test", "text/html", emptyList(), null, emptyList(), "data", "", "", 1, isLoaded = true)

	private fun show(html: String, legacy: Boolean = false, configure: (PersonyzeActionView) -> Unit = {}): PersonyzeActionView
	{	var view: PersonyzeActionView? = null
		scenario = ActivityScenario.launch(HostActivity::class.java)
		scenario!!.onActivity { a ->
			val v = PersonyzeActionView(a)
			v.forceLegacyBridge = legacy
			v.reportClicks = false
			v.reportExecuted = false
			v.onClick = PersonyzeClickListener { clicks.add(it); true }
			configure(v)
			a.root.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
			v.render(action(html))
			view = v
		}
		return view!!
	}

	/**	The message the server's library posts, from a page loaded under counter.personyze.com. **/
	private val posting = """<div id="x">x</div><script>window.addEventListener('load', function() {
		window.personyze_message_handler.postMessage(JSON.stringify({href: "https://shop.example/p/A1", clicked: "product", arg: "A1"}));
	});</script>"""

	@Test fun webMessageListenerReceivesUnderTheAssetsOrigin()
	{	assumeTrue("WebView without WEB_MESSAGE_LISTENER", WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
		show(posting)
		val click = clicks.poll(20, TimeUnit.SECONDS)
		assertNotNull("no message reached the listener: the origin of loadDataWithBaseURL did not match", click)
		assertEquals(PersonyzeClick.Kind.PRODUCT, click!!.kind)
		assertEquals("A1", click.productId)
		assertEquals("https://shop.example/p/A1", click.href)
		assertEquals(88, click.actionId)
	}

	@Test fun javascriptInterfaceFallbackReceivesToo()
	{	show(posting, legacy = true)
		val click = clicks.poll(20, TimeUnit.SECONDS)
		assertNotNull(click)
		assertEquals(PersonyzeClick.Kind.PRODUCT, click!!.kind)
	}

	@Test fun viewTakesTheContentsHeight()
	{	val view = show("""<div style="height:300px;background:#0284C7"></div>""")
		val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
		val deadline = System.currentTimeMillis() + 20_000
		var height = 0
		while (System.currentTimeMillis() < deadline)
		{	InstrumentationRegistry.getInstrumentation().runOnMainSync { height = view.height }
			if (height >= 300*density)
			{	break
			}
			Thread.sleep(100)
		}
		assertTrue("height $height px for 300 css px at density $density", height >= 300*density && height < 400*density)
	}

	/**	The content cannot take the view elsewhere; a tap that navigates becomes a call to action. **/
	@Test fun navigationIsBlocked()
	{	val view = show("""<script>window.addEventListener('load', function() { location.href = "https://example.com/away"; });</script><p>stay</p>""")
		Thread.sleep(3000)
		var url: String? = null
		InstrumentationRegistry.getInstrumentation().runOnMainSync { url = (view.getChildAt(0) as android.webkit.WebView).url }
		assertTrue("navigated to $url", url == null || !url!!.contains("example.com/away"))
	}

	/**	Personyze's own `web-view.js` (network): a link tap is reported as `target` with its address. **/
	@Test fun realWebViewLibraryReportsALinkTap()
	{	val view = show("""<a id="go" href="https://shop.example/sale">Sale</a>""")
		Thread.sleep(4000)	// web-view.js from counter.personyze.com, then its onload handler
		InstrumentationRegistry.getInstrumentation().runOnMainSync {
			(view.getChildAt(0) as android.webkit.WebView).evaluateJavascript("document.getElementById('go').click()", null)
		}
		val click = clicks.poll(20, TimeUnit.SECONDS)
		assertNotNull("no click from web-view.js (is the emulator online?)", click)
		assertEquals(PersonyzeClick.Kind.TARGET, click!!.kind)
		assertEquals("https://shop.example/sale", click.href)
	}
}
