package com.personyze.android

/**	Something the visitor tapped inside a rendered action, as Personyze's WebView library reports
	it (`{href, clicked, arg}` through `personyze_message_handler`).
 **/
public class PersonyzeClick internal constructor
(	public val actionId: Int,
	public val kind: Kind,
	/**	The link's address when a link was tapped, else empty. **/
	public val href: String,
	/**	For [Kind.PRODUCT] and [Kind.ARTICLE] the item's Internal ID; for [Kind.CLOSE] the number
		of sessions not to show the action again.
	 **/
	public val arg: String
)
{	public enum class Kind
	{	/**	The call to action (`target`) - a link, or the action itself when it marks no targets. **/
		TARGET,
		/**	A close or "don't show again" button. **/
		CLOSE,
		/**	A product in a recommendation widget. **/
		PRODUCT,
		/**	An article in a recommendation widget. **/
		ARTICLE,
	}

	/**	For [Kind.CLOSE]: sessions not to show the action again (0 = just close it). **/
	public val dontShowForSessions: Int
		get() = if (kind == Kind.CLOSE) arg.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0 else 0

	public val productId: String?
		get() = if (kind == Kind.PRODUCT) arg else null

	public val articleId: String?
		get() = if (kind == Kind.ARTICLE) arg else null

	override fun equals(other: Any?): Boolean =
		other is PersonyzeClick && actionId == other.actionId && kind == other.kind && href == other.href && arg == other.arg
	override fun hashCode(): Int = 31*(31*actionId + kind.hashCode()) + href.hashCode()
	override fun toString(): String = "PersonyzeClick(actionId=$actionId, kind=$kind, href=$href, arg=$arg)"

	internal companion object
	{	/**	The wire's `clicked` value to a kind, or null for one this version does not know. **/
		fun kindOf(clicked: String): Kind? = when (clicked)
		{	"target" -> Kind.TARGET
			"close" -> Kind.CLOSE
			"product" -> Kind.PRODUCT
			"article" -> Kind.ARTICLE
			else -> null
		}
	}
}

/**	Handles a tap inside a rendered action. Return true when the app handled it; false lets the
	view do the default - open a link in the browser, hide the action on close.
 **/
public fun interface PersonyzeClickListener
{	public fun onClick(click: PersonyzeClick): Boolean
}
