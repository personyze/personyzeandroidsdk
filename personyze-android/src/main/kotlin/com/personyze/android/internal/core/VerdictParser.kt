package com.personyze.android.internal.core

import com.personyze.android.PersonyzeVerdict
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**	`GET /rest/ab_test/where/condition_id=N` (the same shape the Node port types as `AbVerdict`). **/
internal object VerdictParser
{	fun parse(text: String): PersonyzeVerdict?
	{	val obj = try
		{	Wire.json.parseToJsonElement(text) as? JsonObject
		}
		catch (e: Exception)
		{	null
		} ?: return null
		return PersonyzeVerdict(
			conditionId = Wire.int(obj["condition_id"]) ?: return null,
			conditionName = Wire.string(obj["condition_name"]),
			hasWinner = Wire.bool(obj["has_winner"]),
			winnerActiongroupId = Wire.int(obj["winner_actiongroup_id"]),
			message = Wire.string(obj["message"]),
			controlGroupRotation = Wire.double(obj["control_group_rotation"]) ?: 0.0,
			maxDurationReached = Wire.bool(obj["max_duration_reached"]),
			goals = (obj["goals"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().map { g ->
				PersonyzeVerdict.Goal(
					abGoal = Wire.string(g["ab_goal"]),
					title = Wire.string(g["title"]),
					lowerIsBetter = Wire.bool(g["lower_is_better"]),
					best = arm(g["best"] as? JsonObject),
					comparedTo = arm(g["compared_to"] as? JsonObject),
					significancePrc = Wire.double(g["significance_prc"]),
					improvementPrc = Wire.double(g["improvement_prc"]),
					minSignificancePrc = Wire.double(g["min_significance_prc"]),
					minImprovementPrc = Wire.double(g["min_improvement_prc"]),
					reached = Wire.bool(g["reached"]),
					summary = Wire.string(g["summary"]),
					groups = (g["groups"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().map { r ->
						PersonyzeVerdict.Group(Wire.int(r["actiongroup_id"]) ?: 0, Wire.string(r["name"]), Wire.double(r["value"]), Wire.double(r["denom"]), Wire.double(r["rate"]))
					}
				)
			},
			rawJson = text
		)
	}

	private fun arm(o: JsonObject?): PersonyzeVerdict.Arm? =
		o?.let { PersonyzeVerdict.Arm(Wire.int(it["actiongroup_id"]) ?: 0, Wire.string(it["name"]), Wire.double(it["rate"])) }
}
