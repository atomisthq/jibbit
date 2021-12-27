## Build Clojure app into Container Image using jib (docker-less build)

* based on [jib][jib]
* does not use `Docker` for building.  A local Docker install is not required
  if pushing only to remote registries
* optimized for repeatable image builds
* produces images that work well with OSS vulnerability scanners.  Scanners
  will detect OS, Java, and OSS dependency vulnerabilities.
* uses the [lein metajar packaging][lein-metajar] technique.  Automatically
  generates `Class-Path` entry in `Manifest.mf` entries in jar.
* based on [lein-jib-build][lein-jib-build] ideas and code but installed as a
  [clj Tool][tools-usage].

### Install Tool

```sh
clj -Ttools install io.github.atomisthq/jibbit '{:git/tag "v0.1.1"}' :as jib
```

You can now build clojure projects into containers using `clj -Tjib build`.  Explicit examples are shown below.

### Create Image

Build jar and package into a container image based on `gcr.io/distroless/java`.  It is mandatory to specify a namespace.  This
becomes the entry point for the container image.  You must change directory to
the project containing your `deps.edn` directory and then run the following
command.

```sh
$ clj -Tjib build :config "{:main ${MAIN_NAMESPACE}}"
```

This will build the container image to a file named `app.tar`.  You can inspect
the layers, the `config.json`, and `manifest.json` before pushing the image.
The base layers will come from `gcr.io/distroless/java` and the two application
layers will contain the app's deps.edn dependencies and the compiled app jar.

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
                :authorizer {:fn 'jibbit.gcloud/authorizer}}}
```

Command substitution will also work if you're using a bourne style shell.

```sh
clj -Tjib build :config $(< jib.edn)
```

## Push target Images

### Push to a local Docker daemon

This does require a local Docker install!  However, it does not use Docker for building.  It makes the image available for running in a local docker runtime.

User a `jibbit.edn` where the `:target-image` is of type docker.

```edn
{:main "my-namespace.core"
 :target-image {:image-name "namespace/image_name"
                :type :docker}}
```

If running `clj -Tjib build` is successful, you'll be able to run the container locally with this command.

```sh
$ docker run --rm namespace/image_name
```

### Push to DockerHub

Your user must have write access to the docker namespace called `my-namespace`.

```edn
{:main "my-namespace.core"
 :target-image {:image-name "my-namespace/image_name"
                :type :registry
                :username "my-user"
                :password "my-password"}}
```

This does not rely on a local docker install to push.  [Jib][jib] has its own docker push client.

### Push to GCR

If you have `gcloud` installed, and you have already run `gcloud auth login` to login to your account, then 
you the jib tool can fetch credentials from you currently login.

```edn
{:main "my-namespace.core"
 :target-image {:image-name "gcr.io/my-project/my-image-name"
                :type :registry
                :authorizer {:fn jibbit.gcloud/authorizer}}}
```

The value of the `:target-authorizer` is a
[function](https://github.com/atomisthq/jibbit/blob/main/src/jibbit/gcloud.clj#L6)
that will use `gcloud auth print-access-token` to create the access token for
gcr. 

### Push to ECR

There are a few ways to authenticate to ECR.  If you have a local named aws profile (e.g. `sts`) then use the `:profile` type shown below.  This will call the same api
that would be called by the equivalent `aws ecr get-login-password --region us-west-1 --profile sts`.

```edn
{:main "my-namespace.core"
 :target-image {:image-name "ACCOUNT.dkr.ecr.REGION.amazonaws.com/REPOSITORY"
                :type :registry
                :authorizer {:fn leiningen.aws-ecr-auth/ecr-auth
                             :args {:type :profile
                                    :profile-name "sts"
                                    :region "us-west-1"}}}}
```

[gene-kim-gist]: https://gist.github.com/realgenekim/fdcad45286d065cc559cd75a8f946ad4#file-jib-build-clj-L45
[lein-jib-build]: https://github.com/vehvis/lein-jib-build
[lein-metajar]: https://github.com/orb/lein-metajar
[jib]: https://github.com/GoogleContainerTools/jib
[tools.build]: https://github.com/clojure/tools.build
[tools-usage]: https://clojure.org/reference/deps_and_cli#_using_named_tools
