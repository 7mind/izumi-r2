package izumi.distage.model.reflection.universe.RuntimeDIUniverse

import izumi.distage.model.definition.ImplDef
import izumi.distage.model.exceptions.{FactoryProvidersCannotBeCombined, UnsafeCallArgsMismatched}
import izumi.distage.model.provisioning.proxies.ProxyDispatcher.ByNameWrapper
import izumi.fundamentals.platform.language.unused
import izumi.fundamentals.reflection.Tags.{Tag, TagK, WeakTag}
import izumi.fundamentals.reflection.macrortti.LightTypeTag

final class IdContractImpl[T] extends IdContract[T] {
  override def repr(value: T): String = value.toString
}

sealed trait Association {
  def symbol: SymbolInfo
  def key: DIKey.BasicKey
  def tpe: SafeType
  def name: String
  def isByName: Boolean

  def withKey(key: DIKey.BasicKey): Association
}

object Association {
  final case class Parameter(symbol: SymbolInfo, key: DIKey.BasicKey) extends Association {
    override final def name: String = symbol.name
    override final def tpe: SafeType = symbol.finalResultType
    override final def isByName: Boolean = symbol.isByName
    override final def withKey(key: DIKey.BasicKey): Association.Parameter = copy(key = key)

    final def wasGeneric: Boolean = symbol.wasGeneric
  }
}

sealed trait SymbolInfo {
  def name: String
  def finalResultType: SafeType

  def isByName: Boolean
  def wasGeneric: Boolean
}

object SymbolInfo {
  final case class Static(
                           name: String,
                           finalResultType: SafeType,
                           // annotations: List[u.Annotation],
                           isByName: Boolean,
                           wasGeneric: Boolean,
                         ) extends SymbolInfo
}

final case class SafeType private(
                                   tag: LightTypeTag,
                                   /*private[distage] val */cls: Class[_],
                                 ) {
  override final val hashCode: Int = tag.hashCode()
  override final def toString: String = tag.repr

  override final def equals(obj: Any): Boolean = {
    obj match {
      case that: SafeType =>
        tag =:= that.tag
      case _ =>
        false
    }
  }

  final def =:=(that: SafeType): Boolean = {
    tag =:= that.tag
  }

  final def <:<(that: SafeType): Boolean = {
    tag <:< that.tag
  }

  final def hasPreciseClass: Boolean = {
    tag.shortName == cls.getSimpleName
  }
}

object SafeType {
  def get[T: Tag]: SafeType = SafeType(Tag[T].tag, Tag[T].closestClass)
  def getK[K[_]: TagK]: SafeType = SafeType(TagK[K].tag, TagK[K].closestClass)
  def unsafeGetWeak[T](implicit weakTag: WeakTag[T]): SafeType = SafeType(weakTag.tag, weakTag.closestClass)
}

// dikey

sealed trait DIKey {
  def tpe: SafeType
}

object DIKey {
  def get[T: Tag]: DIKey.TypeKey = TypeKey(SafeType.get[T])

  sealed trait BasicKey extends DIKey {
    def withTpe(tpe: SafeType): DIKey.BasicKey
  }

  final case class TypeKey(tpe: SafeType) extends BasicKey {
    final def named[I: IdContract](id: I): IdKey[I] = IdKey(tpe, id)

    override final def withTpe(tpe: SafeType): DIKey.TypeKey = copy(tpe = tpe)
    override final def toString: String = s"{type.${tpe.toString}}"
  }

  final case class IdKey[I: IdContract](tpe: SafeType, id: I) extends BasicKey {
    final val idContract: IdContract[I] = implicitly

    override final def withTpe(tpe: SafeType): DIKey.IdKey[I] = copy(tpe = tpe)
    override final def toString: String = s"{type.${tpe.toString}@${idContract.repr(id)}}"
  }

  final case class ProxyElementKey(proxied: DIKey, tpe: SafeType) extends DIKey {
    override final def toString: String = s"{proxy.${proxied.toString}}"
  }

  final case class ResourceKey(key: DIKey, tpe: SafeType) extends DIKey {
    override final def toString: String = s"{resource.${key.toString}/$tpe}"
  }

  final case class EffectKey(key: DIKey, tpe: SafeType) extends DIKey {
    override final def toString: String = s"{effect.${key.toString}/$tpe}"
  }

  /**
    * @param set       Key of the parent Set. `set.tpe` must be of type `Set[T]`
    * @param reference Key of `this` individual element. `reference.tpe` must be a subtype of `T`
    */
  final case class SetElementKey(set: DIKey, reference: DIKey, disambiguator: Option[ImplDef]) extends DIKey {
    override final def tpe: SafeType = reference.tpe

    override final def toString: String = s"{set.$set/${reference.toString}#${disambiguator.fold("0")(_.hashCode().toString)}"
  }

