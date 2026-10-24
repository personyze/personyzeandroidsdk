package com.personyze.android.internal.core

/**	One tracker command: `["Product Viewed", "A1000"]`. Strings, as the v1 SDK sent them; the
	server coerces (`Number(command[1])`), and the guide documents both forms. A profile value may
	be null.
 **/
internal typealias Command = List<String?>

/**	Builds commands with the names and casing the v1 SDK used (the server matches them
	case-insensitively), and refuses what the server would refuse - one bad command answers 400 for
	the whole request, taking every other event in it along.
 **/
internal object Commands
{	const val NAVIGATE = "Navigate"
	const val ACTION_STATUS = "Action Status"
	const val USER_PROFILE = "User profile"

	/**	A request body may not exceed this (the v1 limit, `POST_LIMIT`); one command must leave room
		for the rest of the body.
	 **/
	const val MAX_BODY = 50_000
	const val MAX_COMMAND = 45_000

	val STATUSES = setOf("executed", "target", "close", "product", "article", "dont-show", "rules-mismatch", "error")

	fun navigate(document: String): Command
	{	require(document.isNotBlank()) { "Personyze: navigate() needs a document name" }
		return listOf(NAVIGATE, Endpoints.documentUrn(document))
	}

	fun profile(fields: Map<String, String?>): Command
	{	require(fields.isNotEmpty()) { "Personyze: setProfile() needs at least one field" }
		val cmd = ArrayList<String?>(1 + fields.size*2)
		cmd.add(USER_PROFILE)
		for ((field, value) in fields)
		{	require(field.isNotBlank()) { "Personyze: a profile field name is empty" }
			cmd.add(field)
			cmd.add(value)
		}
		return checked(cmd)
	}

	/**	`["Product Viewed", id, "action_id", n, "quantity", n]`; the pairs only when given. **/
	fun product(name: String, productId: String, actionId: Int?, quantity: Int?): Command
	{	require(productId.isNotBlank()) { "Personyze: $name needs a product id (No Product ID specified)" }
		val cmd = arrayListOf<String?>(name, productId)
		if (actionId != null)
		{	require(actionId > 0) { "Personyze: $name: action_id must be positive" }
			cmd.add("action_id")
			cmd.add(actionId.toString())
		}
		if (quantity != null)
		{	require(quantity > 0) { "Personyze: $name: quantity must be positive" }
			cmd.add("quantity")
			cmd.add(quantity.toString())
		}
		return checked(cmd)
	}

	fun article(name: String, articleId: String, actionId: Int?): Command
	{	require(articleId.isNotBlank()) { "Personyze: $name needs an article id (No Article ID specified)" }
		val cmd = arrayListOf<String?>(name, articleId)
		if (actionId != null)
		{	require(actionId > 0) { "Personyze: $name: action_id must be positive" }
			cmd.add("action_id")
			cmd.add(actionId.toString())
		}
		return checked(cmd)
	}

	/**	The server requires both (`No Promotion ID specified`, `No action_id specified`). **/
	fun promotion(name: String, promotionId: String, actionId: Int): Command
	{	require(promotionId.isNotBlank()) { "Personyze: $name needs a promotion id" }
		require(actionId > 0) { "Personyze: $name needs the promotion action's id" }
		return checked(listOf(name, promotionId.trim(), "action_id", actionId.toString()))
	}

	fun coupon(code: String): Command
	{	require(code.isNotBlank()) { "Personyze: couponUsed() needs a code" }
		return checked(listOf("coupon used", code.trim()))
	}

	fun bare(name: String): Command = listOf(name)

	/**	`["Action Status", "<id>", status, arg]`, four elements as v1 sent them. **/
	fun actionStatus(actionId: Int, status: String, arg: String = ""): Command
	{	require(actionId > 0) { "Personyze: invalid action id $actionId" }
		require(status in STATUSES) { "Personyze: unknown action status '$status'" }
		return checked(listOf(ACTION_STATUS, actionId.toString(), status, arg))
	}

	fun raw(command: List<String>): Command
	{	require(command.isNotEmpty() && command[0].isNotBlank()) { "Personyze: track() needs a command name" }
		return checked(command)
	}

	fun isNavigate(cmd: Command): Boolean = cmd.size >= 2 && cmd[0].equals(NAVIGATE, ignoreCase = true)

	fun isActionStatus(cmd: Command): Boolean = cmd.size >= 3 && cmd[0].equals(ACTION_STATUS, ignoreCase = true)

	/**	The JSON length of a command, as it will appear in the body (close enough to pack batches). **/
	fun jsonLength(cmd: Command): Int = cmd.sumOf { (it?.length ?: 4) + 3 + it.orEmpty().count { c -> c == '"' || c == '\\' || c < ' ' }*5 } + 2

	private fun checked(cmd: Command): Command
	{	require(jsonLength(cmd) <= MAX_COMMAND) { "Personyze: a ${cmd[0]} command over ${MAX_COMMAND} characters cannot be sent" }
		return cmd
	}
}
