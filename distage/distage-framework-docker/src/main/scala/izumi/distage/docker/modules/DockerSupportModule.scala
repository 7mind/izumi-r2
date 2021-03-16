package izumi.distage.docker.modules

import distage.TagK
import izumi.distage.config.ConfigModuleDef
import izumi.distage.docker.{Docker, DockerClientWrapper}
import izumi.distage.model.definition.ModuleDef

class DockerSupportModule[F[_]: TagK] extends ModuleDef {
  make[DockerClientWrapper[F]].fromResource[DockerClientWrapper.Resource[F]]

  include(DockerSupportModule.config)
}

object DockerSupportModule {
  def apply[F[_]: TagK]: DockerSupportModule[F] = new DockerSupportModule[F]

  final val config = new ConfigModuleDef {
    makeConfigWithDefault[Docker.ClientConfig]("docker")(Docker.ClientConfig())
  }
}
