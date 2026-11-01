package com.personyze.android.internal.core

import com.personyze.android.PersonyzeAction
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireTest
{	/**	The field names the guide documents, nothing renamed (SPEC section 1). **/
	@Test fun requestBodyHasTheGuidesFields()
	{	val body = Wire.json.parseToJsonElement(
			Wire.requestBody(
				JsonPrimitive(500123456),
				null,
				true,
				listOf(1761220800L, 1761000000L),
				DeviceContext(3.0, "en-US,he-IL", "1080x2400", "Android/14", "phone"),
				false,
				listOf(listOf("Navigate", "urn:personyze:doc:Home"), listOf("User profile", "gender", null))
			)
		).jsonObject
		assertEquals(listOf("user_id", "session_id", "new_session", "past_sessions", "platform", "time_zone", "languages", "screen", "os", "device_type", "noti_enabled", "commands"), body.keys.toList())
		assertEquals("500123456", body["user_id"]!!.jsonPrimitive.content)
		assertTrue(body["user_id"]!!.jsonPrimitive.isString.not())
		assertEquals(JsonNull, body["session_id"])
		assertEquals("1761220800,1761000000", body["past_sessions"]!!.jsonPrimitive.content)
		assertEquals("Android", body["platform"]!!.jsonPrimitive.content)
		assertEquals(JsonNull, body["commands"]!!.jsonArray[1].jsonArray[2])
	}

	@Test fun responseIsReadLeniently()
	{	val r = Wire.parseResponse("""{"session_id":"1 2 3 1","cache_version":"17","conditions":[{"id":42},{"id":"43"}],"actions":[{"id":88,"data":{"data":"x","n":5,"o":{"a":1}}},{"id":89}],"dismiss_conditions":[1],"dismiss_actions":["51"],"extra":true}""")!!
		assertEquals("1 2 3 1", r.sessionId)
		assertEquals(17, r.cacheVersion)
		assertEquals(listOf(42, 43), r.conditions)
		assertEquals(mapOf("data" to "x", "n" to "5", "o" to "{\"a\":1}"), r.actions[0].data)
		assertNull(r.actions[1].data)
		assertEquals(listOf(51), r.dismissActions)
	}

	@Test fun notTheTrackersJsonIsNull()
	{	assertNull(Wire.parseResponse("<html>"))
		assertNull(Wire.parseResponse("[]"))
		assertNull(Wire.parseResponse("{\"a\":1}"))
		assertNull(Wire.parseResponse("""{"session_id":"","actions":[]}""")!!.sessionId)
	}

	@Test fun intListReadsArraysAndCsv()
	{	assertEquals(listOf(1, 2), Wire.intList(Wire.json.parseToJsonElement("[1,\"2\"]")))
		assertEquals(listOf(3, 4), Wire.intList(JsonPrimitive("3, 4")))
	}
}

class HtmlDocumentTest
{	private fun action(contentType: String = "text/html", libs: List<String> = listOf("timer", "survey"), loaded: Boolean = true): PersonyzeAction =
		PersonyzeAction(88, mapOf("data" to "<b>Hi \${action_id}</b>"), "Banner", contentType, emptyList(), null, libs, "data", "<div>", "</div>", 17, loaded)

	@Test fun contentIsBeginDataEndWithTheActionId()
	{	assertEquals("<div><b>Hi 88</b></div>", action().content)
		assertNull(action(loaded = false).content)
	}

	/**	v1's page, libraries from actions/default (actions/webkit 404s in production). **/
	@Test fun documentLoadsLibrariesFromDefault()
	{	val doc = HtmlDocument.build(action(), ASSETS, "")!!
		assertTrue(doc.startsWith("<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\">"))
		assertTrue(doc.contains("<script src=\"https://counter.personyze.com/web-view.js?v=17\"></script>"))
		assertTrue(doc.contains("<script src=\"https://counter.personyze.com/actions/default/timer.js?v=17\"></script><script src=\"https://counter.personyze.com/actions/default/survey.js?v=17\"></script>"))
		assertTrue(doc.endsWith("<div><b>Hi 88</b></div><script>_S_T.new_elem(document.body, null)</script></body></html>"))
		assertTrue(!doc.contains("webkit"))
	}

	@Test fun notHtmlHasNoDocument()
	{	assertNull(HtmlDocument.build(action("application/json"), ASSETS, ""))
		assertNull(HtmlDocument.build(action(loaded = false), ASSETS, ""))
	}

