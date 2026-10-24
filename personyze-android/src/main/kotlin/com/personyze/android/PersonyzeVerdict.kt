package com.personyze.android

/**	Who is winning an A/B test: `GET /rest/ab_test` for one campaign. Read-only; no visitor or
	session is involved.
 **/
public class PersonyzeVerdict internal constructor
(	public val conditionId: Int,
	public val conditionName: String,
	/**	True only when every configured goal passed its thresholds and they all name the same arm. **/
	public val hasWinner: Boolean,
	public val winnerActiongroupId: Int?,
	/**	Why there is no winner yet, in words; empty once there is one. **/
	public val message: String,
	public val controlGroupRotation: Double,
	public val maxDurationReached: Boolean,
	public val goals: List<Goal>,
	/**	The response verbatim. **/
	public val rawJson: String
)
{	public class Goal internal constructor
	(	public val abGoal: String,
		public val title: String,
		/**	True for bounce rate: the best arm is the lowest. **/
		public val lowerIsBetter: Boolean,
		public val best: Arm?,
		public val comparedTo: Arm?,
		public val significancePrc: Double?,
		/**	Percentage points better than [comparedTo], not a relative percentage. **/
		public val improvementPrc: Double?,
		public val minSignificancePrc: Double?,
		public val minImprovementPrc: Double?,
		public val reached: Boolean,
		public val summary: String,
		public val groups: List<Group>
	)
	{	override fun toString(): String = "Goal(abGoal=$abGoal, reached=$reached, summary=$summary)"
	}

	public class Arm internal constructor(public val actiongroupId: Int, public val name: String, public val rate: Double?)
	{	override fun toString(): String = "Arm(actiongroupId=$actiongroupId, name=$name, rate=$rate)"
	}

	public class Group internal constructor
	(	public val actiongroupId: Int,
		public val name: String,
		public val value: Double?,
		public val denom: Double?,
		public val rate: Double?
	)
	{	override fun toString(): String = "Group(actiongroupId=$actiongroupId, name=$name, rate=$rate)"
	}

	override fun toString(): String = "PersonyzeVerdict(conditionId=$conditionId, hasWinner=$hasWinner, winnerActiongroupId=$winnerActiongroupId)"
}
