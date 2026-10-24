package com.personyze.android.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.personyze.android.PersonyzeAction
import com.personyze.android.PersonyzeActionView
import com.personyze.android.PersonyzeClick

/**	Renders an HTML [PersonyzeAction] - a [PersonyzeActionView] inside Compose. It takes the
	content's height unless [modifier] sets one, reports `executed` and the visitor's taps, and calls
	[onClick] with each tap: return true to handle it yourself, else a link opens in the browser and a
	close hides the action.

	```
	val result by Personyze.result.collectAsStateWithLifecycle()
	result.actionsIn("home_banner").firstOrNull()?.let { PersonyzeActionContent(it) }
	```
 **/
@Composable
public fun PersonyzeActionContent(
	action: PersonyzeAction,
	modifier: Modifier = Modifier,
	reportExecuted: Boolean = true,
	reportClicks: Boolean = true,
	onClick: ((PersonyzeClick) -> Boolean)? = null
)
{	AndroidView(
		factory = { context -> PersonyzeActionView(context) },
		modifier = modifier,
		update = { view ->
			view.reportExecuted = reportExecuted
			view.reportClicks = reportClicks
			view.onClick = onClick?.let { handler -> com.personyze.android.PersonyzeClickListener { handler(it) } }
			view.render(action)
		},
		onRelease = { view -> view.destroy() }
	)
}
