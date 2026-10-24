package com.personyze.android.internal.core

import com.personyze.android.PersonyzeAction

/**	The page an HTML action runs in inside a WebView - the v1 SDK's, with one fix.

	`web-view.js` is Personyze's WebView library (php-service `templates/web-view`): it runs the
	content's templates and reports clicks to `personyze_message_handler`. The helper scripts an
	action needs (`libs_app`: timer, survey, slider...) come from `actions/default/`. v1 asked for
	`actions/webkit/`, which is never built (CompileActionsJs writes only `default`), so every
	action needing one rendered without it: 404 in production, measured 2026-10-23.
 **/
internal object HtmlDocument
{	fun build(action: PersonyzeAction, assetsUrl: String, extraHead: String): String?
	{	if (!action.isHtml)
		{	return null
		}
		val content = action.content ?: return null
		val v = action.cacheVersion
		val sb = StringBuilder(content.length + 1024)
		sb.append("<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
		sb.append(extraHead)
		sb.append("</head><body style=\"visibility:hidden\" onload=\"document.body.style.visibility=''\"><script src=\"")
		sb.append(attr(assetsUrl + "web-view.js?v=" + v))
		sb.append("\"></script>")
		for (lib in action.libs)
		{	sb.append("<script src=\"")
			sb.append(attr(assetsUrl + "actions/default/" + Endpoints.encodeUriComponent(lib) + ".js?v=" + v))
			sb.append("\"></script>")
		}
		sb.append(content)
		sb.append("<script>_S_T.new_elem(document.body, null)</script></body></html>")
		return sb.toString()
	}

	private fun attr(s: String): String = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

	fun libsOf(libsApp: String): List<String> = libsApp.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}
