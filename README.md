## Build Clojure app into Container Image using jib (docker-less build)

* based on [jib][jib]
* does not use `Docker` for building.  A local Docker install is not required if pushing only to remote registries
* optimized for repeatable image builds
* produces images that work well with OSS vulnerability scanners.  Scanners will detect OS, Java, and OSS dependency vulnerabilities.
* uses the [lein metajar packaging][lein-metajar] technique.  Automatically generates `Class-Path` entry in `Manifest.mf` entries in jar.
* based on [lein-jib-build ideas][lein-jib-build] and code but just requires a `deps.edn` alias to configure. 

### Configure deps.edn

```clj
{
  :aliases {
    :package {
      :deps {io.github.atomist/jibbit {:local/root "/Users/slim/atomist/jibbit"}}
      :ns-default jibbit.core
    } 
  }
}
```

### Create Image

Build jar and package into a container image based on `gcr.io/distroless/java`.  It is mandatory to specify a namespace.  This
becomes the entry point for the container image.

```sh
$ clj -T:package build :main ${MAIN_NAMESPACE}
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

## Push Images

### Push to a local Docker daemon

This does require a local Docker install!  However, it does not use Docker for building.  It just makes the image available for running.

```sh
$ clj -T:package build :main ${MAIN_NAMESPACE} :docker namespace/image_name
```

If this command is successful, you'll be able to run the container locally with this command.

```sh
$ docker run --rm namespace/image_name
```

### Push to DockerHub

Create an edn file somewhere with your DockerHub username and an access token.

```clj
{:username "your-username"
 :password "access-token"}
```

Your username must have write access to the namespace in the command below.  Pass the location of the edn file you created using the `:target-creds` keyword.

```
$ clj -T:package build :main ${MAIN_NAMESPACE} :repository namespace/image_name :target-creds creds.edn
```

### Push to GCR

If you have `gcloud` installed then run `gcloud auth login` to login to your account.

```
$ clj -T:package build :main ${MAIN_NAMESPACE} :repository gcr.io/${YOUR_PROJECT_ID}/image_name :target-authorizer jibbit.gcloud/authorizer
```

The value of the `:target-authorizer` is a function that will use `gcloud auth print-access-token` to create an access token for gcr. 

### Push to ECR

TODO

[gene-kim-gist]: https://gist.github.com/realgenekim/fdcad45286d065cc559cd75a8f946ad4#file-jib-build-clj-L45
[lein-jib-build]: https://github.com/vehvis/lein-jib-build
[lein-metajar]: https://github.com/orb/lein-metajar
[jib]: https://github.com/GoogleContainerTools/jib

