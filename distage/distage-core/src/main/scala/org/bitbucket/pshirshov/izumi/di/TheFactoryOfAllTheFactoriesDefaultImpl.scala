package org.bitbucket.pshirshov.izumi.di

import org.bitbucket.pshirshov.izumi.di.model.DIKey
import org.bitbucket.pshirshov.izumi.di.model.plan.FinalPlan
import org.bitbucket.pshirshov.izumi.di.provisioning.Provisioner

class TheFactoryOfAllTheFactoriesDefaultImpl(
                                              provisioner: Provisioner
                                            ) extends TheFactoryOfAllTheFactories {
  override def produce(finalPlan: FinalPlan, parentContext: Locator): Locator = {
    val dependencyMap = provisioner.provision(finalPlan, parentContext)

    new Locator {
      override val parent: Option[Locator] = Option(parentContext)

      override protected def unsafeLookup(key: DIKey): Option[Any] =
        dependencyMap.get(key)

      override def enumerate: Stream[IdentifiedRef] = dependencyMap.enumerate


      override val plan: FinalPlan = finalPlan
    }
  }
}
