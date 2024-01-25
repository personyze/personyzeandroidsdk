# class PersonyzeAction (Personyze Android SDK reference)

This object represents an “action” (in most cases - HTML content) that you want to execute in your application. You get action objects from call to [PersonyzeTracker.inst.getResult()](../PersonyzeTracker/README.md#personyzetrackerinstgetresult)

### action.getId()

```java
int getId()
```

Returns the action ID that distinguishes one action from another. This is Personyze internal property.

### action.getName()

```java
String getName()
```

Returns the action name - how you called it in the Personyze panel. The result is never null.

### action.getContentType()

```java
String getContentType()
```

Returns MIME type of action's content. It depends on action type. If you create a Banner, or a Recommendation action, the MIME type is “text/html”. In Personyze panel you can create action of type Javascript, and target it to the application, and the MIME type will be “application/javascript”. There is no reason to do this, because there is nothing to do with Javascript code in a mobile application. So you can decide how and whether to handle this action according to it's MIME type.

### action.getContent()

```java
String getContent()
```

Returns the raw content of the action. It can be an HTML string, a JSON stringified value, or something else. The result is never null.

### action.getContentJsonArray()

```java
ArrayList<HashMap<String, String>> getContentJsonArray()
```

Assuming that the action raw content was a JSON stringified array of objects, this method gets the action content as `ArrayList<HashMap<String, String>>`. If the action's content cannot be parsed this way, or if MIME type was not “application/json”, this method returns null.

### action.getContentHtmlDoc()

```java
String getContentHtmlDoc()
```

If the MIME type of the action is “text/html”, this method wraps the HTML in <html>, <head> and <body> tags, and adds all the libraries necessary to run this HTML in a WebView widget. It installs capturing onclick event handler that prevents the user from leaving this HTML page by clicking on a hyperlink.

You will want to allow javascript in this WebView by calling

```java
webView.getSettings().setJavaScriptEnabled(true);
```

Or just use `action.renderOnWebView()` (see below).

### action.renderOnWebView()

```java
void renderOnWebView(Context context, WebView webView)
void renderOnWebView(Context context, WebView webView, final PersonyzeTracker.Callback<Clicked> callbackClicked)
```

If the MIME type of the action is “text/html”, this method renders the HTML on a provided WebView component. It uses `action.getContentHtmlDoc()` to get the HTML, calls `webView.getSettings().setJavaScriptEnabled(true)` to allow javascript, and installs click event handlers, that allow you to react on things clicked inside the HTML document.

When user clicks a hyperlink, the default behavior is suppressed, but you will catch this click, and get the link URL. To do this `webView.addJavascriptInterface()` is utilized.

Personyze HTML actions can contain whatever HTML content that you put in the Personyze panel when you create the action. There can be hyperlinks. Also Personyze allows to put “close this action” buttons, so probably you want to react on them clicked too. Recommendation actions also report about recommended products clicked.

You can catch all these user-generated events through the `asyncClicked` parameter. If you're not interested in catching events, you can omit this parameter.

Here is typical usage example:

```java
action.renderOnWebView
(	context,
	webView,
	clicked ->
	{	// Got event. This means that you clicked some sensitive region
		// I report this event to Personyze. So there will be CTR and close-rate statistics, and widget contribution rate (products bought from this action)
		PersonyzeTracker.inst.reportActionClicked(context, clicked);
		// Print out the information about this click
		Log.w("Personyze", String.format("clicked=%s; arg=%s; href=%s", clicked.status, clicked.arg, clicked.href));
	}
);
```

The `PersonyzeAction.Clicked` is dumb structure with the following fields:

- int actionId
- String href - if a hyperlink clicked, this is it's URL. Otherwise empty string (never null).
- String status - one of “target”, “close”, “product”, “article”.
- String arg - for a product or an article - it's Internal ID. For “close” this is number of sessions not to show again.

If you handle this click event, report to Personyze about it by calling [PersonyzeTracker.inst.reportActionClicked()](../PersonyzeTracker/README.md#personyzetrackerinstreportactionclicked), so Personyze will be able to show you CTR, close-rates, recommendation contribution value, and other statistics about how this action performs.

### action.reportExecuted()

```java
void reportExecuted(Context context)
```

If you present this action in a custom way (not through `action.renderOnWebView()`), call this function after the action is shown to the user, so action status will be changed to “executed” in Personyze dashboard, and execution statistics will be counted.

### action.reportClick()

```java
void reportClick(Context context)
```

If you present this action in a custom way (not through `action.renderOnWebView()`), and this action is considered “clickable”, call this method to report to Personyze that it was clicked. Later Personyze will show you CTR (percent of clicked per shown) statistics that will indicate the performance of this action.

### action.reportClose()

```java
void reportClose(Context context)
void reportClose(Context context, int dontShowSessions)
```

If you present this action in a custom way (not through `action.renderOnWebView()`), and this action is considered “closable”, call this method when you dismiss the action content from the screen __as the result of user's intent to close__ it (the user doesn't want to see it). Later Personyze will show you close-rate (percent of closed per shown) statistics that will indicate the performance of this action.

- dontShowSessions - If provided, Personyze will not show (return from [PersonyzeTracker.inst.getResult()](../PersonyzeTracker/README.md#personyzetrackerinstgetresult)) this action this number of sessions. By default the session is 1.5 hours, but you can force start of new session by calling [PersonyzeTracker.inst.startNewSession()](../PersonyzeTracker/README.md#personyzetrackerinststartnewsession).

### action.reportProductClick()

```java
void reportProductClick(Context context, String productId)
```

If you present this action in a custom way (not through `action.renderOnWebView()`), and this action is showing product recommendations, call this method to report user's click on certain product. Later Personyze will show you contribution rate of this action and other statistics.

- productId - the “Internal ID” column in your products catalog, that you imported to Personyze.

### action.reportArticleClick()

```java
void reportArticleClick(Context context, String articleId)
```

### action.reportError()

```java
void reportError(Context context, String message)
```

If something went wrong with presenting this action to the user, it's recommended to call this method. This will set Personyze state of this action to “not executed”, and you will see the error message in Live Visits dashboard in the Personyze panel.
