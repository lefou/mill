package mill.androidlib

import coursier.MavenRepository
import coursier.cache.CachePolicy.LocalOnly
import coursier.cache.FileCache
import coursier.util.Artifact
import mill.*
import mill.api.Result
import mill.androidlib.Versions

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.util.chaining.given
import scala.xml.XML

/**
 * Trait for managing the Android SDK in a Mill build system.
 *
 * This trait offers utility methods for automating the download, installation,
 * and configuration of the Android SDK, build tools, and other essential
 * components necessary for Android development. It facilitates setting up
 * an Android development environment, streamlining the process of building,
 * compiling, and packaging Android applications in a Mill project.
 *
 * For more information, refer to the official Android
 * [[https://developer.android.com/studio documentation]].
 */
@mill.api.experimental
trait AndroidSdkModule extends Module {

  // this has a format `repository2-%d`, where the last number is schema version. For the needs of this module it
  // is okay to stick with the particular version.
  private val remotePackagesUrl = "https://dl.google.com/android/repository/repository2-3.xml"

  /**
   * Specifies the version of the Android Bundle tool to be used.
   */
  def bundleToolVersion: T[String] = Task {
    Versions.bundleToolVersion
  }

  /**
   * Specifies the version of the Manifest Merger.
   */
  def manifestMergerVersion: T[String] = Task {
    Versions.manifestMergerVersion
  }

  /**
   * Specifies the version of the Android build tools to be used.
   */
  def buildToolsVersion: T[String]

  /**
   * Specifies the version of the Android NDK (Native Development Kit) to be used.
   */
  def ndkVersion: T[String] = Task {
    Versions.ndkVersion
  }

  /**
   * Specifies the version of CMake to be used.
   */
  def cmakeVersion: T[String] = Task {
    Versions.cmakeVersion
  }

  /**
   * Specifies the Android platform version (e.g., Android API level).
   */
  def platformsVersion: T[String] = Task { "android-" + buildToolsVersion().split('.').head }

  /**
   * URL to download bundle tool, used for creating Android app bundles (AAB files).
   */
  def bundleToolUrl: T[String] = Task {
    s"https://github.com/google/bundletool/releases/download/${bundleToolVersion()}/bundletool-all-${bundleToolVersion()}.jar"
  }

  /**
   * Provides the path to the `bundleTool.jar` file, necessary for creating Android bundles.
   *
   * For More Read Bundle Tool [[https://developer.android.com/tools/bundletool Documentation]]
   */
  def bundleToolPath: T[PathRef] = Task() {
    val url = bundleToolUrl()
    // TODO: Use caching API once available, https://github.com/com-lihaoyi/mill/issues/3930
    val cache = FileCache()
      .pipe { cache =>
        if (Task.offline) cache.withCachePolicies(Seq(LocalOnly)) else cache
      }
    cache.logger.use(cache.file(Artifact(url)).run).unsafeRun()(using cache.ec) match {
      case Right(file) =>
        PathRef(os.Path(file)).withRevalidateOnce
      case Left(_) if Task.offline =>
        Task.fail(s"Can't fetch bundle tools (from ${url}) while in offline mode.")
      case Left(ex) =>
        Task.fail(ex.getMessage())

    }
  }

  /**
   * Provides all the Android libraries classpaths, including `android.jar` and other necessary files,
   * for the Android R8 tool.
   */
  def androidLibsClasspaths: T[Seq[PathRef]] = Task {
    installAndroidSdkComponents()
    Seq(
      PathRef(sdkPath().path / "platforms" / platformsVersion() / "android.jar"),
      PathRef(sdkPath().path / "platforms" / platformsVersion() / "core-for-system-modules.jar"),
      PathRef(
        sdkPath().path / "platforms" / platformsVersion() / "optional" / "org.apache.http.legacy.jar"
      ),
      PathRef(sdkPath().path / "platforms" / platformsVersion() / "optional" / "android.car.jar"),
      PathRef(
        sdkPath().path / "platforms" / platformsVersion() / "optional" / "android.test.mock.jar"
      ),
      PathRef(
        sdkPath().path / "platforms" / platformsVersion() / "optional" / "android.test.base.jar"
      ),
      PathRef(
        sdkPath().path / "platforms" / platformsVersion() / "optional" / "android.test.runner.jar"
      )
    )
  }

  /**
   * Provides the path to the `android.jar` file, necessary for compiling Android apps.
   */
  def androidJarPath: T[PathRef] = Task {
    installAndroidSdkComponents()
    PathRef(sdkPath().path / "platforms" / platformsVersion() / "android.jar")
  }

  /**
   * Provides path to the Android build tools for the selected version.
   */
  def buildToolsPath: T[PathRef] = Task {
    installAndroidSdkComponents()
    PathRef(sdkPath().path / "build-tools" / buildToolsVersion())
  }

  /**
   * Provides path to the Android CLI lint tool.
   */
  def lintToolPath: T[PathRef] = Task {
    installAndroidSdkComponents()
    PathRef(sdkPath().path / "cmdline-tools/latest/bin/lint")
  }