  final case class MultiSetImplId(set: DIKey, impl: ImplDef)
  object MultiSetImplId {
    implicit object SetImplIdContract extends IdContract[MultiSetImplId] {
      override def repr(v: MultiSetImplId): String = s"set/${v.set}#${v.impl.hashCode()}"
    }
  }
}

trait IdContract[T] {
  def repr(v: T): String
}
object IdContract {
  implicit lazy val stringIdContract: IdContract[String] = new IdContractImpl[String]
  implicit def singletonStringIdContract[S <: String with Singleton]: IdContract[S] = new IdContractImpl[S]
}

//

trait DIFunction {
  def associations: Seq[Association.Parameter]
  def argTypes: Seq[SafeType]
  def diKeys: Seq[DIKey]

  def ret: SafeType

  def fun: Seq[Any] => Any
  def arity: Int

  def isGenerated: Boolean

  def unsafeApply(refs: TypedRef[_]*): Any = {
    val args = verifyArgs(refs)
    fun(args)
  }

  private[this] def verifyArgs(refs: Seq[TypedRef[_]]): Seq[Any] = {
    val countOk = refs.size == argTypes.size

    val typesOk = argTypes.zip(refs).forall {
      case (tpe, arg) =>
        arg.symbol <:< tpe
    }

    val (args, types) = refs.map { case TypedRef(v, t) => (v, t) }.unzip

    if (countOk && typesOk) {
      args
    } else {
      throw new UnsafeCallArgsMismatched(
        message =
          s"""Mismatched arguments for unsafe call:
             | ${if (!typesOk) "Wrong types!" else ""}
             | ${if (!countOk) s"Expected number of arguments ${argTypes.size}, but got ${refs.size}" else ""}
             |Expected types [${argTypes.mkString(",")}], got types [${types.mkString(",")}], values: (${args.mkString(",")})""".stripMargin,
        expected = argTypes,
        actual = types,
        actualValues = args,
      )
    }
  }
}

trait Provider extends DIFunction {
  def unsafeMap(newRet: SafeType, f: Any => _): Provider
  def unsafeZip(newRet: SafeType, that: Provider): Provider

  override final val diKeys: Seq[DIKey] = associations.map(_.key)
  override final val argTypes: Seq[SafeType] = associations.map(_.key.tpe)
  override final val arity: Int = argTypes.size

  private def eqField: AnyRef = if (isGenerated) ret else fun
  override final def equals(obj: Any): Boolean = {
    obj match {
      case that: Provider =>
        eqField == that.eqField
      case _ =>
        false
    }
  }
  override final def hashCode(): Int = eqField.hashCode()
  override final def toString: String = s"$fun(${argTypes.mkString(", ")}): $ret"
}

object Provider {

  final case class ProviderImpl[+A](
                                     associations: Seq[Association.Parameter],
                                     ret: SafeType,
                                     fun: Seq[Any] => Any,
                                     isGenerated: Boolean,
                                   ) extends Provider {

    override final def unsafeApply(refs: TypedRef[_]*): A =
      super.unsafeApply(refs: _*).asInstanceOf[A]

    override final def unsafeMap(newRet: SafeType, f: Any => _): ProviderImpl[_] =
      copy(ret = newRet, fun = xs => f.apply(fun(xs)))

    override final def unsafeZip(newRet: SafeType, that: Provider): Provider =
      ProviderImpl(
        associations ++ that.associations,
        newRet,
        { args0 =>
          val (args1, args2) = args0.splitAt(arity)
          fun(args1) -> that.fun(args2)
        },
        isGenerated
      )
  }

  trait FactoryProvider extends Provider {
    def factoryIndex: Map[Int, Wiring.FactoryFunction.FactoryMethod]
  }

  object FactoryProvider {
    final case class FactoryProviderImpl(provider: Provider, factoryIndex: Map[Int, Wiring.FactoryFunction.FactoryMethod], isGenerated: Boolean) extends FactoryProvider {
      override final def associations: Seq[Association.Parameter] = provider.associations
      override final def ret: SafeType = provider.ret
      override final def fun: Seq[Any] => Any = provider.fun

      override final def unsafeMap(newRet: SafeType, f: Any => _): FactoryProviderImpl =
        copy(provider = provider.unsafeMap(newRet, f))

      override final def unsafeZip(newRet: SafeType, that: Provider): FactoryProviderImpl = {
        that match {
          case that: FactoryProviderImpl =>
            throw new FactoryProvidersCannotBeCombined(s"Impossible operation: two factory providers cannot be zipped. this=$this that=$that", this, that)
          case _ =>
            copy(provider = provider.unsafeZip(newRet, that))
        }
      }
    }
  }
}

