# Kept in the app that uses the SDK.

# The JavaScript interface old WebViews call: R8 must not rename or drop postMessage().
-keepclassmembers class com.personyze.android.PersonyzeActionView$LegacyJsBridge {
	@android.webkit.JavascriptInterface <methods>;
}

# WorkManager stores worker class names and creates them by reflection.
-keep class com.personyze.android.internal.android.PersonyzeNotificationWorker {
	<init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.personyze.android.internal.android.PersonyzeOutboxWorker {
	<init>(android.content.Context, androidx.work.WorkerParameters);
}