  /**
   * Provides path to D8 Dex compiler, used for converting Java bytecode into Dalvik bytecode.
   *
   * For More Read D8 [[https://developer.android.com/tools/d8 Documentation]]
   */
  def d8Path: T[PathRef] = Task {
    PathRef(buildToolsPath().path / "d8")
  }

  /**
   * Provides the path to AAPT2, used for resource handling and APK packaging.
   *
   * For More Read AAPT2 [[https://developer.android.com/tools/aapt2 Documentation]]
   */
  def aapt2Path: T[PathRef] = Task {
    PathRef(buildToolsPath().path / "aapt2")
  }

  /**
   * Provides the path to the Zipalign tool, which optimizes APK files by aligning their data.
   *
   * For More Read Zipalign [[https://developer.android.com/tools/zipalign Documentation]]
   */
  def zipalignPath: T[PathRef] = Task {
    PathRef(buildToolsPath().path / "zipalign")
  }

  def fontsPath: T[PathRef] = Task {
    PathRef(sdkPath().path / "fonts")
  }

  /**
   * Provides the path to the APK signer tool, used to digitally sign APKs.
   *
   * For More Read APK Signer [[https://developer.android.com/tools/apksigner Documentation]]
   */
  def apksignerPath: T[PathRef] = Task {
    PathRef(buildToolsPath().path / "apksigner")
  }

  /**
   * Provides the path for the Android Debug Bridge (adt) tool.
   *
   * For more information, refer to the official Android documentation [[https://developer.android.com/tools/adb]]
   */
  def adbPath: T[PathRef] = Task {
    PathRef(sdkPath().path / "platform-tools/adb")
  }

  /**
   * Provides the path for the Android Virtual Device Manager (avdmanager) tool
   *
   *  For more information refer to the official Android documentation [[https://developer.android.com/tools/avdmanager]]
   */
  def avdPath: T[PathRef] = Task {
    PathRef(sdkPath().path / "cmdline-tools/latest/bin/avdmanager")
  }

  /**
   * Provides the path for the android emulator tool
   *
   * For more information refer to [[https://developer.android.com/studio/run/emulator]]
   */
  def emulatorPath: T[PathRef] = Task {
    PathRef(sdkPath().path / "emulator/emulator")
  }

  /**
   * Location of the default proguard optimisation config.
   * See also [[https://developer.android.com/build/shrink-code]]
   */
  def androidProguardPath: T[PathRef] = Task {
    PathRef(sdkPath().path / "tools/proguard")
  }

  /**
   * Provides the path for the Android SDK Manager tool
   *
   * @return A task containing a [[PathRef]] pointing to the SDK directory.
   */
  def sdkManagerPath: T[PathRef] = Task {
    PathRef(sdkPath().path / "cmdline-tools/latest/bin/sdkmanager")
  }

  /**
   * Provides the path for the r8 tool, used for code shrinking and optimization.
   *
   * @return A task containing a [[PathRef]] pointing to the r8 directory.
   */
  def r8Exe: T[PathRef] = Task {
    PathRef(sdkPath().path / "cmdline-tools/latest/bin/r8")
  }

  def ndkPath: T[PathRef] = Task {
    installAndroidNdk()
    PathRef(sdkPath().path / "ndk" / ndkVersion())
  }

  def ninjaPath: T[PathRef] = Task {
    installAndroidNdk()
    PathRef(sdkPath().path / "cmake" / cmakeVersion() / "bin" / "ninja")
  }

  def cmakePath: T[PathRef] = Task {
    installAndroidNdk()
    PathRef(sdkPath().path / "cmake" / cmakeVersion() / "bin" / "cmake")
  }

  def cmakeToolchainFilePath: T[PathRef] = Task {
    installAndroidNdk()
    PathRef(ndkPath().path / "build" / "cmake" / "android.toolchain.cmake")
  }

  /**
   * Installs the necessary Android SDK components such as platform-tools, build-tools, and Android platforms.
   *
   * For more details on the `sdkmanager` tool, refer to:
   * [[https://developer.android.com/tools/sdkmanager sdkmanager Documentation]]
   */
  def installAndroidSdkComponents: T[Unit] = Task {
    val sdkPath0 = sdkPath()
    val sdkManagerPath = findLatestSdkManager(sdkPath0.path) match {
      case Some(x) => x
      case _ => throw new IllegalStateException(
          s"Cannot locate cmdline-tools in Android SDK $sdkPath0. Download" +
            " it at https://developer.android.com/studio#command-tools. See https://developer.android.com/tools" +
            " for more details."
        )
    }

    val packages = Seq(
      "platform-tools",
      s"build-tools;${buildToolsVersion()}",
      s"platforms;${platformsVersion()}",
      "cmdline-tools;latest"
    )
    // sdkmanager executable and state of the installed package is a shared resource, which can be accessed
    // from the different Android SDK modules.
    AndroidSdkLock.synchronized {
      val missingPackages = packages.filter(p => !isPackageInstalled(sdkPath0.path, p))
      val packagesWithoutLicense = missingPackages
        .map(p => (p, isLicenseAccepted(sdkPath0.path, remoteReposInfo()().path, p)))
        .filter(!_._2)
      if (packagesWithoutLicense.nonEmpty) {
        throw new IllegalStateException(
          "Failed to install the following SDK packages, because their respective" +
            s" licenses are not accepted:\n\n${packagesWithoutLicense.map(_._1).mkString("\n")}"
        )
      }

      if (missingPackages.nonEmpty) {
        val callResult = os.call(
          // Install platform-tools, build-tools, and the Android platform
          Seq(sdkManagerPath.toString) ++ missingPackages,
          stdout = os.Inherit
        )
        if (callResult.exitCode != 0) {
          throw new IllegalStateException(
            "Failed to install Android SDK components. Check logs for more details."
          )
        }
      }
    }
  }

