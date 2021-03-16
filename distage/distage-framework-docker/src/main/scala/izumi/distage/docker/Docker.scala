package izumi.distage.docker

import java.util.concurrent.TimeUnit

import izumi.distage.config.codec.{DIConfigReader, PureconfigAutoDerive}
import izumi.distage.docker.ContainerNetworkDef.ContainerNetwork
import izumi.distage.docker.Docker.ClientConfig.parseReusePolicy
import izumi.distage.docker.healthcheck.ContainerHealthCheck
import izumi.fundamentals.collections.nonempty.NonEmptyList
import pureconfig.ConfigReader

import scala.concurrent.duration.FiniteDuration

object Docker {
  final case class AvailablePort(hostV4: String, port: Int)
  object AvailablePort {
    def local(port: Int): AvailablePort = hostPort("127.0.0.1", port)
    def hostPort(host: String, port: Int): AvailablePort = AvailablePort(host, port)
  }

  final case class ServicePort(listenOnV4: String, port: Int)

  final case class ContainerId(name: String) extends AnyVal {
    override def toString: String = name
  }

  sealed trait DockerPort {
    def protocol: String
    def toEnvVariable: String
    def portLabel(parts: String*): String
  }
  object DockerPort {
    sealed trait TCPBase extends DockerPort {
      override def protocol: String = "tcp"
    }
    sealed trait UDPBase extends DockerPort {
      override def protocol: String = "udp"
    }
    sealed trait Static extends DockerPort {
      def number: Int
      override def toString: String = s"$protocol:$number"
      final def toEnvVariable: String = s"${DockerConst.Vars.portPrefix}_${protocol.toUpperCase}_$number"
      final def portLabel(parts: String*): String = (s"${DockerConst.Labels.portPrefix}.$protocol.$number" :: parts.toList).mkString(".")
    }
    sealed trait Dynamic extends DockerPort {
      def name: String
      override def toString: String = s"$protocol:$name"
      final def toEnvVariable: String = s"${DockerConst.Vars.portPrefix}_${protocol.toUpperCase}_${name.toUpperCase}"
      final def portLabel(parts: String*): String = (s"${DockerConst.Labels.portPrefix}.$protocol.$name" :: parts.toList).mkString(".")
    }
    final case class DynamicTCP(name: String) extends Dynamic with TCPBase
    final case class DynamicUDP(name: String) extends Dynamic with UDPBase
    final case class TCP(number: Int) extends Static with TCPBase
    final case class UDP(number: Int) extends Static with UDPBase
  }

  sealed abstract class DockerReusePolicy(val reuseEnabled: Boolean, val killEnforced: Boolean)
  object DockerReusePolicy {
    case object ReuseDisabled extends DockerReusePolicy(false, true)
//    case object ReuseButAlwaysKill extends DockerReusePolicy(true, true)
    case object ReuseEnabled extends DockerReusePolicy(true, false)

    implicit val configReader: ConfigReader[DockerReusePolicy] =
      ConfigReader[String].map(name => parseReusePolicy(name))
  }

  private[docker] def shouldReuse(reusePolicy: DockerReusePolicy, globalReuse: DockerReusePolicy): Boolean = {
    globalReuse.reuseEnabled && reusePolicy.reuseEnabled
  }

  private[docker] def shouldKill(reusePolicy: DockerReusePolicy, globalReuse: DockerReusePolicy): Boolean = {
    reusePolicy.killEnforced && globalReuse.killEnforced
  }

