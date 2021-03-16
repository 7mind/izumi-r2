package izumi.distage.docker

import com.github.dockerjava.api.command.DockerCmdExecFactory
import com.github.dockerjava.netty.NettyDockerCmdExecFactory
import izumi.distage.model.definition.Lifecycle
import izumi.distage.model.effect.QuasiIO

final class DockerCmdExecFactoryResource[F[_]](
  config: Docker.ClientConfig
)(implicit
  F: QuasiIO[F]
) extends Lifecycle.FromAutoCloseable[F, DockerCmdExecFactory](
    F.maybeSuspend {
      new NettyDockerCmdExecFactory()
        .withReadTimeout(config.readTimeoutMs)
        .withConnectTimeout(config.connectTimeoutMs)
    }
  )
