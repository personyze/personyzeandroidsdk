package com.personyze.android

import com.personyze.android.internal.core.HtmlDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**	What Personyze decided for the visitor: the campaigns (conditions) they match and the actions
	to present now.

	A decision belongs to one screen. [PersonyzeApi.navigate] replaces it; events and reports sent
	without a navigation add to it and remove what Personyze dismissed. A failed navigation answers
	an empty result with [error] set - the app renders its default, as it would for a visitor
	Personyze has nothing for.
 **/
public class PersonyzeResult internal constructor
(	/**	The actions to present, in the order Personyze sent them. **/
	public val actions: List<PersonyzeAction>,
	/**	The campaigns the visitor matches. Rarely needed: only for app logic of your own. **/
	public val conditions: List<PersonyzeCondition>,
	/**	Actions Personyze withdrew in the last response that was not a navigation. **/
	public val dismissedActions: List<Int>,
	/**	Campaigns the visitor stopped matching in the last response that was not a navigation. **/
	public val dismissedConditions: List<Int>,
	/**	The session string this result was decided in. **/
	public val sessionId: String?,
	/**	The account's `cache_version` when this was decided. **/
	public val cacheVersion: Int,
	/**	The last tracker response verbatim, for anything this class does not model. **/
	public val rawJson: String?,
	/**	Why this result is empty, when it is empty because something failed. **/
	public val error: PersonyzeException?
)
{	/**	Whether Personyze decided this action. What a server-side A/B test is written against:
		`if (result.has(88)) showVariantB() else showDefault()`.
	 **/
	public fun has(actionId: Int): Boolean = actions.any { it.id == actionId }

	public fun action(actionId: Int): PersonyzeAction? = actions.firstOrNull { it.id == actionId }

	/**	The action's per-visitor data (the values its content is built from), or null if not decided. **/
	public fun dataFor(actionId: Int): Map<String, String>? = action(actionId)?.data

	/**	The first of these action ids that was decided, or null - for picking the arm of a test. **/
	public fun firstOf(vararg actionIds: Int): Int? = actionIds.firstOrNull { has(it) }

	/**	The actions targeted to this placeholder, as the web tag would fill it: by
		`placeholder_order` (none counts as 999), then by id, at most `units_count_max` of them.
		[placeholder] is the placeholder's name or its HTML id, as set in the panel.
	 **/
	public fun actionsIn(placeholder: String): List<PersonyzeAction> = actionsIn { it.name == placeholder || it.htmlId == placeholder }

	public fun actionsIn(placeholderId: Int): List<PersonyzeAction> = actionsIn { it.id == placeholderId }

	private fun actionsIn(match: (PersonyzePlaceholder) -> Boolean): List<PersonyzeAction>
	{	var capacity = 0
		val list = actions.filter { action ->
			val placeholder = action.placeholders.firstOrNull(match)
			if (placeholder != null && capacity == 0)
			{	capacity = placeholder.unitsCountMax
			}
			placeholder != null
		}.sortedWith(compareBy<PersonyzeAction>({ it.placeholderOrder ?: 999 }, { it.id }))
		return if (capacity > 0) list.take(capacity) else list
	}

	/**	No actions and no conditions. **/
	public val isEmpty: Boolean
		get() = actions.isEmpty() && conditions.isEmpty()

	/**	Empty because the request failed, not because Personyze had nothing. **/
	public val failed: Boolean
		get() = error != null

	override fun equals(other: Any?): Boolean =
		other is PersonyzeResult && actions == other.actions && conditions == other.conditions &&
		dismissedActions == other.dismissedActions && dismissedConditions == other.dismissedConditions &&
		sessionId == other.sessionId && cacheVersion == other.cacheVersion && error === other.error

	override fun hashCode(): Int = 31*(31*actions.hashCode() + conditions.hashCode()) + (sessionId?.hashCode() ?: 0)

	override fun toString(): String = "PersonyzeResult(actions=$actions, conditions=$conditions, error=${error?.message})"

	public companion object
	{	/**	No decision. **/
		@JvmField public val EMPTY: PersonyzeResult = PersonyzeResult(emptyList(), emptyList(), emptyList(), emptyList(), null, 0, null, null)

		internal fun failed(error: PersonyzeException): PersonyzeResult = PersonyzeResult(emptyList(), emptyList(), emptyList(), emptyList(), null, 0, null, error)
	}
}

/**	An action Personyze decided to present - usually HTML (a banner, a recommendation widget),
	sometimes JSON or text you render yourself.

	The content comes in two parts: the per-visitor [data] arrives with the decision, the template
	around it is fetched once per account version and cached. Until it arrives [isLoaded] is false
	and [content] is null; the decision itself ([PersonyzeResult.has]) is already valid.
 **/
