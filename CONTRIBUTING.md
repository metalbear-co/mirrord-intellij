# Contributing

Before submitting pull request features, please discuss them with us first by opening an issue or a discussion.
We welcome new/junior/starting developers. Feel free to join to our [Discord channel](https://discord.gg/metalbear) for help and guidance.

If you would like to start working on an issue, please comment on the issue on GitHub, so that we can assign you to that
issue.

# Contents

- [Contributing](#contributing)
- [Contents](#contents)
- [Getting Started](#getting-started)
    - [Setup a Kubernetes cluster](#setup-a-kubernetes-cluster)
    - [Prepare the cluster](#prepare-the-cluster)
- [Debugging](#debugging)
  - [Plugin](#plugin)
  - [mirrord console](#mirrord-console)
  - [Agent logs](#agent-logs)

# Getting Started

The following guide details the steps to setup a local development environment for mirrord.

### Setup a Kubernetes cluster

For testing mirrord manually you will need a working Kubernetes cluster. A minimal cluster can be easily setup locally using either of the following:

- [Minikube](https://minikube.sigs.k8s.io/)
- [Docker Desktop](https://www.docker.com/products/docker-desktop/)
- [Kind](https://kind.sigs.k8s.io/docs/user/quick-start/)

For the ease of illustration and testing, we will conform to using Minikube for the rest of the guide.

Download [Minikube](https://minikube.sigs.k8s.io/)

Start a Minikube cluster with preferred driver. Here we will use the Docker driver:

```bash
minikube start --driver=docker
```

### Prepare the cluster

Load latest mirrord-agent image to Minikube:

```bash
minikube image load ghcr.io/metalbear-co/mirrord:latest
```

Switch Kubernetes context to `minikube`:

```bash
kubectl config get-contexts
```

```bash
kubectl config use-context minikube
```

From the root directory of this repository, create a new testing deployment and service:

```bash
kubectl apply -f sample/kubernetes/app.yaml
```

<details>
  <summary>sample/kubernetes/app.yaml</summary>

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: py-serv-deployment
  labels:
    app: py-serv
spec:
  replicas: 1
  selector:
    matchLabels:
      app: py-serv
  template:
    metadata:
      labels:
        app: py-serv
    spec:
      containers:
        - name: py-serv
          image: ghcr.io/metalbear-co/mirrord-pytest:latest
          ports:
            - containerPort: 80
          env:
            - name: MIRRORD_FAKE_VAR_FIRST
              value: mirrord.is.running
            - name: MIRRORD_FAKE_VAR_SECOND
              value: "7777"

---
apiVersion: v1
kind: Service
metadata:
  labels:
    app: py-serv
  name: py-serv
spec:
  ports:
    - port: 80
      protocol: TCP
      targetPort: 80
      nodePort: 30000
  selector:
    app: py-serv
  sessionAffinity: None
  type: NodePort

```

</details>

Verify everything was created after applying the manifest

```
❯ kubectl get services
NAME         TYPE        CLUSTER-IP     EXTERNAL-IP   PORT(S)        AGE
kubernetes   ClusterIP   10.96.0.1      <none>        443/TCP        3h13m
py-serv      NodePort    10.96.139.36   <none>        80:30000/TCP   3h8m
❯ kubectl get deployments
NAME                 READY   UP-TO-DATE   AVAILABLE   AGE
py-serv-deployment   1/1     1            1           3h8m
❯ kubectl get pods
NAME                                 READY   STATUS    RESTARTS   AGE
py-serv-deployment-ff89b5974-x9tjx   1/1     Running   0          3h8m
```

# Debugging

## Plugin

Open this repository in IntelliJ IDEA. Create a new Gradle run configuration with a `runIde` task.
Running this configuration will open a new IDE window.
When running this configuration in debug, you can set breakpoints in the plugin's code in the first window,
and use the plugin in the second window to reach the breakpoints.

You can control which IDE is opened with the `PLATFORMTYPE` environment variable. For example, set `PLATFORMTYPE=IU` for IntelliJ IDEA Ultimate.

In the new window, open the [sample Java project](./sample/project/). Enable mirrord and run the HTTP server.
From the dropdown menu select the pod from the `py-serv` deployment prepared in the [previous step](#prepare-the-cluster).

Now open a terminal and make some requests to the `py-serv` service. 

```bash
curl $(minikube service py-serv --url)
```

Verify that the requests were mirrored to the sample HTTP server.

```
Hello from py-serv-deployment-58cb9fc5cc-wdzlc
Starting an HTTP server on port 80...
Got a GET request!
Got a GET request!
Got a GET request!
```

## mirrord console

Debugging mirrord can get hard since we're running from another app flow, so the fact we're debugging might affect the program and make it unusable/buggy (due to sharing stdout with scripts/other applications).

The recommended way to do it is to use `mirrord-console`. It is a small application that receives log information from different mirrord instances and prints it, controlled via `RUST_LOG` environment variable. You can find it in the mirrord [main repo](https://github.com/metalbear-co/mirrord).

To use the console, go into the main repo and run it with [cargo](https://www.rust-lang.org/tools/install):

```bash
cargo run --bin mirrord-console --features=binary
```

To make the plugin communicate with the console, add environment variables to the run/debug configuration:

```bash
RUST_LOG=warn,mirrord=trace
MIRRORD_CONSOLE_ADDR=127.0.0.1:11233
```

## Agent logs

To provide the local process with the cluster environment, mirrord spawns a special pod in the cluster, called agent.
By default, the agent's pod will complete and disappear shortly after the agent exits. In order to be able to retrieve 
the agent's logs after it crashes, set the agent's pod's TTL to a comfortable number of seconds. This configuration can
be specified as an environment variable (`MIRRORD_AGENT_TTL=30`), or in the mirrord configuration file:

```toml
[agent]
ttl = 30
```

Then, when running with some reasonable TTL, you can retrieve the agent log like this:
```bash
kubectl logs -l app=mirrord --tail=-1 | less -R
```

This will retrieve the logs from all running mirrord agents, so it is only useful when just one agent pod exists.

If there are currently multiple agent pods running on your cluster, you would have to run

```bash
kubectl get pods
```

and find the name of the agent pod you're interested in, then run

```bash
kubectl logs <YOUR_POD_NAME> | less -R
```

where you would replace `<YOUR_POD_NAME>` with the name of the pod.
