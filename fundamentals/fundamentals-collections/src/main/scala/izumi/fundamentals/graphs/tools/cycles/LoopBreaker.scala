package izumi.fundamentals.graphs.tools.cycles

import izumi.fundamentals.graphs.GraphTraversalError.UnrecoverableLoops
import izumi.fundamentals.graphs.struct.IncidenceMatrix

trait LoopBreaker[N] {
  def breakLoops(withLoops: IncidenceMatrix[N]): Either[UnrecoverableLoops[N], IncidenceMatrix[N]]
}

object LoopBreaker {
  def terminating[N]: LoopBreaker[N] = _ => Left(UnrecoverableLoops())
}