public class PersonyzeAction internal constructor
(	public val id: Int,
	/**	The values Personyze computed for this visitor (product lists, texts). **/
	public val data: Map<String, String>,
	/**	The action's name in the panel, once loaded. **/
	public val name: String?,
	/**	MIME type: `text/html` for banners and recommendation widgets, `application/json` for a
		JSON feed, or whatever the action type produces.
	 **/
	public val contentType: String?,
	/**	The placeholders the action is targeted to, as set in the panel. **/
	public val placeholders: List<PersonyzePlaceholder>,
	/**	Order among the actions sharing a placeholder; null means last (999). **/
	public val placeholderOrder: Int?,
	/**	Helper scripts the HTML needs in a WebView (`libs_app`). **/
	public val libs: List<String>,
	internal val contentParam: String,
	internal val contentBegin: String,
	internal val contentEnd: String,
	internal val cacheVersion: Int,
	/**	Whether the template has arrived. **/
	public val isLoaded: Boolean
)
{	/**	The client that decided this action; where its report shortcuts go. **/
	internal var owner: PersonyzeApi? = null
	internal var assetsUrl: String = PersonyzeConfig.DEFAULT_ASSETS_URL

	/**	The raw content: `content_begin + data[content_param] + content_end`, with `${action_id}`
		replaced - the formula the web tag uses. Null until [isLoaded].
	 **/
	public val content: String?
		get() = if (!isLoaded) null else (contentBegin + (if (contentParam.isEmpty()) "" else data[contentParam] ?: "") + contentEnd).replace("\${action_id}", id.toString())

	public val isHtml: Boolean
		get() = contentType == "text/html"

	public val isJson: Boolean
		get() = contentType == "application/json"

	/**	For a JSON action whose content is an array of objects (a product feed): the rows, every
		value as text. Null for anything else.
	 **/
	public fun contentJsonArray(): List<Map<String, String>>?
	{	if (!isJson)
		{	return null
		}
		val array = try
		{	Json.parseToJsonElement(content ?: return null) as? JsonArray
		}
		catch (e: Exception)
		{	null
		}
		return array?.filterIsInstance<JsonObject>()?.map { row ->
			row.mapValues { (_, value) ->
				when (value)
				{	is JsonNull -> "null"
					is JsonPrimitive -> value.content
					else -> value.toString()
				}
			}
		}
	}

	/**	For an HTML action: a complete page that runs it in a WebView - Personyze's WebView
		library, the helper scripts, the content, and the click handling that reports to
		`personyze_message_handler`. [PersonyzeActionView] renders it for you; this is for a WebView
		of your own. Null for other content types or before [isLoaded].
	 **/
	public fun contentHtmlDocument(): String? = HtmlDocument.build(this, assetsUrl, "")

	/**	Report that this action was shown (`executed`). [PersonyzeActionView] does it for you. **/
	public fun reportExecuted(): Unit = report { it.reportExecuted(id) }

	/**	Report a click on the action's call to action (`target`) - what the Clicks criterion of an
		A/B test counts, and only that.
	 **/
	public fun reportEngaged(): Unit = report { it.reportEngaged(id) }

	/**	Report that the visitor closed the action; with [dontShowForSessions] > 0 it is not
		presented again for that many sessions.
	 **/
	public fun reportClosed(dontShowForSessions: Int = 0): Unit = report { it.reportClosed(id, dontShowForSessions) }

	public fun reportProductClicked(productId: String): Unit = report { it.reportProductClicked(id, productId) }

	public fun reportArticleClicked(articleId: String): Unit = report { it.reportArticleClicked(id, articleId) }

	/**	Report that the action could not be presented; the message appears in Live visits. **/
	public fun reportError(message: String): Unit = report { it.reportError(id, message) }

	private inline fun report(block: (PersonyzeApi) -> Unit)
	{	(owner ?: if (Personyze.isInitialized) Personyze.client else null)?.let(block)
	}

	override fun equals(other: Any?): Boolean =
		other is PersonyzeAction && id == other.id && data == other.data && isLoaded == other.isLoaded &&
		cacheVersion == other.cacheVersion && contentType == other.contentType && contentParam == other.contentParam &&
		contentBegin == other.contentBegin && contentEnd == other.contentEnd && name == other.name &&
		placeholders == other.placeholders && placeholderOrder == other.placeholderOrder && libs == other.libs

	override fun hashCode(): Int = 31*(31*id + data.hashCode()) + (if (isLoaded) 1 else 0)

	override fun toString(): String = "PersonyzeAction(id=$id, name=$name, contentType=$contentType, isLoaded=$isLoaded)"
}

/**	A campaign the visitor matches. **/
public class PersonyzeCondition internal constructor
(	public val id: Int,
	/**	The campaign's name in the panel, once loaded. **/
	public val name: String?
)
{	override fun equals(other: Any?): Boolean = other is PersonyzeCondition && id == other.id && name == other.name
	override fun hashCode(): Int = id
	override fun toString(): String = "PersonyzeCondition(id=$id, name=$name)"
}

/**	A placeholder an action is targeted to: in an app, a named slot of your own. **/
public class PersonyzePlaceholder internal constructor
(	public val id: Int,
	public val name: String,
	/**	The placeholder's HTML id or selector as set in the panel. **/
	public val htmlId: String,
	/**	How many actions it holds; 0 means no limit. **/
	public val unitsCountMax: Int
)
{	override fun equals(other: Any?): Boolean =
		other is PersonyzePlaceholder && id == other.id && name == other.name && htmlId == other.htmlId && unitsCountMax == other.unitsCountMax
	override fun hashCode(): Int = id
	override fun toString(): String = "PersonyzePlaceholder(id=$id, name=$name, htmlId=$htmlId)"
}
