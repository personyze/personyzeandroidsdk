package com.personyze.android.internal.android

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.personyze.android.Personyze
import com.personyze.android.PersonyzeConfig
import com.personyze.android.PersonyzeException
import com.personyze.android.PersonyzeNotification
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

private const val KEY = "0123456789ABCDEF0123456789ABCDEF01234567"

@RunWith(RobolectricTestRunner::class)
class LegacyMigrationTest
{	private val context: Context = ApplicationProvider.getApplicationContext()

	@Test fun readsTheV1Preferences()
	{	context.getSharedPreferences(LegacyMigration.PREFS, Context.MODE_PRIVATE).edit()
			.putInt("User ID", -123456)
			.putString("User", "1761220800 -123456 7 3 1761000000 0 491,492 0")
			.putString("Past Sessions", "100,200,300")
			.putString("Blocked Actions", "88:2,bad,90:0,91:1")
			.putBoolean("New Session", true)
			.putLong("Noti Last Check Time", 5L)
			.putInt("Api Key Hash", KEY.hashCode())
			.commit()
		File(context.cacheDir, "Personyze Action Data 88").writeText("x")
		val old = LegacyMigration(context).read()!!
		assertEquals(-123456, old.userId)
		assertEquals("1761220800 -123456 7 3 1761000000 0 491,492 0", old.session)
		assertEquals(listOf(100L, 200L, 300L), old.pastSessionsOldestFirst)
		assertEquals(mapOf(88 to 2, 91 to 1), old.blocked)
		assertTrue(old.newSession)
		assertEquals(5L, old.notiLastCheckMs)
		assertEquals(KEY.hashCode(), old.apiKeyHash)
		LegacyMigration(context).delete()
		assertNull(LegacyMigration(context).read())
		assertFalse(File(context.cacheDir, "Personyze Action Data 88").exists())
	}

	@Test fun nothingToMigrateCreatesNothing()
	{	assertNull(LegacyMigration(context).read())
		assertFalse(File(context.applicationInfo.dataDir, "shared_prefs/${LegacyMigration.PREFS}.xml").exists())
	}
}

@RunWith(RobolectricTestRunner::class)
class DeviceContextTest
{	private val context: Context = ApplicationProvider.getApplicationContext()

	@Test fun phone()
	{	val d = AndroidPlatform.deviceContext(context)
		assertEquals("phone", d.deviceType)
		assertTrue(d.os, Regex("^Android/[\\d.]+$").matches(d.os))
		assertTrue(d.screen, Regex("^\\d+x\\d+$").matches(d.screen))
	}

	@Config(qualifiers = "xlarge")
	@Test fun tablet()
	{	assertEquals("tablet", AndroidPlatform.deviceContext(context).deviceType)
	}

	/**	Java still says `iw` for Hebrew; the server reads two letters, and wants `he`. **/
	@Config(qualifiers = "iw-rIL")
	@Test fun languageTags()
	{	Locale.setDefault(Locale("iw", "IL"))
		assertTrue(AndroidPlatform.deviceContext(context).languages, AndroidPlatform.deviceContext(context).languages.startsWith("he"))
	}
}

@RunWith(RobolectricTestRunner::class)
class NotificationRendererTest
{	private val context: Context = ApplicationProvider.getApplicationContext()
	private val nm get() = context.getSystemService(NotificationManager::class.java)

	private fun notification(buttons: List<PersonyzeNotification.Button> = emptyList(), url: String = "https://shop.example/sale") =
		PersonyzeNotification(12, "Spring sale", "50% off today", url, "", "", "", buttons, false, false, listOf(100L, 50L))

	@Before fun grant()
	{	shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
	}

	/**	v1 showed only the body (`setContentText` twice). **/
	@Test fun titleAndBodyAreShown() = runBlocking<Unit> {
		val buttons = listOf(PersonyzeNotification.Button("Open", "", "https://shop.example/a"), PersonyzeNotification.Button("Later", "", "https://shop.example/a"))
		assertTrue(NotificationRenderer.show(context, notification(buttons), ImageLoader(OkHttpClient()), "Recommendations", 0))
		val posted = shadowOf(nm).allNotifications.single()
		assertEquals("Spring sale", posted.extras.getString(NotificationCompat.EXTRA_TITLE))
		assertEquals("50% off today", posted.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString())
		assertEquals(NotificationRenderer.CHANNEL_ID, posted.channelId)
		assertEquals("Recommendations", nm.getNotificationChannel(NotificationRenderer.CHANNEL_ID).name)
		// Two buttons opening the same address still get PendingIntents of their own.
		assertEquals(2, posted.actions.size)
		assertNotSame(posted.actions[0].actionIntent, posted.actions[1].actionIntent)
		val content = shadowOf(posted.contentIntent).savedIntent
		assertEquals(Intent.ACTION_VIEW, content.action)
		assertEquals("https://shop.example/sale", content.dataString)
	}

