## Build Clojure app into Container Image using jib (docker-less build)

* based on [jib][jib]
* does not use `Docker` for building or pushing.  A local Docker install is not required.
* optimized for repeatable image builds
* produces images that work well with OSS vulnerability scanners.  Scanners
  will detect OS, Java, and OSS dependency vulnerabilities.
* uses the [lein metajar packaging][lein-metajar] technique.  Automatically
  generates `Class-Path` entry in `Manifest.mf` entries in jar.
* based on [lein-jib-build][lein-jib-build] ideas and code but installed as a
  [clj Tool][tools-usage].
* automatically adds `org.opencontainers.image` LABELS to the target image

### Install Tool

This can be installed as a [named tool][tools-usage].

```sh
clj -Ttools install io.github.atomisthq/jibbit '{:git/tag "v0.1.13"}' :as jib
```

You can now build clojure projects into containers using `clj -Tjib build`.

### Create Image

Build jar and package into a container image based on `gcr.io/distroless/java`.  It is mandatory to specify a `main` namespace.  This becomes the entry point for the container image.  Change directory to the project containing your `deps.edn` directory and then run the following command.

```sh
$ clj -Tjib build :config "{:main ${MAIN_NAMESPACE}}"
```

This will build the container image to a file named `app.tar`.  You can inspect the layers, the `config.json`, and `manifest.json` before pushing the image.  The base layers will come from `gcr.io/distroless/java` and the two application layers will contain the app's deps.edn dependencies and the compiled app jar.

```sh
$ tar -tf app.tar
e8614d09b7bebabd9d8a450f44e88a8807c98a438a2ddd63146865286b132d1b.tar.gz
c6f4d1a13b699c8490910fd4fd6c7056b90fd0da3077e4f29b4bd27bf0bae6cd.tar.gz
a1f1879bb7de17d50f521ac7c19f3ed9779ded79f26461afb92124ddb1ee7e27.tar.gz
6748f1c8d3a99fdad72a561558e914ab8a4d862b37f7bb577ba8cd946f6fc1f1.tar.gz
52f1c3c7389f760e4cd68a55fc0e59763a6041f8b6c6279e68ba050d268ab5f4.tar.gz
ea16e69f268d9a6abb1e0aee06704d970c168420c54e829f6d8e8f4338b149cd.tar.gz
90066fc6fa0c14d710842fbc03f2f395bcf6fc496de20a85e1f302ab17962923.tar.gz
config.json
manifest.json
```

The `:config` can also be slurped from a local file named `jib.edn`, or the path to a config file can be passed in the environment variable `JIB_CONFIG`.  If using eithese options, simply run `clj -Tjib build`.

The `:main` key is mandatory. Non-default values of `:base-image` and `:target-image` can also be specified.

```edn
{:main "my-namespace.core"
 :base-image {:image-name "gcr.io/distroless/java"
              :type :registry}
 :target-image {:image-name "gcr.io/my-project/my-repository"
                :type :registry
                :authorizer {:fn jibbit.gcloud/authorizer}}}
```

Command substitution will also work if you're using a bourne style shell.

```sh
clj -Tjib build :config "$(< jib.edn)"
```

## Push target Images

### Push to a local Docker daemon

This does require a local Docker install!  However, it does not use Docker for building.  It makes the image available for running in a local docker runtime.

Use a `jib.edn` where the `:target-image` is of type docker.

```edn
{:main "my-namespace.core"
 :target-image {:image-name "namespace/image_name"
                :type :docker}}
```

If running `clj -Tjib build` was successful, you'll be able to run the container locally with this command.

```sh
$ docker run --rm namespace/image_name
```

Since there was no tag specified, this will default to `latest`.

### Push to a remote registry

None of the configurations below rely on a local docker client.  This means that you can build and push from environments that do not have a running docker daemon.

### Push to GCR

If you have `gcloud` installed, and you have already run `gcloud auth login` to login to your account, then the jib tool will fetch credentials from your current login.

```edn
{:main "my-namespace.core"
 :target-image {:image-name "gcr.io/my-project/my-image-name"
                :type :registry
                :authorizer {:fn jibbit.gcloud/authorizer}}}
```

