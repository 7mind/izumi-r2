package com.github.pshirshov.izumi.idealingua.model.il.ast.raw

import com.github.pshirshov.izumi.idealingua.model.common.TypeId.BuzzerId

final case class Buzzer(id: BuzzerId, events: List[RawMethod], meta: RawNodeMeta) extends RawWithMeta {
  override def updateMeta(f: RawNodeMeta => RawNodeMeta): RawWithMeta = this.copy(meta = f(meta))
}




