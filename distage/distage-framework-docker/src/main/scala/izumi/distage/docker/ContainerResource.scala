package izumi.distage.docker

import java.util.concurrent.{TimeUnit, TimeoutException}

import com.github.dockerjava.api.command.InspectContainerResponse
import com.github.dockerjava.api.model._
import izumi.distage.docker.ContainerResource.PortDecl
import izumi.distage.docker.Docker._
import izumi.distage.docker.DockerClientWrapper.ContainerDestroyMeta
import izumi.distage.docker.healthcheck.ContainerHealthCheck.HealthCheckResult.GoodHealthcheck
import izumi.distage.docker.healthcheck.ContainerHealthCheck.{HealthCheckResult, VerifiedContainerConnectivity}
import izumi.distage.framework.model.exceptions.IntegrationCheckException
import izumi.distage.model.definition.Lifecycle
import izumi.distage.model.effect.QuasiIO.syntax._
import izumi.distage.model.effect.{QuasiAsync, QuasiIO}
import izumi.functional.Value
import izumi.fundamentals.collections.nonempty.NonEmptyList
import izumi.fundamentals.platform.exceptions.IzThrowable._
import izumi.fundamentals.platform.integration.ResourceCheck
import izumi.fundamentals.platform.network.IzSockets
import izumi.fundamentals.platform.strings.IzString._
import izumi.logstage.api.IzLogger

import scala.annotation.nowarn
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

