package com.personyze.android.internal.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CommandsTest
{	@Test fun navigateEncodesLikeEncodeUriComponent()
	{	assertEquals(listOf("Navigate", "urn:personyze:doc:MainActivity"), Commands.navigate("MainActivity"))
		assertEquals(listOf("Navigate", "urn:personyze:doc:Cart%20page"), Commands.navigate("Cart page"))
		assertEquals("urn:personyze:doc:100%25%20off%2Fnow%3F", Commands.navigate("100% off/now?")[1])
		assertEquals("urn:personyze:doc:a-_.!~*'()b", Commands.navigate("a-_.!~*'()b")[1])
		assertEquals("urn:personyze:doc:%D7%A9%D7%9C%D7%95%D7%9D", Commands.navigate("שלום")[1])
		assertThrows(IllegalArgumentException::class.java) { Commands.navigate(" ") }
	}

	@Test fun productsCarryAttributionPairs()
	{	assertEquals(listOf("Product Viewed", "A1"), Commands.product("Product Viewed", "A1", null, null))
		assertEquals(listOf("Product Purchased", "A1", "action_id", "88", "quantity", "2"), Commands.product("Product Purchased", "A1", 88, 2))
		assertEquals(listOf("Product Added to cart", "A1", "quantity", "3"), Commands.product("Product Added to cart", "A1", null, 3))
		assertThrows(IllegalArgumentException::class.java) { Commands.product("Product Viewed", "", null, null) }
		assertThrows(IllegalArgumentException::class.java) { Commands.product("Product Viewed", "A1", 0, null) }
		assertThrows(IllegalArgumentException::class.java) { Commands.product("Product Viewed", "A1", null, 0) }
	}

	@Test fun articlesAndBareCommands()
	{	assertEquals(listOf("Article Goal", "B2", "action_id", "9"), Commands.article("Article Goal", "B2", 9))
		assertEquals(listOf("Products Removed from cart"), Commands.bare("Products Removed from cart"))
		assertThrows(IllegalArgumentException::class.java) { Commands.article("Article Viewed", " ", null) }
	}

	@Test fun promotionsNeedTheirActionId()
	{	assertEquals(listOf("promotion viewed", "SPRING-HERO", "action_id", "77"), Commands.promotion("promotion viewed", " SPRING-HERO ", 77))
		assertThrows(IllegalArgumentException::class.java) { Commands.promotion("promotion clicked", "X", 0) }
		assertEquals(listOf("coupon used", "WELCOME10"), Commands.coupon("WELCOME10"))
		assertThrows(IllegalArgumentException::class.java) { Commands.coupon("") }
	}

	@Test fun profileIsOneCommandOfPairs()
	{	assertEquals(listOf("User profile", "email", "a@b.c", "gender", null), Commands.profile(linkedMapOf("email" to "a@b.c", "gender" to null)))
		assertThrows(IllegalArgumentException::class.java) { Commands.profile(emptyMap()) }
		assertThrows(IllegalArgumentException::class.java) { Commands.profile(mapOf("" to "x")) }
	}

	@Test fun actionStatusIsFourStrings()
	{	assertEquals(listOf("Action Status", "88", "executed", ""), Commands.actionStatus(88, "executed"))
		assertEquals(listOf("Action Status", "88", "close", "3"), Commands.actionStatus(88, "close", "3"))
		assertThrows(IllegalArgumentException::class.java) { Commands.actionStatus(0, "executed") }
		assertThrows(IllegalArgumentException::class.java) { Commands.actionStatus(1, "clicked") }
	}

	@Test fun oversizedCommandIsRefused()
	{	assertThrows(IllegalArgumentException::class.java) { Commands.raw(listOf("x", "y".repeat(Commands.MAX_COMMAND))) }
		assertThrows(IllegalArgumentException::class.java) { Commands.raw(listOf(" ")) }
	}

	/**	By the visitor id alone: nothing to encode (v1's session string was encoded for a parser that
		no longer exists, and answered 400).
	 **/
	@Test fun notificationPathNamesTheVisitor()
	{	assertEquals("current_notification/where/user_id=42&message_id=5", Endpoints.notification(42, 5))
		assertEquals("current_notification/where/user_id=-7", Endpoints.notification(-7))
	}

	@Test fun metadataPaths()
	{	assertEquals("conditions/columns/id,name/where/id:1,2", Endpoints.conditions(listOf(1, 2)))
		assertEquals("actions/columns/id,name,content_type,content_param,content_begin,content_end,libs_app,placeholders,placeholder_order/where/id:88", Endpoints.actions(listOf(88), true))
		assertEquals("actions/columns/id,name,content_type,content_param,content_begin,content_end,libs_app,placeholders/where/id:88", Endpoints.actions(listOf(88), false))
		assertEquals("placeholders/columns/id,name,html_id,units_count_max/where/id:635", Endpoints.placeholders(listOf(635)))
		assertEquals("ab_test/where/condition_id=273", Endpoints.verdict(273))
	}
}