// wiring

sealed trait Wiring {
  def instanceType: SafeType
  def associations: Seq[Association]
  def replaceKeys(f: Association => DIKey.BasicKey): Wiring

  def requiredKeys: Set[DIKey] = associations.map(_.key).toSet
}

object Wiring {
  sealed trait PureWiring extends Wiring {
    override def replaceKeys(f: Association => DIKey.BasicKey): PureWiring
  }

  sealed trait SingletonWiring extends PureWiring {
    def instanceType: SafeType
    override def replaceKeys(f: Association => DIKey.BasicKey): SingletonWiring
  }
  object SingletonWiring {
    final case class Function(provider: Provider, associations: Seq[Association.Parameter]) extends SingletonWiring {
      override final def instanceType: SafeType = provider.ret

      override final def replaceKeys(f: Association => DIKey.BasicKey): Function = this.copy(associations = this.associations.map(a => a.withKey(f(a))))
    }
    final case class Instance(instanceType: SafeType, instance: Any) extends SingletonWiring {
      override final def associations: Seq[Association] = Seq.empty

      override final def replaceKeys(@unused f: Association => DIKey.BasicKey): Instance = this
    }
    final case class Reference(instanceType: SafeType, key: DIKey, weak: Boolean) extends SingletonWiring {
      override final def associations: Seq[Association] = Seq.empty

      override final val requiredKeys: Set[DIKey] = super.requiredKeys ++ Set(key)
      override final def replaceKeys(@unused f: Association => DIKey.BasicKey): Reference = this
    }
  }

  sealed trait MonadicWiring extends Wiring {
    def effectWiring: PureWiring
    def effectHKTypeCtor: SafeType
  }
  object MonadicWiring {
    final case class Effect(instanceType: SafeType, effectHKTypeCtor: SafeType, effectWiring: PureWiring) extends MonadicWiring {
      override final def associations: Seq[Association] = effectWiring.associations
      override final def requiredKeys: Set[DIKey] = effectWiring.requiredKeys

      override final def replaceKeys(f: Association => DIKey.BasicKey): Effect = copy(effectWiring = effectWiring.replaceKeys(f))
    }

    final case class Resource(instanceType: SafeType, effectHKTypeCtor: SafeType, effectWiring: PureWiring) extends MonadicWiring {
      override final def associations: Seq[Association] = effectWiring.associations
      override final def requiredKeys: Set[DIKey] = effectWiring.requiredKeys

      override final def replaceKeys(f: Association => DIKey.BasicKey): Resource = copy(effectWiring = effectWiring.replaceKeys(f))
    }
  }

  final case class FactoryFunction(
                                    provider: Provider,
                                    factoryIndex: Map[Int, FactoryFunction.FactoryMethod],
                                    factoryCtorParameters: Seq[Association.Parameter],
                                  ) extends PureWiring {
    private[this] final val factoryMethods = factoryIndex.values.toList

    override final lazy val associations: Seq[Association] = {
      val userSuppliedDeps = factoryMethods.flatMap(_.userSuppliedParameters).toSet

      val factorySuppliedDeps = factoryMethods.flatMap(_.productWiring.associations).filterNot(userSuppliedDeps contains _.key)
      factorySuppliedDeps ++ factoryCtorParameters
    }

    override final def replaceKeys(f: Association => DIKey.BasicKey): FactoryFunction =
      this.copy(
        factoryCtorParameters = this.factoryCtorParameters.map(a => a.withKey(f(a))),
        factoryIndex = this.factoryIndex.mapValues(m => m.copy(productWiring = m.productWiring.replaceKeys(f))).toMap // 2.13 compat
      )

    override final def instanceType: SafeType = provider.ret
  }

  object FactoryFunction {
    final case class FactoryMethod(factoryMethod: SymbolInfo, productWiring: Wiring.SingletonWiring.Function, userSuppliedParameters: Seq[DIKey])
  }

}

final case class TypedRef[+T](private val v: T, symbol: SafeType) {
  def value: T = v match {
    case d: ByNameWrapper => d.apply().asInstanceOf[T]
    case o => o
  }
}
object TypedRef {
  def apply[T: Tag](value: T): TypedRef[T] = TypedRef(value, SafeType.get[T])
  def byName[T: Tag](value: => T): TypedRef[T] = TypedRef((() => value).asInstanceOf[T], SafeType.get[T])
}