case class ContainerResource[F[_], T](
  config: Docker.ContainerConfig[T],
  client: DockerClientWrapper[F],
  logger: IzLogger,
)(implicit
  val F: QuasiIO[F],
  val P: QuasiAsync[F],
) extends Lifecycle.Basic[F, DockerContainer[T]] {

  private[this] val rawClient = client.rawClient

  private[this] def toExposedPort(port: DockerPort, number: Int): ExposedPort = {
    port match {
      case _: DockerPort.TCPBase => ExposedPort.tcp(number)
      case _: DockerPort.UDPBase => ExposedPort.udp(number)
    }
  }

  override def acquire: F[DockerContainer[T]] = F.suspendF {
    val ports = config.ports.map {
      containerPort =>
        val local = IzSockets.temporaryLocalPort()
        val exposedPort = containerPort match {
          case containerPort: DockerPort.Static => toExposedPort(containerPort, containerPort.number)
          case containerPort: DockerPort.Dynamic => toExposedPort(containerPort, local)
        }
        val binding = new PortBinding(Ports.Binding.bindPort(local), exposedPort)
        val labels = Map(
          containerPort.portLabel("defined") -> true.toString,
          containerPort.portLabel() -> local.toString,
        )
        PortDecl(containerPort, local, binding, labels)
    }

    if (Docker.shouldReuse(config.reuse, client.clientConfig.globalReuse)) {
      runReused(ports)
    } else {
      doRun(ports)
    }
  }

  override def release(container: DockerContainer[T]): F[Unit] = {
    if (Docker.shouldKill(container.containerConfig.reuse, container.clientConfig.globalReuse)) {
      client.destroyContainer(container.id, ContainerDestroyMeta.ParameterizedContainer(container))
    } else {
      F.maybeSuspend(logger.info(s"Will not destroy: $container"))
    }
  }

  def await(container: DockerContainer[T], attempt: Int): F[DockerContainer[T]] = {
    F.maybeSuspend {
      logger.debug(s"Awaiting until alive: $container...")
      try {
        val status = rawClient.inspectContainerCmd(container.id.name).exec()
        if (status.getState.getExitCodeLong == 0L && config.ports.isEmpty) {
          logger.debug(s"Container has exited but that was a singleshot container $container...")
          Right(HealthCheckResult.Available)
        } else if (status.getState.getRunning) {
          logger.debug(s"Trying healthcheck on running $container...")
          Right(config.healthCheck.check(logger, container))
        } else {
          Left(new RuntimeException(s"$container exited, status: ${status.getState}"))
        }
      } catch {
        case t: Throwable =>
          Left(t)
      }
    }.flatMap {
        case Right(HealthCheckResult.Available) =>
          F.maybeSuspend {
            logger.info(s"Continuing without port checks: $container")
            container
          }

        case Right(status: HealthCheckResult.AvailableOnPorts) if status.allTCPPortsAccessible =>
          F.maybeSuspend {
            val out = container.copy(availablePorts = VerifiedContainerConnectivity.HasAvailablePorts(status.availablePorts))
            logger.info(s"Looks good: ${out -> "container"}")
            out
          }

        case Right(last) =>
          val maxAttempts = config.healthCheckMaxAttempts
          val next = attempt + 1
          if (maxAttempts >= next) {
            logger.debug(s"Health check uncertain, retrying $next/$maxAttempts on $container...")
            P.sleep(config.healthCheckInterval).flatMap(_ => await(container, next))
          } else {
            last match {
              case HealthCheckResult.Unavailable =>
                F.fail(new TimeoutException(s"Health checks failed after $maxAttempts attempts, no diagnostics available: $container"))

              case HealthCheckResult.UnavailableWithMeta(unavailablePorts, unverifiedPorts) =>
                val sb = new StringBuilder()
                sb.append(s"Health checks failed after $maxAttempts attempts: $container\n")
                if (unverifiedPorts.nonEmpty) {
                  sb.append(s"Unchecked ports:\n")
                  sb.append(unverifiedPorts.niceList())
                }

                if (unavailablePorts.unavailablePorts.nonEmpty) {
                  val errored = unavailablePorts.unavailablePorts
                    .flatMap {
                      case (port, attempts) =>
                        attempts.map {
                          case (tested, cause) =>
                            (port, tested, cause)
                        }
                    }.map {
                      case (port, tested, None) =>
                        s"- $port with candidate $tested: no diagnostics"
                      case (port, tested, Some(cause)) =>
                        s"- $port with candidate $tested:\n${cause.stackTrace.shift(4)}"
                    }

                  sb.append(s"Unchecked ports:\n")
                  sb.append(errored.niceList())
                }

                F.fail(new TimeoutException(sb.toString()))

              case impossible: GoodHealthcheck =>
                F.fail(new TimeoutException(s"BUG: good healthcheck $impossible while health checks failed after $maxAttempts attempts: $container"))
            }
          }

        case Left(t) =>
          F.fail(new RuntimeException(s"$container failed due to exception: ${t.stackTrace}", t))
      }
  }

  private[this] def runReused(ports: Seq[PortDecl]): F[DockerContainer[T]] = {
    logger.info(s"About to start or find container ${config.image}, ${config.pullTimeout -> "max lock retries"}...")
    FileLockMutex.withLocalMutex(logger)(
      s"distage-container-resource-${config.image}:${config.ports.mkString(";")}".replaceAll("[:/]", "_"),
      waitFor = 200.millis,
      maxAttempts = config.pullTimeout.toSeconds.toInt * 5,
    ) {
      F.suspendF {
        val containers = {
          /*
           * We will filter out containers by "running" status if container exposes any ports to be mapped
           * and by "exited" status also if there are no exposed ports.
           * We don't need to consider "exited" containers with exposed ports, because they will always fail port check.
           *
           * Filtering containers like this allows us to reuse "oneshot" containers that were started and exited eventually.
           * When reusing containers we usually assume that a container runs as a daemon, but if a container exited successfully
           * we can't understand whether that container's run belonged to one of the previous test runs, or to the current one.
           * So containers that exit will be reused only in the scope of the current test run.
           */
          val exitedOpt = if (ports.isEmpty) List(DockerConst.State.exited) else Nil
          val statusFilter = DockerConst.State.running :: exitedOpt
          // FIXME: temporary hack to allow missing containers to skip tests (happens when both DockerWrapper & integration check that depends on Docker.Container are memoized)
          try {
            rawClient
              .listContainersCmd()
              .withAncestorFilter(List(config.image).asJava)
              .withStatusFilter(statusFilter.asJava)
              .exec().asScala.toList
              .sortBy(_.getId)
          } catch {
            case c: Throwable =>
              throw new IntegrationCheckException(NonEmptyList(ResourceCheck.ResourceUnavailable(c.getMessage, Some(c))))
          }
        }
        val candidate = {
          val portSet = ports.map(_.port).toSet
          val candidates = containers.flatMap {
            c =>
              val id = c.getId
              val cInspection = rawClient.inspectContainerCmd(id).exec()
              val cNetworks = cInspection.getNetworkSettings.getNetworks.asScala.keys.toList
              val missingNetworks = config.networks.filterNot(n => cNetworks.contains(n.name))
              val name = cInspection.getName
              mapContainerPorts(cInspection) match {
                case Left(value) =>
                  logger.info(s"Container $name:$id is missing required ports $value so will not be reused")
                  Seq.empty
                case _ if missingNetworks.nonEmpty =>
                  logger.info(s"Container $name:$id is missing required networks $missingNetworks so will not be reused")
                  Seq.empty
                case Right(value) =>
                  Seq((c, cInspection, value))
              }
          }
          if (portSet.nonEmpty) {
            // here we are checking if all ports was successfully mapped
            candidates.find {
              case (_, _, eports) => portSet.diff(eports.dockerPorts.keySet).isEmpty
            }
          } else {
            // or if container has no ports we will check that there is exists container that belongs at least to this test run (or exists container that actually still runs)
            candidates.find(_._2.getState.getRunning).orElse {
              candidates.find {
                case (_, inspection, _) =>
                  val hasLabelsFromSameJvm = (stableLabels.toSet -- client.labelsUnique -- inspection.getConfig.getLabels.asScala.toSet).isEmpty
                  val hasGoodExitCode = inspection.getState.getExitCodeLong == 0L
                  hasLabelsFromSameJvm && hasGoodExitCode
              }
            }
          }
        }

        candidate match {
          case Some((c, cInspection, existingPorts)) =>
            val unverified = DockerContainer[T](
              id = ContainerId(c.getId),
              name = cInspection.getName,
              connectivity = existingPorts,
              containerConfig = config,
              clientConfig = client.clientConfig,
              availablePorts = VerifiedContainerConnectivity.NoAvailablePorts(),
              hostName = cInspection.getConfig.getHostName,
              labels = cInspection.getConfig.getLabels.asScala.toMap,
            )
            logger.info(s"Matching container found: ${config.image}->${unverified.name}:${unverified.id}, will try to reuse...")
            await(unverified, 0)

          case None =>
            logger.info(s"No existing container found for ${config.image}, will run new...")
            doRun(ports)
        }
      }
    }
  }

  private[this] def doRun(ports: Seq[PortDecl]): F[DockerContainer[T]] = {
    val allPortLabels = ports.flatMap(p => p.labels).toMap ++ stableLabels
    val baseCmd = rawClient.createContainerCmd(config.image).withLabels(allPortLabels.asJava)

    val volumes = config.mounts.map {
      case Docker.Mount(h, c, true) => new Bind(h, new Volume(c), true)
      case Docker.Mount(h, c, _) => new Bind(h, new Volume(c))
    }

    val portsEnv = ports.map {
      port => port.port.toEnvVariable -> port.binding.getBinding.getHostPortSpec
    }
    val adjustedEnv = portsEnv ++ config.env

    for {
      out <- F.maybeSuspend {
        @nowarn("msg=method.*Bind.*deprecated")
        val cmd = Value(baseCmd)
          .mut(config.name)(_.withName(_))
          .mut(ports.nonEmpty)(_.withExposedPorts(ports.map(_.binding.getExposedPort).asJava))
          .mut(ports.nonEmpty)(_.withPortBindings(ports.map(_.binding).asJava))
          .mut(adjustedEnv.nonEmpty)(_.withEnv(adjustedEnv.map { case (k, v) => s"$k=$v" }.toList.asJava))
          .mut(config.cmd.nonEmpty)(_.withCmd(config.cmd.toList.asJava))
          .mut(config.entrypoint.nonEmpty)(_.withEntrypoint(config.entrypoint.toList.asJava))
          .mut(config.cwd)(_.withWorkingDir(_))
          .mut(config.user)(_.withUser(_))
          .mut(volumes.nonEmpty)(_.withVolumes(volumes.map(_.getVolume).asJava))
          .mut(volumes.nonEmpty)(_.withBinds(volumes.toList.asJava))
          .map(c => c.withHostConfig(c.getHostConfig.withAutoRemove(config.autoRemove)))
          .get

        if (config.alwaysPull) {
          logger.info(s"Going to pull `${config.image}`...")
          rawClient
            .pullImageCmd(config.image)
            .start()
            .awaitCompletion(config.pullTimeout.toMillis, TimeUnit.MILLISECONDS);
        } else {
          logger.info(s"Skipping explicit pull of `${config.image}`")
        }

        logger.debug(s"Going to create container from image `${config.image}`...")
        val res = cmd.exec()

        logger.debug(s"Going to start container ${res.getId -> "id"}...")
        rawClient.startContainerCmd(res.getId).exec()

        val inspection = rawClient.inspectContainerCmd(res.getId).exec()
        val hostName = inspection.getConfig.getHostName
        val maybeMappedPorts = mapContainerPorts(inspection)

        maybeMappedPorts match {
          case Left(value) =>
            throw new RuntimeException(s"Created container from `${config.image}` with ${res.getId -> "id"}, but ports are missing: $value!")

          case Right(mappedPorts) =>
            val container = DockerContainer[T](
              ContainerId(res.getId),
              inspection.getName,
              hostName,
              inspection.getConfig.getLabels.asScala.toMap,
              config,
              client.clientConfig,
              mappedPorts,
              VerifiedContainerConnectivity.NoAvailablePorts(),
            )
            logger.debug(s"Created $container from ${config.image}...")
            logger.debug(s"Going to attach container ${res.getId -> "id"} to ${config.networks -> "networks"}")
            config.networks.foreach {
              network =>
                rawClient
                  .connectToNetworkCmd()
                  .withContainerId(container.id.name)
                  .withNetworkId(network.id)
                  .exec()
            }

            container
        }
      }
      result <- await(out, 0)
    } yield result
  }

  private def mapContainerPorts(inspection: InspectContainerResponse): Either[UnmappedPorts, ReportedContainerConnectivity] = {
    val network = inspection.getNetworkSettings
    val labels = inspection.getConfig.getLabels
    val ports = config.ports.map {
      containerPort =>
        // if we have dynamic port then we will try find mapped port number in container labels
        val exposedPort = containerPort match {
          case dynamic: DockerPort.Dynamic =>
            Option(labels.get(containerPort.portLabel())).flatMap(_.asInt()).map(toExposedPort(dynamic, _))
          case static: DockerPort.Static =>
            Some(toExposedPort(static, static.number))
        }
        val mappings = exposedPort.toSeq.flatMap(p => Option(network.getPorts.getBindings.get(p))).flatten.map {
          port =>
            ServicePort(port.getHostIp, Integer.parseInt(port.getHostPortSpec))
        }
        (containerPort, NonEmptyList.from(mappings))
    }
    val unmapped = ports.collect { case (cp, None) => cp }

    if (unmapped.nonEmpty) {
      Left(UnmappedPorts(unmapped))
    } else {
      val dockerHost = client.rawClientConfig.getDockerHost.getHost
      val networkAddresses = network.getNetworks.asScala.values.toList.map(_.getIpAddress)
      val mapped = ports.collect { case (cp, Some(lst)) => (cp, lst) }

      Right(
        ReportedContainerConnectivity(
          Option(dockerHost),
          networkAddresses,
          mapped.toMap,
        )
      )
    }

  }

  private val stableLabels = Map(
    DockerConst.Labels.reuseLabel -> Docker.shouldReuse(config.reuse, client.clientConfig.globalReuse).toString
  ) ++ client.labels
}

object ContainerResource {
  private final case class PortDecl(port: DockerPort, localFree: Int, binding: PortBinding, labels: Map[String, String])
}
