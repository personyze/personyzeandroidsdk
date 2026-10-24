package com.personyze.android.internal.android

import android.content.Context
import com.personyze.android.internal.core.LegacySource
import com.personyze.android.internal.core.LegacyState
import java.io.File

/**	The v1 SDK's state (SharedPreferences "Personyze Tracker"), read once so an upgraded app keeps
	its visitors - their history and their A/B arms. Its templates cache is not worth migrating;
	it reloads.
 **/
internal class LegacyMigration(private val context: Context) : LegacySource
{	override fun read(): LegacyState?
	{	// getSharedPreferences() would create the file; look first.
		if (!File(context.applicationInfo.dataDir, "shared_prefs/$PREFS.xml").isFile)
		{	return null
		}
		val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
		val userId = safe { p.getInt("User ID", 0) } ?: 0
		val session = safe { p.getString("User", null) }
		if (userId == 0 && session == null)
		{	return null
		}
		return LegacyState(
			userId = userId,
			session = session,
			pastSessionsOldestFirst = (safe { p.getString("Past Sessions", "") } ?: "").split(',').mapNotNull { it.trim().toLongOrNull() },
			blocked = (safe { p.getString("Blocked Actions", "") } ?: "").split(',').mapNotNull { kv ->
				val parts = kv.split(':')
				val k = parts.getOrNull(0)?.trim()?.toIntOrNull()
				val v = parts.getOrNull(1)?.trim()?.toIntOrNull()
				if (parts.size == 2 && k != null && v != null && k > 0 && v > 0) k to v else null
			}.toMap(),
			newSession = safe { p.getBoolean("New Session", false) } ?: false,
			notiLastCheckMs = safe { p.getLong("Noti Last Check Time", 0) } ?: 0,
			apiKeyHash = safe { p.getInt("Api Key Hash", 0) } ?: 0
		)
	}

	override fun delete()
	{	context.deleteSharedPreferences(PREFS)
		context.cacheDir.listFiles()?.filter { it.name.startsWith("Personyze Action Data ") }?.forEach { it.delete() }
	}

	private inline fun <T> safe(block: () -> T): T? =
		try
		{	block()
		}
		catch (e: ClassCastException)
		{	null
		}

	companion object
	{	const val PREFS = "Personyze Tracker"
	}
}
