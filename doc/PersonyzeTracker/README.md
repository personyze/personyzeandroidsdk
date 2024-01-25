# class PersonyzeTracker (Personyze Android SDK reference)

The main class is called PersonyzeTracker. It is singleton, so you don't need to instantiate it, just call it's default instance's (PersonyzeTracker.inst) methods. By design, this class will persist it's Personyze state (that includes what campaigns are matching, and what actions are currently to be presented) across application shut down. You don't need to manage the state. Immideately after calling `PersonyzeTracker.inst.initialize()` (see below), the current state will be available. That is, `PersonyzeTracker.inst.getResult()` will always return what campaigns are currently matching for the device user, or were matching last time.

All the methods of PersonyzeTracker are thread-safe, and don't block GUI. They can be used from an Activity, Service or from somewhere else.

All the methods except `PersonyzeTracker.inst.getResult()` and `PersonyzeTracker.inst.done()` don't query Personyze server, but delay their effect till `PersonyzeTracker.inst.getResult()` or `PersonyzeTracker.inst.done()` called.

Typical example:

```java
// Before calling other methods, need to initialize
PersonyzeTracker.inst.initialize(context, "8C03F1C7FF861065B3D063CD6A4DD0F8E8008FE9"); // replace API key with yours
// Log navigation to document called MainActivity
PersonyzeTracker.inst.navigate("MainActivity");
// Log that in this document product ID A1000 was viewed
PersonyzeTracker.inst.productViewed("A1000");
// Get current state, that includes matching campaigns ("conditions") and actions to be presented
PersonyzeTracker.inst.getResult(context).addOnCompleteListener
(	task->
	{	if (task.getException() == null)
		{	// Loaded successfully
			PersonyzeResult result = task.getResult();
			Toast.makeText(this, String.format(Locale.US, "%d conditions", result.conditions.size()), Toast.LENGTH_LONG).show();
		}
		else
		{	// Error with the requested things
			Toast.makeText(this, "E: "+task.getException().getMessage(), Toast.LENGTH_LONG).show();
		}
	}
);
```

### PersonyzeTracker.inst.initialize()

```java
void initialize(Context context, String apiKey)
void initialize(Context context, String apiKey, boolean notiEnabled)
```

This method is the first one you need to call, before calling other methods. You can freely call it several times, this will not hurt.

- context - This can be any kind of context: application, activity or service. In any case Personyze will convert it to application context.
- apiKey - Your account's API key.
- notiEnabled - If true, will register to receive notifications from Personyze. The registered service will run on the background, and automatically restart after device reboot. If notiEnabled is set to false, the notification service will be deregistered.

### PersonyzeTracker.inst.navigate()

```java
void navigate(String documentName)
```

Personyze is event-driven system. What actions will be presented, and what products recommended depends on user's behavior. So you need to log what your user is doing, like what document he is viewing.

- documentName - the “document” is whatever you define like that. Typically it is an activity name. In Personyze panel you can specify on what documents actions need to be presented, and what actions (e.g. what recommendation algorithm).

If you call PersonyzeTracker.inst.navigate(), and then report product interaction events (see below), these events will be attributed to the current document the user is in - the document you logged through this method.

### PersonyzeTracker.inst.logUserData()

```java
void logUserData(String field, String value)
```

Send to Personyze information about the user, like his email, gender, and so on. Later in Personyze panel you will be able to use this information. Either to target specific users, to show appropriate products, or to print this information inside an action content. For example, if you send a user email to Personyze, you will be able to create a mailing list, so this user will receive promotional or informative emails.

- field - can be any field name, like “first_name”, “email”, or “Boots size”.
- value - the field's value (like “Johnny”)

### PersonyzeTracker.inst.productViewed()

```java
void productViewed(String productId)
```

Report to Personyze the fact that certain product was viewed by your visitor (because you probably just presented it in the application). For Personyze it's important to know that the product was viewed. You can base recommendations on last viewed product, or all viewed products. And also Personyze can avoid showing the currently viewing product in recommendations (because the user sees it anyway).

- productId - the “Internal ID” column in your products catalog, that you imported to Personyze.

### PersonyzeTracker.inst.productAddedToCart()

```java
void productAddedToCart(String productId)
```

Report to Personyze the fact that certain product was added to cart in your application. You can have recommendations based on currently in-cart products. And if the user abandons the purchase, you can create an email campaign that will recommend to the user products similar to those he had in his cart.

- productId - the “Internal ID” column in your products catalog, that you imported to Personyze.

### PersonyzeTracker.inst.productLiked()

```java
void productLiked(String productId)
```

If your application supports wishlists, or favorites, or something similar, call this method to report Personyze about such event. Personyze will save the state of this product as “liked”, and you will be able to create action that presents all the liked products (that you reported).

- productId - the “Internal ID” column in your products catalog, that you imported to Personyze.

### PersonyzeTracker.inst.productPurchased()

```java
void productPurchased(String productId)
```

Report to Personyze that a product was purchased through your application.

