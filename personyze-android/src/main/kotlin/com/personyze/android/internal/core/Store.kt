package com.personyze.android.internal.core

import java.io.File
import java.io.FileOutputStream

/**	Named text files. Writes are atomic: a reader sees the old file or the new one, never half. **/
internal interface Store
{	fun read(name: String): String?
	fun write(name: String, text: String, sync: Boolean = false)
	fun delete(name: String)
	/**	Keep the file but move it aside (a corrupt state, for a later look). **/
	fun quarantine(name: String)
	fun clear()
}

/**	Plain files in [dir], written to `name.tmp` and renamed over the old one. The SDK is the only
	writer (one engine per storage name and process), so there is no locking between processes:
	WorkManager runs the workers in the app's own process.
 **/
internal class FileStore(private val dir: File) : Store
{	@Synchronized override fun read(name: String): String? =
		try
		{	File(dir, name).takeIf { it.isFile }?.readText(Charsets.UTF_8)
		}
		catch (e: Exception)
		{	null
		}

	@Synchronized override fun write(name: String, text: String, sync: Boolean)
	{	dir.mkdirs()
		val tmp = File(dir, "$name.tmp")
		FileOutputStream(tmp).use { out ->
			out.write(text.toByteArray(Charsets.UTF_8))
			if (sync)
			{	out.fd.sync()
			}
		}
		if (!tmp.renameTo(File(dir, name)))
		{	File(dir, name).delete()
			tmp.renameTo(File(dir, name))
		}
	}

	@Synchronized override fun delete(name: String)
	{	File(dir, name).delete()
	}

	@Synchronized override fun quarantine(name: String)
	{	File(dir, name).renameTo(File(dir, "$name.corrupt"))
	}

	@Synchronized override fun clear()
	{	dir.listFiles()?.forEach { it.delete() }
	}
}

internal class MemoryStore : Store
{	val files = HashMap<String, String>()

	@Synchronized override fun read(name: String): String? = files[name]

	@Synchronized override fun write(name: String, text: String, sync: Boolean)
	{	files[name] = text
	}

	@Synchronized override fun delete(name: String)
	{	files.remove(name)
	}

	@Synchronized override fun quarantine(name: String)
	{	files.remove(name)?.let { files["$name.corrupt"] = it }
	}

	@Synchronized override fun clear()
	{	files.clear()
	}
}