The `authorizer` shown above is a [function](https://github.com/atomisthq/jibbit/blob/main/src/jibbit/gcloud.clj#L6) that will use `gcloud auth print-access-token` to create the access token.

You can reference your own own custom authorizers.  Note that this is running as a [named tool][tools-usage], so the deps of your projects are not loaded in the current runtime.  You can, however, load namespaces from the root of your project (eg from `"."`).

### Push to ECR

There are a few ways to authenticate to ECR.  If you have a local aws profile then use the `:profile` type shown below.  This will call the same api that would be called by the equivalent `aws ecr get-login-password --region us-west-1 --profile sts`.

```edn
{:main "my-namespace.core"
 :target-image {:image-name "ACCOUNT.dkr.ecr.REGION.amazonaws.com/REPOSITORY"
                :type :registry
                :authorizer {:fn jibbit.aws-ecr/ecr-auth
                             :args {:type :profile
                                    :profile-name "sts"
                                    :region "us-west-1"}}}}
```

### Push to DockerHub

Your user must have write access to the docker namespace.  You can set credentials using `:username` and `:password` keys.

```edn
{:main "my-namespace.core"
 :target-image {:image-name "my-namespace/image_name"
                :type :registry
                :username "<my-docker-user>"
                :password "<my-docker-personal-access-token>"}}
```

However, if you want to check in your configuration and still read the credentials from disk, then extract the credentials using an [authorizer function](https://github.com/atomisthq/jibbit/blob/main/src/jibbit/creds.clj#L14).

```edn
{:main "my-namespace.core"
 :target-image {:image-name "my-namespace/image_name"
                :type :registry
                :authorizer {:fn jibbit.creds/load-edn
                             :args {:local ".creds.edn"}}}}
```

Your `.creds.edn` file should be an edn map with two keys (`:username` and `:password`).

```edn
{:username "<my-docker-user>" :password "<my-docker-personall-access-token>"}
```

## Tagging

In most of the above configurations, we did not include a tag in the in `:image-name`.  You can add a tag directly to the config.

```edn
{:main "my-namespace.core"
 :target-image {:image-name "my-namespace/image_name:v1"
                :type :registry
                :authorizer {:fn jibbit.creds/load-edn
                             :args {:local ".creds.edn"}}}}
```

You can also pass the tag in on the command line using the `:tag` key.  When you explicitly set a `:tag` on the command line, it will override any tag that is already present in the `:image-name` of the `:target-image` map.

```bash
clj -Tjib build :tag v1
```

Tags are often extracted dynamically from some other project metadata, like a git tag on the HEAD commit.  Add a `:tagger` to the `:target-image` map to control how the next tag is extracted.  

An out of the box `jibbit.tagger/tag` function is packaged with this tool.  It will try to use a tag on the HEAD commit, or just the HEAD commit `SHA` if there is no tag.  It will throw an exception if the current working copy is not clean. 

```edn
{:main "my-namespace.core"
 :target-image {:image-name "my-namespace/image_name"
                :type :registry
                :tagger {:fn jibbit.tagger/tag}
                :authorizer {:fn jibbit.creds/load-edn
                             :args {:local ".creds.edn"}}}}
```

Any custom `:tagger` that you define will be over-ridden by a `:tag` key on the command line.  An explicit `:tag` value on the command line always wins.

## Using other base images

The examples above were all built on the `gcr.io/distroless/java` base.  You can also use private images from authenticated registries, or other public base images. For example, choose `openjdk:11-slim-buster` as the base image using this configuration.

```edn
{:main "my-namespace.core"
 :user "nobody"
 :base-image {:image-name "openjdk:11-slim-buster"
              :type :registry}}
```

All of the `:authorizer` configs shown above can be used in the `:base-image` maps.  Add an `:authorizer` if you are building on a base image that is stored in a private registry.

## Building an arm64 image

To build an arm64 image, use an arm64 base image such as [arm64v8/openjdk](https://hub.docker.com/r/arm64v8/openjdk/).

```edn
{:main "my-namespace.core"
 :base-image {:image-name "arm64v8/openjdk:11.0.14.1-jdk-bullseye"
              :type :registry}}
```

## Setting a non-root user

By default, this tool tries to set a non-root user when we recognize a base image that comes equipped with a good alternative.  For example, the `openjdk` images have a "nobody" user and the `gcr.io/distroless/java` images have a user with id `65532`.  This can be over-ridden with the `:user` key.  In general, it's a good practice to verify that your images are not required to run as the root user.

If you're using an unrecognized base image, your image will default to run as root.  Add a `:user` key if your image supports running as non-root.

```edn
{:main "my-namespace.core"
 :user "nobody"}
```

## org.opencontainers.image LABELS

This tool automatically adds [opencontainer metadata][opencontainers] LABELs to the target image. It is a good idea to run jib only when the working directory is clean. 

```bash
if [ -z "$(git status --porcelain)" ]; then
  clj -Tjib build
else
  echo "Don't jib with uncommitted changes."
  exit -1
fi
```

By adhering to this rule, the metadata in the Image can be used to trace a running container back to its source code.

Labels that are automatically set in each target image are shown below.

| LABEL | value |
| :---- | :---- |
| org.opencontainers.image.revision | set to the commit SHA |
| org.opencontainers.image.source   | set to the remote git url |
| com.atomist.containers.image.build | jib config used to reproduce this image |

## AOT

Use [tools.build][tools.build] to compile your `.clj` files before packaging them in the container image.  This will obviously slow down the image build but speed up the container runtime.

```edn
{:main "my-namespace.core"
 :aot true}
```

You can experiment with this on the command line.

```bash
clj -Tjib build :aot true
```

## Controlling packaged dependencies with deps.edn aliases

Control the packaged libraries with `:aliases` in your `deps.edn`.  If you need `:extra-deps`, `:classpath-overrides`, `:extra-paths`, or `:jvm-opts` then pass a vector of aliases in the config file.

```edn
{:main "my-namespace.core"
 :aliases [:production]}
```

You can also pass `:aliases` directly on the command line.

```bash
clj -Tjib build :aliases '[:production]'
```

[gene-kim-gist]: https://gist.github.com/realgenekim/fdcad45286d065cc559cd75a8f946ad4#file-jib-build-clj-L45
[lein-jib-build]: https://github.com/vehvis/lein-jib-build
[lein-metajar]: https://github.com/orb/lein-metajar
[jib]: https://github.com/GoogleContainerTools/jib
[tools.build]: https://github.com/clojure/tools.build
[tools-usage]: https://clojure.org/reference/deps_and_cli#_using_named_tools
[aliased-tool-execution]: https://clojure.org/reference/deps_and_cli#_aliased_tool_execution
[opencontainers]: https://github.com/opencontainers/image-spec/blob/main/annotations.md

