package com.petertroll.quiethome.domain

data class CombinedLaunchDecision(val block: BlockDecision, val launch: LaunchDecision)

/** Both decisions are derived synchronously from one immutable rule snapshot. */
class CombinedLaunchPolicy(private val safePackages: Set<String>) {
    fun evaluate(packageName: String, rule: AppRule?, context: EvaluationContext): CombinedLaunchDecision =
        CombinedLaunchDecision(
            block = BlockPolicy(safePackages).evaluate(packageName, rule, context),
            launch = LaunchPolicy(safePackages).decide(packageName, rule)
        )
}