	@Test fun unsafeAddressFallsBackToTheApp() = runBlocking<Unit> {
		NotificationRenderer.show(context, notification(url = "file:///data/x"), ImageLoader(OkHttpClient()), "Recommendations", 0)
		// The test package has no launcher activity, so there is no intent at all - never the file.
		val content = shadowOf(nm).allNotifications.single().contentIntent?.let { shadowOf(it).savedIntent }
		assertFalse(content?.dataString == "file:///data/x")
	}

	@Test fun nothingWhenNotificationsAreOff() = runBlocking<Unit> {
		shadowOf(nm).setNotificationsEnabled(false)
		assertFalse(NotificationRenderer.canShow(context))
		assertFalse(NotificationRenderer.show(context, notification(), ImageLoader(OkHttpClient()), "Recommendations", 0))
		assertTrue(shadowOf(nm).allNotifications.isEmpty())
	}

	@Config(sdk = [34])
	@Test fun nothingWithoutThePermission()
	{	shadowOf(context as Application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
		assertFalse(NotificationRenderer.canShow(context))
	}
}

@RunWith(RobolectricTestRunner::class)
class PersonyzeFacadeTest
{	private val context: Context = ApplicationProvider.getApplicationContext()

	@Before fun work()
	{	WorkManagerTestInitHelper.initializeTestWorkManager(context)
	}

	@After fun shutdown()
	{	Personyze.shutdown()
	}

	@Test fun beforeInitializeNothingThrowsButClient() = runBlocking<Unit> {
		assertFalse(Personyze.isInitialized)
		Personyze.productViewed("A1")
		Personyze.reportExecuted(88)
		assertTrue(Personyze.navigate("Home").error is PersonyzeException.Invalid)
		assertTrue(Personyze.result.value.isEmpty)
		try
		{	Personyze.client
			throw AssertionError("client should throw before initialize()")
		}
		catch (e: IllegalStateException)
		{	assertTrue(e.message!!.contains("Application.onCreate"))
		}
	}

	@Test fun initializeIsIdempotentForTheSameConfig()
	{	val a = Personyze.initialize(context, PersonyzeConfig(KEY))
		assertSame(a, Personyze.initialize(context, PersonyzeConfig(KEY)))
		val b = Personyze.initialize(context, PersonyzeConfig(KEY) { timeout = kotlin.time.Duration.parse("2s") })
		assertNotSame(a, b)
		assertSame(b, Personyze.client)
	}

	@Test fun configRefusesPlainHttpAndHidesTheKey()
	{	try
		{	PersonyzeConfig(KEY) { baseUrl = "http://app.personyze.com" }
			throw AssertionError("http must be refused")
		}
		catch (e: IllegalArgumentException)
		{	assertFalse(e.message!!.contains(KEY))
		}
		val config = PersonyzeConfig(KEY)
		assertFalse(config.toString().contains(KEY))
		assertFalse(Personyze.initialize(context, config).toString().contains(KEY))
	}

	@Test fun notificationsScheduleTheWorkAndCancelV1s()
	{	val wm = WorkManager.getInstance(context)
		Personyze.initialize(context, PersonyzeConfig(KEY) { notifications = true })
		assertEquals(WorkInfo.State.ENQUEUED, wm.getWorkInfosForUniqueWork(WorkScheduling.NOTIFICATIONS).get().single().state)
		Personyze.setNotificationsEnabled(false)
		assertEquals(WorkInfo.State.CANCELLED, wm.getWorkInfosForUniqueWork(WorkScheduling.NOTIFICATIONS).get().single().state)
	}

	/**	The key is never stored, so a worker in a process the app did not initialize does nothing. **/
	@Test fun workersWithoutAClientDoNothing() = runBlocking<Unit> {
		val n = TestListenableWorkerBuilder<PersonyzeNotificationWorker>(context).build()
		assertEquals(ListenableWorker.Result.success(), n.doWork())
		val o = TestListenableWorkerBuilder<PersonyzeOutboxWorker>(context).build()
		assertEquals(ListenableWorker.Result.success(), o.doWork())
	}

	@Test fun stateLivesInNoBackupFiles() = runBlocking<Unit> {
		val client = Personyze.initialize(context, PersonyzeConfig(KEY) { storageName = "t1" })
		client.productViewed("A1")	// no session yet: queued, and saved
		kotlinx.coroutines.delay(500)
		assertTrue(File(context.noBackupFilesDir, "personyze/t1/visitor").isFile)
		assertNotNull(File(context.noBackupFilesDir, "personyze/t1/outbox.json").takeIf { it.isFile })
	}
}
