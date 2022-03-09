/**

This package is for Android application developers, who want to use the Personyze
personalization/recommendation system.
Personyze provides facility for generating Product Recommendations
and getting event-driven dynamic content inside your app.

The main class that this package provides is called {@link com.personyze.androidsdk.PersonyzeTracker}.

<pre>{@code
	// Before calling other methods, need to initialize
	PersonyzeTracker.inst.initialize(context, "8C03F1C7FF861065B3D063CD6A4DD0F8E8008FE9"); // replace API key with yours
	// Log navigation to document called MainActivity
	PersonyzeTracker.inst.navigate("MainActivity");
	// Log that in this document product ID A1000 was viewed
	PersonyzeTracker.inst.productViewed("A1000");
	// Get current state, that includes matching campaigns ("conditions") and actions to be presented
	PersonyzeTracker.inst.getResult
	(	new PersonyzeTracker.AsyncResult<PersonyzeResult>()
		{	&#64;Override public void success(PersonyzeResult result)
			{	 Toast.makeText(context, String.format(Locale.US, "%d conditions", result.conditions.length), Toast.LENGTH_LONG).show();
			}

			&#64;Override public void error(String message)
			{	Toast.makeText(context, "E: "+message, Toast.LENGTH_LONG).show();
			}
		}
	);
}</pre>

*/

package com.personyze.androidsdk;
