# Change Log

## 0.1.13

### Added
- support OCI image output format
- support [skaffold](https://skaffold.dev/) workflow as a custom builder
    - supporting non-root file ownership when container runs as non-root - needed for skaffold
      dev mode to update source files in running pods

### Changed 
- removed dependency on code from leiningen plugin
- `:tagger` functions now return the image name instead of just the image tag.  This
  allows us to let an external workflow drive the target image naming completely

### Fixed
- fix missing resources when doing aot
- fix docker path for nested paths
- fix error message for unknown registry types

## 0.1.14

### Added
- support for [polylith](https://polylith.gitbook.io/polylith/) layout 
- support envsubst style semantics for [:target-image :image-name]
- set default value for :tar type if there's no image-name (app.tar)

### Changed
- default WORKING_DIR changed to `/home/app` (was previously `/`)
    - can still change using `:working-dir` parameter to change this
- update `tools.build` to `v0.8.1` (required an excusion of `com.google.guava/guava` - the maven core transitive dep was breaking google jib)
