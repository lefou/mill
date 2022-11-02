package mill.api

import os.{Path, SubPath}

import java.nio.{file => jnio}
import java.security.{DigestOutputStream, MessageDigest}
import scala.util.{DynamicVariable, Try, Using}
import upickle.default.{ReadWriter => RW}

/**
 * A wrapper around `os.Path` that calculates it's hashcode based
 * on the contents of the filesystem underneath it. Used to ensure filesystem
 * changes can bust caches which are keyed off hashcodes.
 */
case class PathRef(path: os.Path, quick: Boolean, sig: Int) {
//  override def hashCode(): Int = 41 * (41 + path.hashCode()) + sig
}

object PathRef {

  private[mill] val pathRefContext: DynamicVariable[Seq[(String, os.Path)]] =
    new DynamicVariable(Seq())

  private def pathContext(path: os.Path): Option[(String, os.Path, os.SubPath)] = {
    pathRefContext.value.to(LazyList).map {
      case (ctx, ctxPath) => Try {
          (ctx, ctxPath, path.subRelativeTo(ctxPath))
        }
    }.find(_.isSuccess).map(_.get)
  }

  private val quickEnabled = {
    val prop = sys.props.get("mill.api.PathRef.quick").getOrElse("true")
    Try(prop.toBoolean)
      .getOrElse(throw new IllegalArgumentException(
        s"""System property "mill.api.PathRef.quick" value "${prop}" cannot be parsed as Boolean."""
      ))
  }

  /**
   * Create a [[PathRef]] by recursively digesting the content of a given `path`.
   *
   * @param path The digested path.
   * @param quick If `true` the digest is only based to some file attributes (like mtime and size).
   *              If `false` the digest is created of the files content.
   * @return
   */
  def apply(path: os.Path, quick: Boolean = false): PathRef = {
    val basePath = path
    val useQuick = quick && quickEnabled

    val sig = {
      val isPosix = path.wrapped.getFileSystem.supportedFileAttributeViews().contains("posix")
      val digest = MessageDigest.getInstance("MD5")
      val digestOut = new DigestOutputStream(DummyOutputStream, digest)

      def updateWithInt(value: Int): Unit = {
        digest.update((value >>> 24).toByte)
        digest.update((value >>> 16).toByte)
        digest.update((value >>> 8).toByte)
        digest.update(value.toByte)
      }

      if (os.exists(path)) {
        for (
          (path, attrs) <-
            os.walk.attrs(path, includeTarget = true, followLinks = true).sortBy(_._1.toString)
        ) {
          val sub = path.subRelativeTo(basePath)
          digest.update(sub.toString().getBytes())
          if (!attrs.isDir) {
            if (isPosix) {
              updateWithInt(os.perms(path, followLinks = false).value)
            }
            if (useQuick) {
              val value = (attrs.mtime, attrs.size).hashCode()
              updateWithInt(value)
            } else if (jnio.Files.isReadable(path.toNIO)) {
              val is =
                try Some(os.read.inputStream(path))
                catch {
                  case _: jnio.FileSystemException =>
                    // This is known to happen, when we try to digest a socket file.
                    // We ignore the content of this file for now, as we would do,
                    // when the file isn't readable.
                    // See https://github.com/com-lihaoyi/mill/issues/1875
                    None
                }
              is.foreach {
                Using.resource(_) { is =>
                  StreamSupport.stream(is, digestOut)
                }
              }
            }
          }
        }
      }

      java.util.Arrays.hashCode(digest.digest())
    }

    new PathRef(path, useQuick, sig)
  }

  /**
   * Default JSON formatter for [[PathRef]].
   */
  implicit def jsonFormatter: RW[PathRef] = upickle.default.readwriter[String].bimap[PathRef](
    p => {
      val ctxPath: Option[(String, Path, SubPath)] = pathContext(p.path)

      val prefix = if (p.quick) "qref:" else "ref:"
      val sig = String.format("%08x", p.sig: Integer)

      val suffix = ctxPath match {
        case Some((ctx, base, subPath)) =>
          val res = s":${ctx}:${subPath.toString()}"
          res
        case None => s":root:${p.path.toString()}"
      }

      val out = prefix + sig + suffix
      out
    },
    s => {
      val Array(prefix, hex, oldPath) = s.split(":", 3)

      val quick = prefix match {
        case "qref" => true
        case "ref" => false
      }

      // Parsing to a long and casting to an int is the only way to make
      // round-trip handling of negative numbers work =(
      val sig = java.lang.Long.parseLong(hex, 16).toInt

      val fullPath = oldPath.split(":", 2) match {
        case Array(path) => os.Path(path)
        case Array("root", path) => os.Path(path)
        case Array(ctx, path) =>
          val base = pathRefContext.value.find(_._1 == ctx).getOrElse(
            sys.error(s"Unsupported PathRef context '${ctx}' found")
          )._2
          base / os.SubPath(path)
      }

      PathRef(fullPath, quick, sig)
    }
  )
}