- productId - the “Internal ID” column in your products catalog, that you imported to Personyze.

### PersonyzeTracker.inst.productUnliked()

```java
void productUnliked(String productId)
```

A product removed from wishlist in your application. If such product was not reported as “liked” in the past, this call will have no effect.

- productId - the “Internal ID” column in your products catalog, that you imported to Personyze.

### PersonyzeTracker.inst.productRemovedFromCart()

```java
void productRemovedFromCart(String productId)
```

A product removed from cart in your application. If such product was not reported as “added to cart” in the past, this call will have no effect.

- productId - the “Internal ID” column in your products catalog, that you imported to Personyze.

### PersonyzeTracker.inst.productsPurchased()

```java
void productsPurchased()
```

Report that all the products that were reported as “added to cart” were purchased in your application. So they will become purchased on Personyze too.

### PersonyzeTracker.inst.productsUnliked()

```java
void productsUnliked()
```

Report that all the products that were reported as “liked” were removed from wishlist in your application.

### PersonyzeTracker.inst.productsRemovedFromCart()

```java
void productsRemovedFromCart()
```

Report that all the products that were reported as “added to cart” were removed from cart in your application, that is, the cart was emptied.

### PersonyzeTracker.inst.articleViewed()

```java
void articleViewed(String articleId)
```

### PersonyzeTracker.inst.articleLiked()

```java
void articleLiked(String articleId)
```

### PersonyzeTracker.inst.articleCommented()

```java
void articleCommented(String articleId)
```

### PersonyzeTracker.inst.articleUnliked()

```java
void articleUnliked(String articleId)
```

### PersonyzeTracker.inst.articleGoal()

```java
void articleGoal(String articleId)
```

### PersonyzeTracker.inst.reportActionClicked()

```java
void reportActionClicked(Context context, PersonyzeAction.Clicked clicked)
```

When you present actions that `PersonyzeTracker.inst.getResult()` returns in your application through `action.renderOnWebView()` method, the action is rendered and the user can interact with it. Like click on links in the action HTML, click “close” button (that is supposed to close the action), click on a product in a recommendation widget, and so on.

In these cases `action.renderOnWebView()` generates events, which are represented by `PersonyzeAction.Clicked` objects. If you handle those events (closes the WebView widget if “close action” clicked, etc.), report to Personyze that you handled them by calling this method.

If you present an action in custom way (not through `action.renderOnWebView()`), still you are invited to report action events to Personyze by calling the following methods of PersonyzeAction object:

- [action.reportExecuted()](../PersonyzeAction/README.md#actionreportexecuted)
- [action.reportClick()](../PersonyzeAction/README.md#actionreportclick)
- [action.reportClose()](../PersonyzeAction/README.md#actionreportclose)
- [action.reportProductClick()](../PersonyzeAction/README.md#actionreportproductclick)
- [action.reportArticleClick()](../PersonyzeAction/README.md#actionreportarticleclick)

### PersonyzeTracker.inst.getResult()

```java
Task<PersonyzeResult> getResult(Context context)
```

This is what we are here for. You called all the above methods in order to eventually get from Personyze what campaigns are matching, and what actions are to be presented now to the application user.

You can call this method at any time after `PersonyzeTracker.inst.initialize()`: before `PersonyzeTracker.inst.navigate()` or after. And you can call it several times. The result this method (asynchronously) returns will be different only after some events and/or navigation reports.

As already mentioned above, this state is persistent across application life cycle (this method will still return the same result after application restart).

Typical usage is like this:

```java
PersonyzeTracker.inst.getResult(context).addOnCompleteListener
(	task->
	{	if (task.getException() == null)
		{	// Have result
			PersonyzeResult result = task.getResult();
			// ...
		else
		{	// Some problem
		}
	}
);
```

TrackerResult is dumb structure that only has two fields:

```java
ArrayList<PersonyzeCondition> conditions;
ArrayList<PersonyzeAction> actions;
```

- conditions - this is rarely needed. Only if you want to manually implement some logic in your application depending on what conditions (campaigns) are matching to the user.
- actions - this is the array of actions that you want to present to the user in your application

How will you present the actions, and will you, is your choice. This library provides a way to render HTML actions on a WebView component in your application. Also it gives you access to the HTML or JSON content of the action that you can present by yourself in a custom way.

### PersonyzeTracker.inst.startNewSession()

```java
void startNewSession(Context context)
```

You can optionally call this method when your application was restarted, to separate events by different sessions. Personyze will automatically start new session 1.5 hours after first navigation.

### PersonyzeTracker.inst.done()

```java
void done(Context context)
```

If you report an event, or series of events and/or navigations, and don't call `PersonyzeTracker.inst.getResult()`, call this method, to send pending events to Personyze. It doesn't hurt to call this method several times.

### PersonyzeTracker.inst.clearCache()

```java
void clearCache(Context context)
```

This library caches various data, like action names, and content to files, and to `SharedPreferences`. It effectively manages the cache by itself, and normally you don't need to call this method. Only call for debug purposes.
