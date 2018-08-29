package com.github.pshirshov.izumi.distage.roles.launcher

import com.github.pshirshov.izumi.distage.app
import com.github.pshirshov.izumi.distage.app.AppFailureHandler
import com.github.pshirshov.izumi.distage.model.Locator
import com.github.pshirshov.izumi.distage.plugins.load.PluginLoaderDefaultImpl
import com.github.pshirshov.izumi.distage.plugins.load.PluginLoaderDefaultImpl.PluginConfig
import com.github.pshirshov.izumi.distage.roles.impl.{ScoptLauncherArgs, ScoptRoleApp}
import com.github.pshirshov.izumi.distage.roles.launcher.test.TestService
import com.github.pshirshov.izumi.distage.roles.roles.RoleService
import com.github.pshirshov.izumi.fundamentals.reflection.SourcePackageMaterializer._
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpec

class RoleAppTest extends WordSpec {

  def withProperties(properties: (String, String)*)(f: => Unit): Unit = {
    try {
      properties.foreach {
        case (k, v) =>
          System.setProperty(k, v)
      }
      ConfigFactory.invalidateCaches()
      f
    } finally {
      properties.foreach {
        case (k, _) =>
        System.clearProperty(k)
      }
      ConfigFactory.invalidateCaches()
    }
  }

  "Role Launcher" should {
    "properly discover services to start" in withProperties("testservice.systemPropInt" -> "265"
      , "testservice.systemPropList.0" -> "111"
      , "testservice.systemPropList.1" -> "222"
    ) {
      new RoleApp with ScoptRoleApp {
        override def handler: AppFailureHandler = AppFailureHandler.NullHandler

        override final val using = Seq.empty

        override val pluginConfig: PluginLoaderDefaultImpl.PluginConfig = PluginConfig(
          debug = false
          , packagesEnabled = Seq(s"$thisPkg.test")
          , packagesDisabled = Seq.empty
        )

        override protected def start(context: Locator, bootstrapContext: app.BootstrapContext[ScoptLauncherArgs]): Unit = {
          val services = context.instances.map(_.value).collect({ case t: RoleService => t }).toSet
          assert(services.size == 1)
          assert(services.exists(_.isInstanceOf[TestService]))

          val service = services.head.asInstanceOf[TestService]
          val conf = service.conf
          assert(conf.intval == 123)
          assert(conf.strval == "xxx")
          assert(conf.overridenInt == 111)
          assert(conf.systemPropInt == 265)
          assert(conf.systemPropList == List(111, 222))
          assert(service.dummies.isEmpty)

          super.start(context, bootstrapContext)
        }
      }.main(Array("testservice"))
    }
  }

}
