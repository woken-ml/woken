# hbpmip/woken

[![License](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](https://github.com/LREN-CHUV/woken/blob/master/LICENSE) [![](https://images.microbadger.com/badges/version/hbpmip/woken.svg)](https://hub.docker.com/r/hbpmip/woken/tags "hbpmip/woken image tags") [![](https://images.microbadger.com/badges/image/hbpmip/woken.svg)](https://microbadger.com/#/images/hbpmip/woken "hbpmip/woken on microbadger")

## Docker image for Woken

An orchestration platform for Docker containers running data mining algorithms.

This project exposes a web interface to execute on demand data mining algorithms defined in Docker containers and implemented using any tool or language (R, Python, Java and more are supported).

It relies on a runtime environment containing [Mesos](http://mesos.apache.org) and [Chronos])(https://mesos.github.io/chronos/) to control and execute the Docker containers over a cluster.

## Usage

```sh

 docker run --rm --env [list of environment variables] --link woken hbpmip/woken:3.0.2

```

where the environment variables are:

* CLUSTER_IP: Name of this server advertised in the Akka cluster
* CLUSTER_PORT: Port of this server advertised in the Akka cluster
* CLUSTER_NAME: Name of Woken cluster, default to 'woken'
* WOKEN_PORT_8088_TCP_ADDR: Address of Woken master server
* WOKEN_PORT_8088_TCP_PORT: Port of Woken master server, default to 8088
* DOCKER_BRIDGE_NETWORK: Name of the Docker bridge network. Default to 'bridge'
* NETWORK_INTERFACE: IP address for listening to incoming HTTP connections. Default to '0.0.0.0'
* WEB_SERVICES_PORT: Port for the HTTP server in Docker container. Default to 8087
* WEB_SERVICES_SECURE: If yes, HTTPS with a custom certificate will be used. Default to no.
* WEB_SERVICES_USER: Name used to protected the web servers protected with HTTP basic authentication. Default to 'admin'
* WEB_SERVICES_PASSWORD: Password used to protected the web servers protected with HTTP basic authentication.
* LOG_LEVEL: Level for generic logs on standard output, default to WARN
* LOG_CONFIG: on/off - log configuration on start, default to off
* AKKA_CLUSTER_LOG_LEVEL: Level for Akka Cluster logs, default to INFO
* AKKA_REMOTE_LOG_LEVEL: Level for Akka Remote logs, default to INFO
* AKKA_HTTP_LOG_LEVEL: Level for Akka HTTP logs, default to INFO
* AKKA_IO_LOG_LEVEL: Level for Akka IO logs, default to INFO
* AKKA_STREAM_LOG_LEVEL: Level for Akka Stream logs, default to INFO
* WOKEN_LOG_LEVEL: Level for Woken logs, default to INFO
* DB_LOG_LEVEL: Level for database logs, default to WARN
* NETTY_LOG_LEVEL: Level for Netty logs, default to ERROR
* VALIDATION_MIN_SERVERS: minimum number of servers with the 'validation' functionality in the cluster, default to 0
* SCORING_MIN_SERVERS: minimum number of servers with the 'scoring' functionality in the cluster, default to 0
* KAMON_ENABLED: enable monitoring with Kamon, default to no
* ZIPKIN_ENABLED: enable reporting traces to Zipkin, default to no. Requires Kamon enabled.
* ZIPKIN_IP: IP address to Zipkin server. Requires Kamon and Zipkin enabled.
* ZIPKIN_PORT: Port to Zipkin server. Requires Kamon and Zipkin enabled.
* PROMETHEUS_ENABLED: enable reporting metrics to Prometheus, default to no. Requires Kamon enabled.
* PROMETHEUS_IP: IP address to Prometheus server. Requires Kamon and Prometheus enabled.
* PROMETHEUS_PORT: Port to Prometheus server. Requires Kamon and Prometheus enabled.
* SIGAR_SYSTEM_METRICS: Enable collection of metrics of the system using Sigar native library, default to no. Requires Kamon enabled.
* JVM_SYSTEM_METRICS: Enable collection of metrics of the JVM using JMX, default to no. Requires Kamon enabled.
* MINING_LIMIT: Maximum number of concurrent mining operations. Default to 100
* EXPERIMENT_LIMIT: Maximum number of concurrent experiments. Default to 100
* RELEASE_STAGE: Release stage used when reporting errors to Bugsnag. Values are dev, staging, production
* DATA_CENTER_LOCATION: Location of the datacenter, used when reporting errors to Bugsnag
* CONTAINER_ORCHESTRATION: Container orchestration system used to execute the Docker containers. Values are mesos, docker-compose, kubernetes