  /**
   * Install the Android NDK (Native Development Kit) for building native code.
   */
  def installAndroidNdk: T[Unit] = Task {
    installAndroidSdkComponents()

    AndroidNdkLock.synchronized {
      os.call(
        Seq(
          sdkManagerPath().path.toString,
          "--install",
          s"ndk;${ndkVersion()}",
          s"cmake;${cmakeVersion()}"
        )
      )
    }
  }

  private def sdkPath: T[PathRef] = Task {
    Task.env.get("ANDROID_HOME")
      .orElse(Task.env.get("ANDROID_SDK_ROOT")) match {
      case Some(x) => PathRef(os.Path(x))
      case _ => throw new IllegalStateException("Android SDK location not found. Define a valid" +
          " SDK location with an ANDROID_HOME environment variable.")
    }
  }

  private def isPackageInstalled(sdkPath: os.Path, packageName: String): Boolean =
    os.exists(sdkPath / os.SubPath(packageName.replaceAll(";", "/")))

  private def isLicenseAccepted(
      sdkPath: os.Path,
      remoteReposInfo: os.Path,
      packageName: String
  ): Boolean = {
    val (licenseName, licenseHash) = licenseForPackage(remoteReposInfo, packageName)
    val licenseFile = sdkPath / "licenses" / licenseName
    os.exists(licenseFile) && os.isFile(licenseFile) && os.read(licenseFile).contains(licenseHash)
  }

  private def licenseForPackage(remoteReposInfo: os.Path, packageName: String): (String, String) = {
    val repositoryInfo = XML.loadFile(remoteReposInfo.toIO)
    val remotePackage = (repositoryInfo \ "remotePackage")
      .filter(_ \@ "path" == packageName)
      .head
    val licenseName = (remotePackage \ "uses-license").head \@ "ref"
    val licenseText = (repositoryInfo \ "license")
      .filter(_ \@ "id" == licenseName)
      .text
      .replaceAll(
        "(?<=\\s)[ \t]*",
        ""
      ) // remove spaces and tabs preceded by space, tab, or newline.
      .replaceAll("(?<!\n)\n(?!\n)", " ") // replace lone newlines with space
      .replaceAll(" +", " ")
      .trim
    val licenseHash = hexArray(sha1.digest(licenseText.getBytes(StandardCharsets.UTF_8)))
    (licenseName, licenseHash)
  }

  def remoteReposInfo(): Command[PathRef] = Task.Command {
    if (Task.offline) Result.Failure("Can't fetch remote repositories in offline mode.")
    else Result.create {
      // shouldn't be persistent, allow it to be re-downloaded again.
      // it will be called only if some packages are not installed.
      val path = Task.dest / "repository.xml"
      os.write(
        Task.dest / "repository.xml",
        requests.get(remotePackagesUrl).bytes
      )
      PathRef(path)
    }
  }

  private def sha1 = MessageDigest.getInstance("sha1")

  private def hexArray(arr: Array[Byte]) =
    String.format("%0" + (arr.length << 1) + "x", new BigInteger(1, arr))

  // TODO consolidate with sdkmanager path
  private def findLatestSdkManager(sdkPath: os.Path): Option[os.Path] = {
    var sdkManagerPath = sdkPath / "cmdline-tools/latest/bin/sdkmanager"
    if (!os.exists(sdkManagerPath)) {
      // overall it can be cmdline-tools/<version>
      val candidates = os.list(sdkPath / "cmdline-tools")
        .filter(os.isDir)
      if (candidates.nonEmpty) {
        val latestCmdlineToolsPath = candidates
          .map(p => (p, p.baseName.split('.')))
          .filter(_._2 match {
            case Array(_, _) => true
            case _ => false
          })
          .maxBy(_._2.head.toInt)._1
        sdkManagerPath = latestCmdlineToolsPath / "bin/sdkmanager"
      }
    }
    Some(sdkManagerPath).filter(os.exists)
  }

}

private object AndroidSdkLock
private object AndroidNdkLock

object AndroidSdkModule {

  /**
   * Declaration of the Maven Google Repository.
   */
  val mavenGoogle: MavenRepository = MavenRepository("https://maven.google.com/")
}