	@Test fun jsonContentAsRows()
	{	val a = PersonyzeAction(9, mapOf("data" to """[{"id":"A1","price":9.5,"tags":["x"],"n":null},"skip"]"""), "Feed", "application/json", emptyList(), null, emptyList(), "data", "", "", 1, true)
		assertEquals(listOf(mapOf("id" to "A1", "price" to "9.5", "tags" to "[\"x\"]", "n" to "null")), a.contentJsonArray())
		assertNull(action().contentJsonArray())
	}

	@Test fun libsSplitOnCommas()
	{	assertEquals(listOf("timer", "survey"), HtmlDocument.libsOf(" timer, ,survey ,"))
	}
}

class NotificationPollTest
{	@Test fun nullMeansNothingQueued()
	{	assertNull(NotificationPoll.parse("null"))
		assertNull(NotificationPoll.parse(""))
	}

	@Test fun parsesTheQueuedMessage()
	{	val n = NotificationPoll.parse("""{"message_id":"12","title":" Sale ","body":"50% off","badge":"","icon":"https://x/i.png","image":"","tag":"https://shop/sale","vibrate":"200, 100,x","silent":false,"renotify":1,"actions":[{"title":"Open","icon":"","action":"https://shop/a"},{"title":"","icon":"","action":"https://shop/b"},{"title":"Later","icon":null,"action":null}]}""")!!
		assertEquals(12L, n.messageId)
		assertEquals("Sale", n.title)
		assertEquals("https://shop/sale", n.url)
		assertEquals(listOf(200L, 100L), n.vibrate)
		assertEquals(false, n.silent)
		assertEquals(true, n.renotify)
		assertEquals(listOf("Open"), n.buttons.map { it.title })
		assertTrue("a server from before the reports", n.shownUrl.isEmpty() && n.clickUrl.isEmpty())
	}

	@Test fun parsesTheReportAddresses()
	{	val n = NotificationPoll.parse("""{"message_id":3,"title":"t","body":"b","shown_url":"https://pic/white/k=a/u=7","click_url":" https://pic/white/k=a/u=7/extra=1 "}""")!!
		assertEquals("https://pic/white/k=a/u=7", n.shownUrl)
		assertEquals("https://pic/white/k=a/u=7/extra=1", n.clickUrl)
	}

	@Test fun titleAndBodyAreRequired()
	{	assertNull(NotificationPoll.parse("""{"message_id":1,"title":"","body":"b"}"""))
		assertNull(NotificationPoll.parse("""{"message_id":1,"title":"t","body":" "}"""))
		assertNull(NotificationPoll.parse("""{"title":"t","body":"b"}"""))
	}
}

class VerdictParserTest
{	@Test fun parsesTheVerdict()
	{	val v = VerdictParser.parse("""{"condition_id":273,"condition_name":"PHP SDK A/B test","has_winner":false,"winner_actiongroup_id":null,"message":"Not enough data","control_group_rotation":20,"max_duration_reached":false,"goals":[{"ab_goal":"clicks","title":"Clicks","lower_is_better":false,"best":{"actiongroup_id":147,"name":"A","rate":0.1},"compared_to":{"actiongroup_id":0,"name":"Control","rate":null},"significance_prc":null,"improvement_prc":1.5,"min_significance_prc":95,"min_improvement_prc":0,"reached":false,"summary":"","groups":[{"actiongroup_id":147,"name":"A","value":3,"denom":30,"rate":0.1}]}]}""")!!
		assertEquals(273, v.conditionId)
		assertEquals(null, v.winnerActiongroupId)
		assertEquals(20.0, v.controlGroupRotation, 0.0)
		assertEquals(147, v.goals[0].best!!.actiongroupId)
		assertEquals(null, v.goals[0].comparedTo!!.rate)
		assertEquals(1.5, v.goals[0].improvementPrc!!, 0.0)
		assertEquals(30.0, v.goals[0].groups[0].denom!!, 0.0)
	}
}

/**	The engine runs on a plain JVM; Android stays in `internal.android` and the public views. **/
class CoreIsPureJvmTest
{	@Test fun noAndroidImportsInCore()
	{	val dir = java.io.File("src/main/kotlin/com/personyze/android/internal/core")
		assertTrue("run from the module directory", dir.isDirectory)
		val offenders = dir.listFiles()!!.filter { f -> f.readLines().any { it.startsWith("import android.") || it.startsWith("import androidx.") } }.map { it.name }
		assertEquals(emptyList<String>(), offenders)
	}
}