  /**
    * Parameters that define the behavior of this docker container,
    * Will be interpreted by [[izumi.distage.docker.ContainerResource]]
    *
    * @param image    Docker Image to use
    *
    * @param ports    Ports to map on the docker container
    *
    * @param networks Docker networks to connect this container to
    *
    * @param reuse    If true and [[ClientConfig#globalReuse]] is also true, keeps container alive after tests.
    *                 If false, the container will be shut down.
    *                 default: true
    *
    * @param healthCheck The function to use to test if a container has started already,
    *                    by default probes to check if all [[ports]] are open and proceeds if so.
    *
    * @param healthCheckInterval Sleep interval between [[healthCheck]]s
    *                            default: 1 second
    *
    * @param portProbeTimeout Sleep interval between port probes in the default [[healthCheck]]
    *                         default: 200 milliseconds
    *
    * @param pullTimeout Maximum amount of time to wait for `docker pull` to download the image
    *                    default: 120 seconds
    *
    * @param name     Name of the container, if left at `None` Docker will generate a random name
    *
    * @param env      Setup environment variables visible inside docker container
    *
    * @param cmd      Entrypoint command to use
    *
    * @param entrypoint Docker entrypoint to use
    *
    * @param cwd      Working directory to use inside the docker container
    *
    * @param mounts   Host paths mounted to Volumes inside the docker container
    *
    * @param alwaysPull Pull the image before starting the container
    *                   default: true
    */
  final case class ContainerConfig[T](
    image: String,
    ports: Seq[DockerPort],
    name: Option[String] = None,
    env: Map[String, String] = Map.empty,
    cmd: Seq[String] = Seq.empty,
    entrypoint: Seq[String] = Seq.empty,
    cwd: Option[String] = None,
    user: Option[String] = None,
    mounts: Seq[Mount] = Seq.empty,
    networks: Set[ContainerNetwork[_]] = Set.empty,
    reuse: DockerReusePolicy = DockerReusePolicy.ReuseEnabled,
    autoRemove: Boolean = true,
    healthCheckInterval: FiniteDuration = FiniteDuration(1, TimeUnit.SECONDS),
    healthCheckMaxAttempts: Int = 120,
    pullTimeout: FiniteDuration = FiniteDuration(120, TimeUnit.SECONDS),
    healthCheck: ContainerHealthCheck[T] = ContainerHealthCheck.portCheck[T],
    portProbeTimeout: FiniteDuration = FiniteDuration(200, TimeUnit.MILLISECONDS),
    alwaysPull: Boolean = true,
  ) {
    def tcpPorts: Set[DockerPort] = ports.collect { case t: DockerPort.TCPBase => t: DockerPort }.toSet
    def udpPorts: Set[DockerPort] = ports.collect { case t: DockerPort.UDPBase => t: DockerPort }.toSet
  }

  /**
    * Client configuration that will be read from HOCON config.
    * See `docker-reference.conf` for an example configuration.
    * You can `include` the reference configuration if you want to use defaults.
    *
    * @param globalReuse   If true and container's [[ContainerConfig#reuse]] is also true, keeps container alive after
    *                     initialization. If false, the container will be shut down.
    *
    * @param daemon      Options to connect to a Docker Daemon
    *
    * @param useRegistry  Connect to speicifed Docker Registry
    *
    * @param registry     Options to connect to custom Docker Registry host,
    *                     will try to connect to specified registry, instead of the default if [[useRegistry]] is `true`
    *
    */
  final case class ClientConfig(
    globalReuse: DockerReusePolicy = ClientConfig.defaultReusePolicy,
    daemon: Option[DaemonDockerConfig] = None,
    useRegistry: Boolean = false,
    registry: Option[DockerRegistryConfig] = None
  )

  object ClientConfig {
    implicit val configReader: ConfigReader[ClientConfig] = PureconfigAutoDerive.derived
    implicit val diConfigReader: DIConfigReader[ClientConfig] = DIConfigReader.deriveFromPureconfigConfigReader

    val defaultReusePolicy: DockerReusePolicy = {
      DebugProperties.`izumi.distage.docker.reuse`
        .strValue()
        .fold[DockerReusePolicy](DockerReusePolicy.ReuseEnabled)(parseReusePolicy)
    }

    def parseReusePolicy(name: String): DockerReusePolicy = {
      name match {
        case "ReuseDisabled" | "false" =>
          DockerReusePolicy.ReuseDisabled
//        case "ReuseButAlwaysKill" =>
//          DockerReusePolicy.ReuseButAlwaysKill
        case "ReuseEnabled" | "true" =>
          DockerReusePolicy.ReuseEnabled
        case other =>
          throw new IllegalArgumentException(s"Unexpected config value for reuse policy: $other")
      }
    }
  }

  final case class DaemonDockerConfig(
    host: String,
    tlsVerify: Boolean = false,
    certPath: String,
    config: String
  )

  final case class DockerRegistryConfig(url: String, username: String, password: String, email: String)

  final case class Mount(hostPath: String, containerPath: String, noCopy: Boolean = false)

  final case class UnmappedPorts(ports: Seq[DockerPort])

  final case class ReportedContainerConnectivity(
    dockerHost: Option[String],
    containerAddressesV4: Seq[String],
    dockerPorts: Map[DockerPort, NonEmptyList[ServicePort]],
  ) {
    override def toString: String = s"{host: $dockerHost; addresses=$containerAddressesV4; ports=$dockerPorts}"
  }

}
