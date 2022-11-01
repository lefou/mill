package mill.api

import os.{Path, SubPath}

import java.nio.{file => jnio}
import java.security.{DigestOutputStream, MessageDigest}
import scala.util.{Try, Using}
import upickle.default.{ReadWriter => RW}

/**
 * A wrapper around `os.Path` that calculates it's hashcode based
 * on the contents of the filesystem underneath it. Used to ensure filesystem
 * changes can bust caches which are keyed off hashcodes.
 */
case class PathRef(path: os.Path, quick: Boolean, sig: Int) {
  override def hashCode(): Int = sig
}

object PathRef {

//  private[mill] def pathContexts: Seq[(String, os.Path)] = _pathContexts

  private[mill] var _pathContexts: Seq[(String, os.Path)] = Seq()

  private def pathContext(path: os.Path): Option[(String, os.Path, os.SubPath)] = {
    _pathContexts.to(LazyList).map {
      case (ctx, ctxPath) => Try {
          (ctx, ctxPath, path.subRelativeTo(ctxPath))
        }.toOption
    }.find(_.isDefined).flatten
  }

  /**
   * Create a [[PathRef]] by recursively digesting the content of a given `path`.
   * @param path The digested path.
   * @param quick If `true` the digest is only based to some file attributes (like mtime and size).
   *              If `false` the digest is created of the files content.
   * @return
   */
  def apply(path: os.Path, quick: Boolean = false): PathRef = {
    val optCtx = pathContext(path)

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
          optCtx match {
            case Some((ctx, base, subPath)) =>
              digest.update(ctx.getBytes())
              digest.update(subPath.toString().getBytes())
            case None =>
              digest.update(path.toString.getBytes())
          }
          if (!attrs.isDir) {
            if (isPosix) {
              updateWithInt(os.perms(path, followLinks = false).value)
            }
            if (quick) {
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
    new PathRef(path, quick, sig)
  }

  /**
   * Default JSON formatter for [[PathRef]].
   */
  implicit def jsonFormatter: RW[PathRef] = upickle.default.readwriter[String].bimap[PathRef](
    p => {
      val ctxPath = pathContext(p.path)

      val ctxSuffix = if (ctxPath.isDefined) "c" else ""
      val quickPrefix = if (p.quick) "q" else ""

      val prefix = (p.quick, ctxPath.isDefined) match {
        case (true, false) => "qref"
        case (true, true) => "cqref"
        case (false, false) => "ref"
        case (false, true) => "cref"
      }
      val suffix = ctxPath match {
        case Some((ctx, base, subPath)) =>
          s"${ctx}:${subPath.toString()}"
        case None => p.path.toString()
      }

      prefix + ":" +
        String.format("%08x", p.sig: Integer) + ":" +
        suffix
    },
    s => {
      val Array(prefix, hex, path) = s.split(":", 3)

      val (quick, withCtx) = prefix match {
        case "qref" => (true, false)
        case "cqref" => (true, true)
        case "ref" => (false, false)
        case "cref" => (false, true)
      }
      // Parsing to a long and casting to an int is the only way to make
      // round-trip handling of negative numbers work =(
      val sig = java.lang.Long.parseLong(hex, 16).toInt

      val fullPath = if (withCtx) {
        val Array(ctx, subPath) = path.split(":", 2)
        val base = _pathContexts.find(_._1 == ctx).get._2
        base / os.SubPath(subPath)
      } else {
        os.Path(path)
      }

      PathRef(fullPath, quick, sig)
    }
  )
}
