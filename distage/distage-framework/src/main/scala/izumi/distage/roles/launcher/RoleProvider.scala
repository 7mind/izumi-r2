package izumi.distage.roles.launcher

import distage.{Id, _}
import izumi.distage.model.definition.Binding
import izumi.distage.model.definition.Binding.ImplBinding
import izumi.distage.model.reflection.SafeType
import izumi.distage.roles.DebugProperties
import izumi.distage.roles.model.definition.RoleTag
import izumi.distage.roles.model.exceptions.DIAppBootstrapException
import izumi.distage.roles.model.meta.{RoleBinding, RolesInfo}
import izumi.distage.roles.model.{AbstractRole, RoleDescriptor}
import izumi.fundamentals.platform.cli.model.raw.RawAppArgs
import izumi.fundamentals.platform.jvm.IzJvm
import izumi.fundamentals.platform.language.open
import izumi.fundamentals.platform.strings.IzString.toRichIterable
import izumi.fundamentals.reflection.TypeUtil
import izumi.logstage.api.IzLogger

trait RoleProvider {
  def loadRoles[F[_]: TagK](appModule: ModuleBase): RolesInfo
}

object RoleProvider {
  private[this] final val syspropRolesReflection = DebugProperties.`izumi.distage.roles.reflection`.boolValue(true)

  @open class Impl(
    logger: IzLogger @Id("early"),
    reflectionEnabled: Boolean @Id("distage.roles.reflection"),
    parameters: RawAppArgs,
  ) extends RoleProvider {

    protected lazy val isReflectionEnabled: Boolean = {
      reflectionEnabled && syspropRolesReflection && !IzJvm.isGraalNativeImage()
    }

    def loadRoles[F[_]: TagK](appModule: ModuleBase): RolesInfo = {
      val rolesInfo = getInfo(
        bindings = appModule.bindings,
        requiredRoles = parameters.roles.iterator.map(_.role).toSet,
        roleType = SafeType.get[AbstractRole[F]],
      )
      logger.info(s"Available ${rolesInfo.render() -> "roles"}")
      rolesInfo
    }

    protected def getInfo(bindings: Set[Binding], requiredRoles: Set[String], roleType: SafeType): RolesInfo = {
      val availableRoleBindings = instantiateRoleBindings(bindings, roleType)
      val requiredRoleBindings = availableRoleBindings.filter(isRoleEnabled(requiredRoles))

      val roleNames = availableRoleBindings.map(_.descriptor.id)
      val requiredRoleNames = requiredRoleBindings.iterator.map(_.descriptor.id).toSet
      val unrequiredRoleNames = roleNames.diff(requiredRoleNames)

      val rolesInfo = RolesInfo(
        requiredComponents = requiredRoleBindings.iterator.map(_.binding.key).toSet,
        requiredRoleBindings = requiredRoleBindings,
        requiredRoleNames = requiredRoleNames,
        availableRoleNames = roleNames,
        availableRoleBindings = availableRoleBindings,
        unrequiredRoleNames = unrequiredRoleNames,
      )

      val missing = requiredRoles.diff(availableRoleBindings.map(_.descriptor.id))
      if (missing.nonEmpty) {
        logger.crit(s"Missing ${missing.niceList() -> "roles"}")
        throw new DIAppBootstrapException(s"Unknown roles:${missing.niceList("    ")}")
      }
      if (requiredRoleBindings.isEmpty) {
        throw new DIAppBootstrapException(s"""No roles selected to launch, please select one of the following roles using syntax `:${'$'}roleName` on the command-line.
                                             |
                                             |Available roles:${rolesInfo.render()}""".stripMargin)
      }

      rolesInfo
    }

    protected def instantiateRoleBindings(bindings: Set[Binding], roleType: SafeType): Set[RoleBinding] = {
      bindings.collect {
        case s: ImplBinding if s.tags.exists(_.isInstanceOf[RoleTag]) && checkRoleType(s.implementation.implType, roleType, log = true) =>
          mkRoleBinding(s, s.tags.collectFirst { case RoleTag(roleDescriptor) => roleDescriptor }.get)

        case s: ImplBinding if isReflectionEnabled && s.implementation.implType <:< roleType =>
          reflectCompanionBinding(s)
      }
    }

    protected def isRoleEnabled(requiredRoles: Set[String])(b: RoleBinding): Boolean = {
      requiredRoles.contains(b.descriptor.id) || requiredRoles.contains(b.tpe.tag.shortName.toLowerCase)
    }

    protected def checkRoleType(implType: SafeType, roleType: SafeType, log: Boolean): Boolean = {
      val res = implType <:< roleType
      if (!res) logger.warn(s"Found role binding with incompatible effect type $implType (expected to be a subtype of $roleType)")
      res
    }

    protected def mkRoleBinding(roleBinding: ImplBinding, roleDescriptor: RoleDescriptor): RoleBinding = {
      val runtimeClass = roleBinding.key.tpe.cls
      val implType = roleBinding.implementation.implType
      RoleBinding(roleBinding, runtimeClass, implType, roleDescriptor)
    }

    protected def reflectCompanionBinding(roleBinding: ImplBinding): RoleBinding = {
      if (isReflectionEnabled) {
        reflectCompanionDescriptor(roleBinding.key.tpe) match {
          case Some(descriptor) =>
            logger.warn(
              s"""${roleBinding.key -> "role"} defined ${roleBinding.origin -> "at"}: using deprecated reflective look-up of `RoleDescriptor` companion object.
                 |Please use `RoleModuleDef` & `makeRole` to create a role binding explicitly, instead.
                 |
                 |Reflective lookup of `RoleDescriptor` will be removed in a future version.""".stripMargin
            )
            mkRoleBinding(roleBinding, descriptor)

          case None =>
            logger.crit(s"${roleBinding.key -> "role"} defined ${roleBinding.origin -> "at"} has no companion object inherited from RoleDescriptor")
            throw new DIAppBootstrapException(s"role=${roleBinding.key} defined at=${roleBinding.origin} has no companion object inherited from RoleDescriptor")
        }
      } else {
        logger.crit(s"${roleBinding.key -> "role"} defined ${roleBinding.origin -> "at"} has no RoleDescriptor, companion reflection is disabled")
        throw new DIAppBootstrapException(s"role=${roleBinding.key} defined at=${roleBinding.origin} has no RoleDescriptor, companion reflection is disabled")
      }
    }

    protected def reflectCompanionDescriptor(role: SafeType): Option[RoleDescriptor] = {
      val roleClassName = role.cls.getName
      try {
        Some(TypeUtil.instantiateObject[RoleDescriptor](Class.forName(s"$roleClassName$$")))
      } catch {
        case t: Throwable =>
          logger.crit(s"""Failed to reflect RoleDescriptor companion object for $role: $t
                         |Please create a companion object extending `izumi.distage.roles.model.RoleDescriptor` for `$roleClassName`
                         |""".stripMargin)
          None
      }
    }

  }

}
