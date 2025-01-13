package bloop.scalanative
import java.nio.file.Files
import java.nio.file.Path

import scala.scalanative.build
import scala.scalanative.build.BuildTarget
import scala.scalanative.util.Scope
import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import bloop.config.Config.NativeBuildTarget
import bloop.config.Config.LinkerMode
import bloop.config.Config.NativeConfig
import bloop.io.Paths
import bloop.io.AbsolutePath
import bloop.logging.DebugFilter
import bloop.logging.Logger
import bloop.config.Config.NativeLTO.Full
import bloop.config.Config.NativeLTO
import bloop.config.Config.NativeLTO.Thin
import bloop.config.Config.NativeLinkerReleaseMode.ReleaseFast
import bloop.config.Config.NativeLinkerReleaseMode.ReleaseFull
import bloop.config.Config.NativeLinkerReleaseMode.ReleaseSize

class NativeLinkerException(msg: String) extends RuntimeException(msg)

object NativeBridge {
  private implicit val ctx: DebugFilter = DebugFilter.Link
  private val sharedScope = Scope.unsafe()

  def nativeLink(
      config0: NativeConfig,
      workdir: Path,
      classpath: Array[Path],
      entry: Option[String],
      target: Path,
      logger: Logger,
      ec: ExecutionContext
  ): Future[Path] = {

    val absWorkdir = AbsolutePath(workdir)
    if (absWorkdir.isDirectory) Paths.delete(absWorkdir)
    Files.createDirectories(workdir)

    val nativeLogger =
      build.Logger(logger.trace _, logger.debug _, logger.info _, logger.warn _, logger.error _)
    val config = setUpNativeConfig(classpath, config0)

    val nativeMode = config.mode match {
      case LinkerMode.Debug => build.Mode.debug
      case LinkerMode.Release =>
        config.nativeModeAndLTO.nativeLinkerReleaseMode match {
          case None => build.Mode.releaseFast

          case Some(mode) =>
            mode match {
              case ReleaseFast => build.Mode.releaseFast
              case ReleaseFull => build.Mode.releaseFull
              case ReleaseSize => build.Mode.releaseFast
            }
        }
    }

    val nativeLTO = config.nativeModeAndLTO.lto match {
      case None => build.LTO.none
      case Some(lto) =>
        lto match {
          case Full => build.LTO.full
          case NativeLTO.None => build.LTO.none
          case Thin => build.LTO.thin
        }
    }

    val buildTarget = config.buildTarget
      .map(_ match {
        case NativeBuildTarget.Application => BuildTarget.application
        case NativeBuildTarget.LibraryDynamic => BuildTarget.libraryDynamic
        case NativeBuildTarget.LibraryStatic => BuildTarget.libraryStatic
      })
      .getOrElse(BuildTarget.application)

    val nativeConfig =
      build.Config.empty
        .withClassPath(classpath)
        .withBaseDir(target.getParent())
        .withLogger(nativeLogger)
        .withCompilerConfig(
          build.NativeConfig.empty
            .withClang(config.clang)
            .withClangPP(config.clangpp)
            .withBaseName(target.getFileName().toString())
            .withCompileOptions(config.options.compiler)
            .withLinkingOptions(config.options.linker)
            .withGC(build.GC(config.gc))
            .withMode(nativeMode)
            .withBuildTarget(buildTarget)
            .withLTO(nativeLTO)
            .withLinkStubs(config.linkStubs)
            .withCheck(config.check)
            .withDump(config.dump)
            .withTargetTriple(config.targetTriple)
            .withOptimize(config.nativeFlags.optimize)
            .withEmbedResources(config.nativeFlags.embedResources)
            .withIncrementalCompilation(config.nativeFlags.useIncrementalCompilation)
            .withMultithreading(config.nativeFlags.multithreading)
        )

    if (buildTarget == BuildTarget.application) {
      entry match {
        case None =>
          Future.failed(
            new NativeLinkerException("Missing main class when linking native application")
          )
        case Some(mainClass) =>
          build.Build.build(nativeConfig.withMainClass(Some(mainClass)))(sharedScope, ec)
      }
    } else build.Build.build(nativeConfig)(sharedScope, ec)

  }

  private[scalanative] def setUpNativeConfig(
      classpath: Array[Path],
      config: NativeConfig
  ): NativeConfig = {
    val mode = config.mode
    val options = config.options
    val gc = if (config.gc.isEmpty) build.GC.default.name else config.gc
    val clang = if (config.clang.toString.isEmpty) build.Discover.clang() else config.clang
    val clangpp = if (config.clangpp.toString.isEmpty) build.Discover.clangpp() else config.clangpp
    val lopts = if (options.linker.isEmpty) build.Discover.linkingOptions() else options.linker
    val copts = if (options.compiler.isEmpty) build.Discover.compileOptions() else options.compiler

    val targetTriple = config.targetTriple

    NativeConfig.apply(
      version = config.version,
      mode = mode,
      toolchain = Nil, // No worries, toolchain is on this project's classpath
      gc = gc,
      targetTriple = targetTriple,
      clang = clang,
      clangpp = clangpp,
      options = options,
      linkStubs = config.linkStubs,
      check = config.check,
      dump = config.dump,
      output = config.output
    )
  }
}
