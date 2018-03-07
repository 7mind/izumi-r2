package com.github.pshirshov.izumi.distage.model.plan

import com.github.pshirshov.izumi.distage.model.definition.ContextDefinition

trait FinalPlan {
  def definition: ContextDefinition
  def steps: Seq[ExecutableOp]

  def flatMap(f: ExecutableOp => Seq[ExecutableOp]): FinalPlan =
    FinalPlanImmutableImpl(definition)(steps.flatMap(f))

  override def toString: String = {
    steps.map(_.format).mkString("\n")
  }
}



