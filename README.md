# personyzeandroidsdk
This library allows you to use [Personyze system](https://www.personyze.com/) on Android devices.

See [reference manual](./doc/README.md).

See also:

- Personyze Android SDK
- [Personyze Android SDK Demo app](https://github.com/personyze/personyzeandroidsdk-demo)
- [Personyze iOS SDK](https://github.com/personyze/personyze-ios-sdk)
- [Personyze iOS SDK Demo app](https://github.com/personyze/personyze-ios-sdk-demo)

## How to get started with the Personyze Android SDK

1. First you need to download the SDK files from [here](https://github.com/personyze/personyzeandroidsdk/archive/refs/heads/main.zip).
2. Start Android Studio. This guide was using Android Studio version 2021.2.1. Create new project. Select “Phone and Tablet” > “Empty Activity” and click “Next”.
3. Enter desired project name, or leave as suggested and click “Finish”.
4. Unzip the SDK. There must be directory called “personyzeandroidsdk”. Put it to the project root directory.
5. In the Project window on the left, where project files are listed, notice “Gradle Scripts”, expand it and edit the “settings.gradle” file.

Initially this file ends with such line:

```gradle
include ':app'
```

Change this line to:

```gradle
include ':app', ':personyzeandroidsdk'
```

6. The IDE will prompt to sync the file. Click “Sync Now”.
7. Find “app/build.gradle” file. This file starts with this line: “plugins { id 'com.android.application' }”. In the “dependencies” section add this line:

```gradle
implementation project(path: ':personyzeandroidsdk')
```

8. The IDE again will prompt to sync the file, so do it again.
9. Your app must have INTERNET permission, so add it. In the “app/src/main/AndroidManifest.xml” file, in `<manifest>` tag, add:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

10. Some applications also will need to add the following “uses-library” to “app/src/main/AndroidManifest.xml”:

```xml
<application
	...>
	<uses-library
			android:name="org.apache.http.legacy"
			android:required="false" />
```

This is according to [Apache HTTP Legacy library](https://developers.google.com/maps/documentation/android-sdk/config#apache_http_legacy_library)

11. Now the SDK must be usable.

To be sure add this code to your MainActivity, or elsewhere:

```java
PersonyzeTracker.inst.initialize(this, "0011223344556677889900112233445566778899");
PersonyzeTracker.inst.navigate("MainActivity");
PersonyzeTracker.inst.done();
```

Use your API key instead of “0011223344556677889900112233445566778899”. API keys for your account can be found on Personyze in the [“Settings” → “Integrations” page](https://personyze.com/site/tracker/condition#cat=Account%20settings%2FMain%20settings%2FIntegrations). You need the full-featured key.

The code above logs a navigation to page called “MainActivity”.

You can look up this request on Personyze [Live Visits Dashboard](https://personyze.com/site/tracker/condition#cat=Dashboard%2FReal%20time%20visitors). In filters, select “Pages” → “only containing” → “MainActivity”.
