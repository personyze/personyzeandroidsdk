package com.personyze.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.util.AttributeSet
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.personyze.android.internal.android.AndroidPlatform
import com.personyze.android.internal.core.HtmlDocument
import com.personyze.android.internal.core.Wire
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt

/**	Renders an HTML [PersonyzeAction] in a WebView, as the v1 SDK's `renderOnWebView` did, and
	reports what happens in it:

	* `executed` once the page has loaded ([reportExecuted]);
	* a tap on the call to action, a close button, a recommended product or article ([reportClicks]);
	  then [onClick] - return true to handle it yourself, else a link opens in the browser and a
	  close hides the view.

	It sizes itself to its content when its height is not fixed ([autoHeight]), so it works in a
	scrolling list and in Compose (`personyze-android-compose`). Call [destroy] when done with it.
 **/
public class PersonyzeActionView @JvmOverloads constructor
(	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr)
{	public var reportExecuted: Boolean = true
	public var reportClicks: Boolean = true
	public var autoHeight: Boolean = true
	public var onClick: PersonyzeClickListener? = null

	/**	The action shown, or null. **/
	public var action: PersonyzeAction? = null
		private set

	private var webView: WebView? = null
	private var renderedKey: String? = null
	private var executedPending = false
	private var contentHeightPx = 0
	private var closed = false

	/**	Tests force the `addJavascriptInterface` path that old WebViews take. **/
	internal var forceLegacyBridge: Boolean = false

	/**	Show [action]. The same action with the same content again does nothing, so this is safe
		to call on every bind or recomposition. Non-HTML content, or a template not loaded yet,
		clears the view.
	 **/
	public fun render(action: PersonyzeAction)
	{	val html = HtmlDocument.build(action, action.assetsUrl, HEIGHT_SCRIPT)
		if (html == null)
		{	clear()
			return
		}
		val key = action.id.toString() + ":" + html.hashCode()
		if (key == renderedKey && webView != null)
		{	return
		}
		val wv = webView ?: createWebView(action.assetsUrl)
		if (wv == null)
		{	action.reportError("No WebView on this device")
			return
		}
		this.action = action
		renderedKey = key
		closed = false
		contentHeightPx = 0
		visibility = VISIBLE
		executedPending = reportExecuted
		wv.loadDataWithBaseURL(action.assetsUrl, html, "text/html", "utf-8", action.assetsUrl)
	}

	/**	Show nothing. **/
	public fun clear()
	{	action = null
		renderedKey = null
		executedPending = false
		contentHeightPx = 0
		webView?.loadUrl(BLANK)
		requestLayout()
	}

	/**	Release the WebView. The view can render again afterwards, with a new one. **/
	public fun destroy()
	{	webView?.let { wv ->
			removeView(wv)
			wv.stopLoading()
			wv.destroy()
		}
		webView = null
		renderedKey = null
		executedPending = false
	}

	@SuppressLint("SetJavaScriptEnabled")
	private fun createWebView(assetsUrl: String): WebView?
	{	val wv = try
		{	WebView(context)
		}
		catch (e: Exception)
		{	// No WebView package (being updated, or none on the device).
			Log.w(AndroidPlatform.TAG, "Personyze: cannot create a WebView: $e")
			return null
		}
		wv.settings.apply {
			javaScriptEnabled = true
			// The popup and template code keeps state in sessionStorage.
			domStorageEnabled = true
			allowFileAccess = false
			allowContentAccess = false
			mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
			setSupportMultipleWindows(false)
			javaScriptCanOpenWindowsAutomatically = false
		}
		wv.setBackgroundColor(Color.TRANSPARENT)
		installBridge(wv, assetsUrl)
		wv.webViewClient = Client()
		addView(wv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
		webView = wv
		return wv
	}

	/**	`personyze_message_handler`, the object Personyze's WebView library posts to
		(php-service `templates/web-view/index.php`): `postMessage(JSON.stringify({href, clicked, arg}))`.
		A WebMessageListener answers only pages of the assets origin and only the main frame; an old
		WebView without it gets v1's JavaScript interface, which any frame could call - acceptable
		because the view loads nothing else (navigation is blocked).
	 **/
	private fun installBridge(wv: WebView, assetsUrl: String)
	{	val uri = Uri.parse(assetsUrl)
		val origin = uri.scheme + "://" + uri.host + (if (uri.port != -1) ":" + uri.port else "")
		if (!forceLegacyBridge && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
		{	WebViewCompat.addWebMessageListener(wv, BRIDGE, setOf(origin)) { _, message, _, isMainFrame, _ ->
				if (isMainFrame)
				{	message.data?.let { onMessage(it) }
				}
			}
		}
		else
		{	wv.addJavascriptInterface(LegacyJsBridge { data -> post { onMessage(data) } }, BRIDGE)
		}
	}

	private fun onMessage(data: String)
	{	val obj = try
		{	Wire.json.parseToJsonElement(data) as? JsonObject
		}
		catch (e: Exception)
		{	null
		} ?: return
		if (obj.containsKey("height"))
		{	val height = Wire.double(obj["height"]) ?: return
			val px = (height*resources.displayMetrics.density).roundToInt()
			if (px != contentHeightPx)
			{	contentHeightPx = px
				if (autoHeight)
				{	requestLayout()
				}
			}
			return
		}
		val action = action ?: return
		val kind = PersonyzeClick.kindOf(Wire.string(obj["clicked"])) ?: return
		handle(PersonyzeClick(action.id, kind, Wire.string(obj["href"]), Wire.string(obj["arg"])))
	}

	private fun handle(click: PersonyzeClick)
	{	if (reportClicks)
		{	(action?.owner ?: Personyze).report(click)
		}
		if (onClick?.onClick(click) == true)
		{	return
		}
		if (click.kind == PersonyzeClick.Kind.CLOSE)
		{	closed = true
			visibility = GONE
			requestLayout()
		}
		else if (click.href.isNotEmpty())
		{	open(click.href)
		}
	}

	/**	Open a link the visitor tapped. Not one that resolves to the assets origin: that was a
		relative link (`href="/"`, written for the customer's site, measured on a live action
		2026-10-23), and in an app it would open Personyze's CDN in the browser. The tap is still
		reported and still reaches [onClick].
	 **/
	private fun open(href: String)
	{	val uri = Uri.parse(href)
		val scheme = uri.scheme?.lowercase()
		if (scheme == null || scheme == "javascript" || scheme == "file" || scheme == "content")
		{	return
		}
		val assets = action?.assetsUrl?.let { Uri.parse(it) }
		if (assets != null && uri.host.equals(assets.host, ignoreCase = true) && scheme == assets.scheme)
		{	Log.i(AndroidPlatform.TAG, "Personyze: not opening $href - a relative link in the action, with no site behind it in an app")
			return
		}
		val intent = Intent(Intent.ACTION_VIEW, uri)
		if (context !is Activity)
		{	intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		}
		try
		{	context.startActivity(intent)
		}
		catch (e: ActivityNotFoundException)
		{	Log.w(AndroidPlatform.TAG, "Personyze: nothing opens $href")
		}
	}

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int)
	{	if (closed)
		{	setMeasuredDimension(0, 0)
			return
		}
		val mode = MeasureSpec.getMode(heightMeasureSpec)
		if (autoHeight && contentHeightPx > 0 && mode != MeasureSpec.EXACTLY)
		{	val size = MeasureSpec.getSize(heightMeasureSpec)
			val height = if (mode == MeasureSpec.AT_MOST) minOf(contentHeightPx, size) else contentHeightPx
			super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
			return
		}
		super.onMeasure(widthMeasureSpec, heightMeasureSpec)
	}

	private inner class Client : WebViewClient()
	{	/**	Nothing leaves the action's page: links are reported and opened outside (the WebView
			library already stops ordinary link clicks); a page the content navigates to by itself is
			treated as a tap on the call to action when the visitor caused it.
		 **/
		override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean
		{	val action = action
			if (action != null && request.isForMainFrame && request.hasGesture())
			{	handle(PersonyzeClick(action.id, PersonyzeClick.Kind.TARGET, request.url.toString(), ""))
			}
			return true
		}

		override fun onPageFinished(view: WebView, url: String?)
		{	if (executedPending && url != BLANK)
			{	executedPending = false
				action?.let { (it.owner ?: Personyze).reportExecuted(it.id) }
			}
		}

		/**	The renderer crashed or was killed for memory: without handling this the app dies too. **/
		override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean
		{	if (view == webView)
			{	removeView(view)
				view.destroy()
				webView = null
				renderedKey = null
			}
			return true
		}
	}

	internal class LegacyJsBridge(private val onMessage: (String) -> Unit)
	{	@JavascriptInterface
		fun postMessage(data: String)
		{	onMessage(data)
		}
	}

	private companion object
	{	const val BRIDGE = "personyze_message_handler"
		const val BLANK = "about:blank"

		/**	Tells the view the content's height, through the same bridge - the server's library never
			sends `height`.

			Measured as the bottom of the body's children and text (with the last margins), not as the
			height of `<html>` or `<body>`: the page has no doctype, as v1's had none, so it renders in
			quirks mode, where the body takes the viewport's height - and the viewport is the view.
			Measuring the body fed the view's own height back: a live banner (action 240, 2026-10-23)
			stretched the view to the whole screen. Each child is observed (re-armed when children
			change), and measured again a moment after load: a helper script (`responsive`) or a late
			font changes the layout after the first measurement - the same banner once showed cut off.
		 **/
		const val HEIGHT_SCRIPT = "<script>(function(){" +
			"function h(){var b=document.body;if(!b)return;var bs=getComputedStyle(b),bottom=0,i,e,cs;" +
			"var rg=document.createRange();rg.selectNodeContents(b);bottom=rg.getBoundingClientRect().bottom;" +
			"for(i=0;i<b.children.length;i++){e=b.children[i];cs=getComputedStyle(e);if(cs.position==='fixed'||cs.display==='none')continue;" +
			"bottom=Math.max(bottom,e.getBoundingClientRect().bottom+(parseFloat(cs.marginBottom)||0));}" +
			"var v=Math.ceil(bottom+window.scrollY+(parseFloat(bs.paddingBottom)||0)+(parseFloat(bs.marginBottom)||0));" +
			"if(v>0&&v!==h.l){h.l=v;try{window.personyze_message_handler.postMessage(JSON.stringify({height:v}))}catch(x){}}}" +
			"var ro=window.ResizeObserver?new ResizeObserver(h):null;" +
			"function watch(){if(ro)for(var i=0;i<document.body.children.length;i++)ro.observe(document.body.children[i]);}" +
			"document.addEventListener('DOMContentLoaded',function(){h();watch();if(window.MutationObserver)new MutationObserver(function(){watch();h();}).observe(document.body,{childList:true});});" +
			"window.addEventListener('load',function(){h();setTimeout(h,300);setTimeout(h,1500);});" +
			"})()</script>"
	}
}
