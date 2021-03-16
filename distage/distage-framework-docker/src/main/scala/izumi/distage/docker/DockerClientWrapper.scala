package izumi.distage.docker

import java.util.UUID
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.model.Container
import com.github.dockerjava.core.{DefaultDockerClientConfig, DockerClientBuilder, DockerClientConfig}
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient
import izumi.distage.docker.Docker.{ClientConfig, ContainerId}
import izumi.distage.docker.DockerClientWrapper.ContainerDestroyMeta
import izumi.distage.framework.model.IntegrationCheck
import izumi.distage.model.definition.Lifecycle
import izumi.distage.model.effect.QuasiIO
import izumi.distage.model.effect.QuasiIO.syntax._
import izumi.functional.Value
import izumi.fundamentals.platform.integration.ResourceCheck
import izumi.fundamentals.platform.language.Quirks.Discarder
import izumi.logstage.api.IzLogger

import scala.jdk.CollectionConverters._

class DockerClientWrapper[F[_]](
  val rawClient: DockerClient,
  val rawClientConfig: DockerClientConfig,
  val clientConfig: ClientConfig,
  val labelsBase: Map[String, String],
  val labelsJvm: Map[String, String],
  val labelsUnique: Map[String, String],
  logger: IzLogger,
)(implicit
  F: QuasiIO[F]
) {
  def labels: Map[String, String] = labelsBase ++ labelsJvm ++ labelsUnique

  def destroyContainer(containerId: ContainerId, context: ContainerDestroyMeta): F[Unit] = {
    F.definitelyRecover {
      F.maybeSuspend {
        logger.info(s"Going to destroy $containerId ($context)...")

        try {
          rawClient
            .stopContainerCmd(containerId.name)
            .exec()
        } finally {
          rawClient
            .removeContainerCmd(containerId.name)
            .withForce(true)
            .exec()
            .discard()
        }

        logger.info(s"Destroyed $containerId ($context)")
      }
    } {
      failure => F.maybeSuspend(logger.warn(s"Got failure during container destroy $failure"))
    }
  }
}

object DockerClientWrapper {
  sealed trait ContainerDestroyMeta
  object ContainerDestroyMeta {
    final case class ParameterizedContainer[T](container: DockerContainer[T]) extends ContainerDestroyMeta {
      override def toString: String = container.toString
    }
    final case class RawContainer(container: Container) extends ContainerDestroyMeta {
      override def toString: String = container.toString
    }
  }

  private[this] val jvmRun: String = UUID.randomUUID().toString

  class Resource[F[_]](
    logger: IzLogger,
    clientConfig: ClientConfig,
  )(implicit
    F: QuasiIO[F]
  ) extends Lifecycle.Basic[F, DockerClientWrapper[F]]
    with IntegrationCheck[F] {

    private[this] lazy val rawClientConfig = {
      Value(DefaultDockerClientConfig.createDefaultConfigBuilder())
        .mut(clientConfig.daemon)((b, c) => b.withDockerHost(c.host))
        .mut(clientConfig.daemon.filter(_.tlsVerify))((b, c) => b.withDockerTlsVerify(true).withDockerCertPath(c.certPath).withDockerConfig(c.config))
        .mut(clientConfig.registry.filter(_ => clientConfig.useRegistry))(
          (b, c) => b.withRegistryUrl(c.url).withRegistryUsername(c.username).withRegistryPassword(c.password).withRegistryEmail(c.email)
        )
        .get.build()
    }

    private[this] lazy val client = DockerClientBuilder
      .getInstance(rawClientConfig).withDockerHttpClient(
        new ZerodepDockerHttpClient.Builder()
          .dockerHost(rawClientConfig.getDockerHost)
          .sslConfig(rawClientConfig.getSSLConfig)
          .build()
      )
      .build

    override def resourcesAvailable(): F[ResourceCheck] = F.maybeSuspend {
      try {
        client.infoCmd().exec()
        ResourceCheck.Success()
      } catch {
        case t: Throwable =>
          ResourceCheck.ResourceUnavailable("Docker daemon is unavailable", Some(t))
      }
    }

    override def acquire: F[DockerClientWrapper[F]] = {
      F.maybeSuspend {
        new DockerClientWrapper[F](
          rawClient = client,
          rawClientConfig = rawClientConfig,
          labelsBase = Map(DockerConst.Labels.containerTypeLabel -> "testkit"),
          labelsJvm = Map(DockerConst.Labels.jvmRunId -> jvmRun),
          labelsUnique = Map(DockerConst.Labels.distageRunId -> UUID.randomUUID().toString),
          logger = logger,
          clientConfig = clientConfig,
        )
      }
    }

    override def release(resource: DockerClientWrapper[F]): F[Unit] = {
      for {
        containers <- F.maybeSuspend {
          resource.rawClient
            .listContainersCmd()
            .withStatusFilter(List(DockerConst.State.exited, DockerConst.State.running).asJava)
            .withLabelFilter(resource.labels.asJava)
            .exec()
        }
        // destroy all containers that should not be reused, or was exited (to not to accumulate containers that could be pruned)
        containersToDestroy = containers.asScala.filter {
          c =>
            import izumi.fundamentals.platform.strings.IzString._
            Option(c.getLabels.get(DockerConst.Labels.reuseLabel)).forall(label => label.asBoolean().contains(false)) || c.getState == DockerConst.State.exited
        }
        _ <- F.traverse_(containersToDestroy) {
          c: Container =>
            val id = ContainerId(c.getId)
            F.definitelyRecover(resource.destroyContainer(id, ContainerDestroyMeta.RawContainer(c))) {
              error =>
                F.maybeSuspend(logger.warn(s"Failed to destroy container $id: $error"))
            }

        }
        _ <- F.maybeSuspend(resource.rawClient.close())
      } yield ()
    }
  }

}
